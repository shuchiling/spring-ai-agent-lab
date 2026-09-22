package io.github.agentlab.businesstools.tool;

import com.alibaba.fastjson.JSON;
import io.github.agentlab.businesstools.service.TicketService;
import io.github.agentlab.businesstools.util.OrderUtil;
import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.common.dto.TicketDraft;
import io.github.agentlab.common.enums.ErrorCode;
import io.github.agentlab.common.enums.TicketPriority;
import io.github.agentlab.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 工单创建工具（任务 04）。
 *
 * <p>这是 Agent 工具边界最关键的一课：模型能调用的工具方法 {@link #createTicketDraft}
 * 只生成"草稿"，不真正落库。真正的写操作发生在 {@link TicketService#confirm}，
 * 由后端独立接口触发。模型永远拿不到"直接创建工单"的能力。</p>
 *
 * <p>对比 OrderTool：OrderTool 是只读查询，错了没副作用；本工具是写操作的"提议入口"，
 * 错了不能撤销，所以必须经过人审。这就是为什么这关单独存在。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TicketCreateTool {

    private final TicketService ticketService;

    /**
     * 创建工单草稿（不落库）。
     *
     * <p><b>场景意义</b>：用户说"ORD-xxx 商品破损要退货退款"时，模型调用本工具收集
     * orderNo/reason/priority，生成草稿 + confirmToken 返回给用户。用户确认后才真正创建。</p>
     *
     * <p><b>实现要求</b>：</p>
     * <ol>
     *     <li>入参校验：orderNo 格式（ORD-yyyyMMdd-xxxx）、reason 非空、priority 非空。
     *         校验失败返回 success=false 的结果（参照 OrderTool 的写法），不要抛异常。</li>
     *     <li>调用 {@link TicketService#createDraft} 拿草稿。</li>
     *     <li>日志带 traceId、工具名、orderNo、draftId、耗时。</li>
     *     <li>返回 {@link ToolResult}，把 confirmToken 带回去——用户需要它来做二次确认。</li>
     * </ol>
     *
     * <p><b>注意</b>：工具方法返回的是结构化结果，不是自由文本。模型基于这个结果决定
     * 怎么告诉用户"工单草稿已生成，请确认"。返回结构要稳定，方便模型理解和评测。</p>
     */
    @Tool(description = "创建售后工单草稿。用户想退款/退货/售后时调用。"
            + "这个工具只生成草稿，不会真正创建工单，需要用户确认 confirmToken 后才落库。"
            + "缺少订单号、退款原因时不要调用，先向用户追问。")
    public ToolResult createTicketDraft(
            @ToolParam(description = "订单号，格式 ORD-yyyyMMdd-xxxx，例如 ORD-20260911-0001")
            String orderNo,
            @ToolParam(description = "退款原因，用户描述的售后诉求")
            String reason,
            @ToolParam(description = "优先级：LOW / NORMAL / HIGH / URGENT")
            TicketPriority priority) {

        long start = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        String toolName = "createTicketDraft";
        logStart(toolName, traceId, orderNo);
        
        // ===== 前置校验：格式与必填项 =====
        ToolResult validationResult = preHandle(toolName, traceId, start, orderNo, reason, priority);
        if (validationResult != null) {
            return validationResult;
        }
        
        try {
            // ===== 调用草稿创建服务 =====
            TicketDraft draft = ticketService.createDraft(orderNo, reason, priority);
            ToolResult result = StringUtils.isBlank(draft.draftId()) 
                    ? ToolResult.fail(orderNo, ErrorCode.INTERNAL_ERROR, "草稿生成失败") 
                    : ToolResult.ok(draft);
            logToolResult(toolName, traceId, result, start);
            return result;
            
        } catch (BusinessException ex) {
            // ===== 特殊处理：工单已创建 =====
            // 场景：confirm 成功后，模型/用户再次调用本 Tool（同一 orderNo + reason）
            // Service 抛 TICKET_ALREADY_CREATED，此处转换成「业务成功」的响应
            if (ex.getErrorCode() == ErrorCode.TICKET_ALREADY_CREATED) {
                // 从异常消息里提取 ticketId（格式："工单已创建（工单号: T-001）..."）
                String message = ex.getMessage();
                String ticketId = extractTicketId(message);
                
                ToolResult result = ToolResult.alreadyCreated(orderNo, ticketId, message);
                log.info("工具调用-工单已存在: 工具名称={}, traceId={}, 订单号={}, 已有工单={}, 耗时={}ms",
                        toolName, traceId, orderNo, ticketId, System.currentTimeMillis() - start);
                return result;
            }
            
            // ===== 其他业务异常：正常失败 =====
            ToolResult result = ToolResult.fail(orderNo, ex.getErrorCode(), ex.getMessage());
            logToolResult(toolName, traceId, result, start);
            return result;
        }
    }

    private void logStart(String tool, String traceId, String orderNo) {
        log.info("工具调用开始: 工具名称={}, traceId={}, 订单号={}", tool, traceId, orderNo);
    }

    private ToolResult preHandle(String toolName, String traceId, long start, String orderNo, String reason, TicketPriority priority) {
        if (StringUtils.isBlank(orderNo)) {
            ToolResult result = ToolResult.fail(null, ErrorCode.BAD_REQUEST, "订单号不能为空");
            logToolResult(toolName, traceId, result, start);
            return ToolResult.fail(null, ErrorCode.BAD_REQUEST, "订单号不能为空");
        }
        if (!OrderUtil.isValidOrderNo(orderNo)) {
            ToolResult result = ToolResult.fail(orderNo, ErrorCode.ILLEGAL_ARGUMENT, "订单号不合法");
            logToolResult(toolName, traceId, result, start);
            return ToolResult.fail(null, ErrorCode.BAD_REQUEST, "订单号不合法");
        }
        if (StringUtils.isBlank(reason)) {
            ToolResult result = ToolResult.fail(null, ErrorCode.BAD_REQUEST, "退款原因不能为空");
            logToolResult(toolName, traceId, result, start);
            return ToolResult.fail(null, ErrorCode.BAD_REQUEST, "退款原因不能为空");
        }
        if (priority == null) {
            ToolResult result = ToolResult.fail(null, ErrorCode.BAD_REQUEST, "工单优先级不能为空");
            logToolResult(toolName, traceId, result, start);
            return result;
        }
        return null;
    }


    private void logToolResult(String toolName, String traceId, ToolResult result, long start) {
        if (result.success()) {
            log.info("工具调用成功: 工具名称={}, traceId={}, 订单号={}, 草稿号={}, success={}, 耗时={}ms, 返回结果={}",
                    toolName, traceId, result.orderNo(), result.draftId, true, System.currentTimeMillis() - start,
                    JSON.toJSONString(result));
            return;
        }
        log.warn("工具调用业务失败: 工具名称={}, traceId={}, 订单号={},草稿号={}, success={}, 错误码={}, 耗时={}ms, 返回结果={}",
                toolName, traceId, result.orderNo(), result.draftId, false, result.errorCode(), System.currentTimeMillis() - start,
                JSON.toJSONString(result));
    }

    /**
     * 从异常消息中提取工单号。
     * 预期格式："工单已创建（工单号: T-001）..."
     */
    private String extractTicketId(String message) {
        if (message == null) {
            return null;
        }
        int start = message.indexOf("工单号: ");
        if (start == -1) {
            return null;
        }
        start += 5; // "工单号: ".length()
        int end = message.indexOf("）", start);
        if (end == -1) {
            end = message.indexOf(",", start);
        }
        if (end == -1) {
            end = message.length();
        }
        return message.substring(start, end).trim();
    }

    /**
     * 工具方法返回结构。
     *
     * <p>三种成功状态：</p>
     * <ul>
     *     <li>新建草稿：success=true, confirmToken 有值, ticketId=null</li>
     *     <li>幂等返回草稿：同上（对模型来说和新建无区别）</li>
     *     <li>工单已存在：success=true, confirmToken=null, ticketId 有值</li>
     * </ul>
     *
     * <p>失败状态：success=false, errorCode/errorMessage 有值</p>
     */
    public record ToolResult(
            boolean success,
            String confirmToken,
            String draftId,
            String orderNo,
            String ticketId,        // 新增：工单已存在时返回
            String errorCode,
            String errorMessage
    ) {
        /** 新建或幂等返回草稿 */
        public static ToolResult ok(TicketDraft draft) {
            return new ToolResult(
                    true,
                    draft.confirmToken(),
                    draft.draftId(),
                    draft.orderNo(),
                    null,  // 草稿阶段无 ticketId
                    null,
                    null
            );
        }

        /** 工单已创建（confirm 后再调 Tool） */
        public static ToolResult alreadyCreated(String orderNo, String ticketId, String message) {
            return new ToolResult(
                    true,
                    null,  // 无新 confirmToken
                    null,
                    orderNo,
                    ticketId,
                    null,
                    message  // 带工单号的提示信息
            );
        }

        /** 业务校验失败或系统异常 */
        public static ToolResult fail(String orderNo, ErrorCode ec, String msg) {
            return new ToolResult(false, null, null, orderNo, null, ec.getCode(), msg);
        }
    }
}
