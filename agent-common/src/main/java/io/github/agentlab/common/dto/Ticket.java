package io.github.agentlab.common.dto;

import io.github.agentlab.common.enums.TicketPriority;
import io.github.agentlab.common.enums.TicketStatus;

import java.time.Instant;

/**
 * 已创建的工单（对外 DTO，不暴露 JPA 实体）。
 */
public record Ticket(
        String ticketId,
        String orderNo,
        String reason,
        TicketPriority priority,
        TicketStatus status,
        Instant createdAt
) {
}
