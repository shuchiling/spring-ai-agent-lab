package io.github.agentlab.common.enums;

/**
 * 工单状态。任务 04 只用 DRAFT / CREATED 两态。
 * 后续 Orchestrator（任务 10）再扩展 IN_PROGRESS / CLOSED / CANCELLED 等。
 */
public enum TicketStatus {
    DRAFT,
    CREATED
}
