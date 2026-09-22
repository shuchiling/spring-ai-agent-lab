package io.github.agentlab.aicore.service;

import io.github.agentlab.businesstools.tool.AfterSalesTool;
import io.github.agentlab.businesstools.tool.LogisticsTool;
import io.github.agentlab.businesstools.tool.OrderTool;
import io.github.agentlab.businesstools.tool.TicketCreateTool;
import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.common.dto.ToolRouteDecision;
import io.github.agentlab.common.enums.ToolRouteType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单客服 Agent 编排服务。
 *
 * <p>这个类不是单纯的模型调用封装，而是一个轻量编排层：它把 ChatClient、业务工具、
 * system prompt、工具路由规则、评测样本和可观测日志组合起来，形成一个可运行、可检查的业务 Agent。</p>
 *
 * <p>当前阶段主要解决几个工程问题：</p>
 * <ul>
 *     <li>多工具选择：通过 {@link #businessChat(String)} 挂载查询类工具与工单草稿工具，让模型根据用户语义选择工具。</li>
 *     <li>工具边界控制：prompt 描述使用“订单状态查询工具”等业务能力名称，而不是 Java 类名，降低模型对实现细节的依赖。</li>
 *     <li>参数缺失控制：用户没有提供有效订单号时，要求模型追问订单号，而不是调用工具或编造参数。</li>
 *     <li>结构化路由：通过 {@link #routeTool(String)} 只判断路线，不挂载 tools，避免评测阶段真的调用业务工具。</li>
 *     <li>输出收敛：路由结果只能落在 ToolRouteType 枚举内，减少模型输出自由文本导致的不可控问题。</li>
 *     <li>路由评测：通过 {@link #evalToolRoute()} 内置样本对比预期和实际，避免 prompt 修改后只能靠人工感觉判断是否退化。</li>
 *     <li>可观测性：关键日志带 traceId、用户输入、路由结果、是否通过、耗时，便于排查模型为什么选了某个工具。</li>
 * </ul>
 *
 * <p>后续如果工具继续增加，可以把重复的校验、日志、样本管理下沉到 agent-orchestrator 或公共组件中。</p>
 */
@Service
@Slf4j
public class BusinessAgentService {

    private final ChatClient chatClient;
    private final OrderTool orderTool;
    private final LogisticsTool logisticsTool;
    private final AfterSalesTool afterSalesTool;
    private final TicketCreateTool ticketCreateTool;

    public BusinessAgentService(ChatClient.Builder chatClientBuilder,
                                OrderTool orderTool,
                                LogisticsTool logisticsTool,
                                AfterSalesTool afterSalesTool,
                                TicketCreateTool ticketCreateTool) {
        this.chatClient = chatClientBuilder
                .build();
        this.orderTool = orderTool;
        this.logisticsTool = logisticsTool;
        this.afterSalesTool = afterSalesTool;
        this.ticketCreateTool = ticketCreateTool;
    }

    /**
     * 真实业务对话入口。
     *
     * <p>这里会挂载真实工具：订单状态、物流、售后查询，以及售后工单草稿创建。
     * 模型根据 system prompt 和工具 schema 自主决定是否调用工具以及调用哪个工具。</p>
     *
     * <p>注意：工具选择由模型决策，但边界由工程控制。写操作只能生成草稿和 confirmToken，
     * 真正落库由用户调用 {@code /api/agent/ticket/confirm}，模型不得声称已创建工单。</p>
     */
    public String businessChat(String message) {
        String userMessage = normalizeMessage(message);
        long startTime = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        try {
            String content = chatClient.prompt()
                    .system("""
                            你是一个订单客服助手。
                            用户问订单状态、是否发货、是否签收时，调用订单状态查询工具。
                            用户问快递、物流、包裹到哪里了、配送进度时，调用物流查询工具。
                            用户问退款、退货、售后、售后进度时，调用售后退款查询工具。
                            用户明确要求创建售后工单、退货退款单，且已提供订单号、退款原因和优先级时，
                            调用创建售后工单草稿工具；该工具只生成草稿和 confirmToken，不会真正创建工单。
                            生成草稿后，明确告知用户需使用 confirmToken 走确认接口后才能落库，不要声称工单已创建。
                            缺少订单号、退款原因或优先级时，先追问，不要调用创建工单草稿工具。
                            缺少订单号时，追问订单号
                            问题不属于订单客服范围时，说明只能处理订单、物流、售后相关问题
                            不要编造工具返回之外的信息
                            工具返回 success=false 时，不要编造
                            订单不存在时，提示用户检查订单号
                            参数缺失时，追问订单号
                            """)
                    .user(userMessage)
                    .tools(orderTool, logisticsTool, afterSalesTool, ticketCreateTool)
                    .call()
                    .content();
            log.info("模型调用成功: 类型=订单客服, traceId={}, 用户输入={}, 耗时={}ms",
                    traceId, userMessage, System.currentTimeMillis() - startTime);
            return content;
        } catch (RuntimeException e) {
            log.error("模型调用异常: 类型=订单客服, traceId={}, 用户输入={}, 耗时={}ms",
                    traceId, userMessage, System.currentTimeMillis() - startTime, e);
            throw e;
        }
    }


    /**
     * 工具路由判断入口。
     *
     * <p>这个方法只做“应该走哪个工具路线”的结构化判断，不挂载 {@code .tools(...)}，
     * 所以不会真正调用 OrderTool、LogisticsTool 或 AfterSalesTool。它用于评测、调试和观察模型路由能力。</p>
     *
     * <p>这里重点修复了几个容易踩坑的点：</p>
     * <ul>
     *     <li>明确 routeType 只能取 ToolRouteType 中的固定枚举值，避免模型输出任意字符串。</li>
     *     <li>明确“属于业务范围但缺订单号”应返回 NEED_ORDER_NO，而不是误判为具体工具。</li>
     *     <li>明确“业务范围外”应返回 OUT_OF_SCOPE，避免订单客服 Agent 回答 RAG、天气等无关问题。</li>
     *     <li>统一使用清洗后的 userMessage，避免日志和模型输入不一致。</li>
     *     <li>日志记录 routeType、hasOrderNo、orderNo 和 reason，方便复盘模型选择依据。</li>
     * </ul>
     */
    public ToolRouteDecision routeTool(String message) {
        String userMessage = normalizeMessage(message);
        long startTime = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        try {
            ToolRouteDecision routeDecision = chatClient.prompt()
                    .system("""
                            你是一个订单客服工具路由评测助手，只判断用户问题应该走哪个工具路线，不要调用任何工具。
                            routeType 只能从以下枚举中选择：ORDER_QUERY、LOGISTICS_QUERY、AFTER_SALES_QUERY、NEED_ORDER_NO、OUT_OF_SCOPE、UNKNOWN。
                            用户问订单状态、是否发货、是否签收，且提供了有效订单号时，routeType=ORDER_QUERY。
                            用户问快递、物流、包裹到哪里了、配送进度，且提供了有效订单号时，routeType=LOGISTICS_QUERY。
                            用户问退款、退货、售后、售后进度，且提供了有效订单号时，routeType=AFTER_SALES_QUERY。
                            用户问题属于订单、物流、售后范围，但缺少有效订单号时，routeType=NEED_ORDER_NO。
                            用户问题不属于订单客服范围时，routeType=OUT_OF_SCOPE。
                            只有确实无法判断时，routeType=UNKNOWN。
                            有效订单号格式为 ORD-yyyyMMdd-四位数字，例如 ORD-20260911-0001。
                            hasOrderNo 只有在用户提供有效订单号时才为 true，否则为 false。
                            orderNo 只填写用户提供的有效订单号；没有有效订单号时填写 null。
                            reason 用一句中文说明判断依据。
                            """)
                    .user(userMessage)
                    .call()
                    .entity(ToolRouteDecision.class);
            assert routeDecision != null;
            log.info("模型调用成功: 类型=工具路由, traceId={}, 用户输入={}, routeType={}, hasOrderNo={}, orderNo={}, reason={}, 耗时={}ms",
                    traceId, userMessage, routeDecision.routeType(), routeDecision.hasOrderNo(), routeDecision.orderNo(),
                    routeDecision.reason(), System.currentTimeMillis() - startTime);
            return routeDecision;
        } catch (Exception e) {
            log.error("模型调用异常: 类型=工具路由, traceId={}, 用户输入={}, 耗时={}ms",
                    traceId, userMessage, System.currentTimeMillis() - startTime, e);
            throw e;
        }
    }

    /**
     * 工具路由轻量评测入口。
     *
     * <p>内置一组典型用户问题作为评测样本，逐条调用 {@link #routeTool(String)}，
     * 对比预期路由和模型实际路由，返回总数、通过数、准确率和逐条明细。</p>
     *
     * <p>这个评测的价值不是追求一次跑满分，而是让 prompt 和路由规则变更变得可观察：
     * 如果某次修改导致“帮我查物流”从 NEED_ORDER_NO 退化成 LOGISTICS_QUERY，
     * 评测结果和日志能第一时间暴露问题。</p>
     */
    public ToolRouteEvalResponse evalToolRoute() {
        long startTime = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        List<ToolRouteEvalSample> samples = List.of(
                new ToolRouteEvalSample("查一下 ORD-20260911-0001 的订单状态", ToolRouteType.ORDER_QUERY),
                new ToolRouteEvalSample("ORD-20260911-0001 到哪了", ToolRouteType.LOGISTICS_QUERY),
                new ToolRouteEvalSample("ORD-20260911-0002 退款怎么样了", ToolRouteType.AFTER_SALES_QUERY),
                new ToolRouteEvalSample("帮我查物流", ToolRouteType.NEED_ORDER_NO),
                new ToolRouteEvalSample("我要退货", ToolRouteType.NEED_ORDER_NO),
                new ToolRouteEvalSample("RAG 是什么", ToolRouteType.OUT_OF_SCOPE),
                new ToolRouteEvalSample("ORD-20260911-0003 签收了吗", ToolRouteType.ORDER_QUERY),
                new ToolRouteEvalSample("ORD-20260911-0003 快递送到了吗", ToolRouteType.LOGISTICS_QUERY),
                new ToolRouteEvalSample("查一下 ORD-20260911-9999 的售后", ToolRouteType.AFTER_SALES_QUERY),
                new ToolRouteEvalSample("今天天气怎么样", ToolRouteType.OUT_OF_SCOPE)
        );

        List<ToolRouteEvalItem> items = new ArrayList<>();
        int passed = 0;
        for (ToolRouteEvalSample sample : samples) {
            long sampleStartTime = System.currentTimeMillis();
            ToolRouteDecision actual = routeTool(sample.input());
            boolean itemPassed = sample.expected() == actual.routeType();
            if (itemPassed) {
                passed++;
            }
            log.info("工具路由评测样本完成: traceId={}, 用户输入={}, 预期={}, 实际={}, 是否通过={}, reason={}, 耗时={}ms",
                    traceId, sample.input(), sample.expected(), actual.routeType(), itemPassed,
                    actual.reason(), System.currentTimeMillis() - sampleStartTime);
            items.add(new ToolRouteEvalItem(
                    sample.input(),
                    sample.expected(),
                    actual.routeType(),
                    itemPassed,
                    actual.reason(),
                    actual.hasOrderNo(),
                    actual.orderNo()
            ));
        }

        ToolRouteEvalResponse response = new ToolRouteEvalResponse(
                samples.size(),
                passed,
                (double) passed / samples.size(),
                items
        );
        log.info("工具路由评测完成: traceId={}, 总数={}, 通过数={}, 准确率={}, 耗时={}ms",
                traceId, response.total(), response.passed(), response.accuracy(), System.currentTimeMillis() - startTime);
        return response;
    }

    private String normalizeMessage(String message) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message.trim();
    }

    private record ToolRouteEvalSample(String input, ToolRouteType expected) {
    }

    public record ToolRouteEvalResponse(
            int total,
            int passed,
            double accuracy,
            List<ToolRouteEvalItem> items
    ) {
    }

    public record ToolRouteEvalItem(
            String input,
            ToolRouteType expected,
            ToolRouteType actual,
            boolean passed,
            String reason,
            boolean hasOrderNo,
            String orderNo
    ) {
    }

}
