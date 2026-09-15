package io.github.agentlab.businesstools.tool;

import io.github.agentlab.businesstools.service.TicketService;
import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.common.dto.TicketDraft;
import io.github.agentlab.common.enums.ErrorCode;
import io.github.agentlab.common.enums.TicketPriority;
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

        // TODO 任务04（核心/编排）：实现校验 → 调 TicketService.createDraft → 组装 ToolResult
        //  参照 OrderTool 的日志和错误处理风格。
        throw new UnsupportedOperationException("TODO 任务04: createTicketDraft 由你实现");
    }

    private void logStart(String tool, String traceId, String orderNo) {
        log.info("工具调用开始: 工具名称={}, traceId={}, 订单号={}", tool, traceId, orderNo);
    }

    /**
     * 工具方法返回结构。success=false 时 errorCode/errorMessage 有值，confirmToken 为 null。
     */
    public record ToolResult(boolean success,
                             String confirmToken,
                             String draftId,
                             String orderNo,
                             String errorCode,
                             String errorMessage) {
        public static ToolResult ok(TicketDraft draft) {
            return new ToolResult(true, draft.confirmToken(), draft.draftId(), draft.orderNo(), null, null);
        }

        public static ToolResult fail(String orderNo, ErrorCode ec, String msg) {
            return new ToolResult(false, null, null, orderNo, ec.getCode(), msg);
        }
    }
}
