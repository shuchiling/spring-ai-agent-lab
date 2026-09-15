package io.github.agentlab.common.enums;

/**
 * 工单审计动作。append-only 事件流的类型标记。
 */
public enum TicketAuditAction {
    /** 草稿生成（模型/用户提议创建工单，尚未落库） */
    DRAFT_CREATED,
    /** 草稿确认后真正落库为工单 */
    TICKET_CONFIRMED
}
