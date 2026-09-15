package io.github.agentlab.common.dto;

import io.github.agentlab.common.enums.TicketPriority;

import java.time.Instant;

/**
 * 工单草稿。模型调用工具后返回给用户，由用户二次确认才真正落库。
 *
 * <p>关键点：草稿不带 ticketId（还没创建），只带 confirmToken。
 * 真正的 ticketId 在 confirm 之后才存在。这条边界是"模型提议、后端执行"的代码体现。</p>
 */
public record TicketDraft(
        String draftId,
        String confirmToken,
        String idempotencyKey,
        String orderNo,
        String reason,
        TicketPriority priority,
        Instant expireAt
) {
}
