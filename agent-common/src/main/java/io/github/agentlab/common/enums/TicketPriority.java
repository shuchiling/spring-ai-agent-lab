package io.github.agentlab.common.enums;

/**
 * 工单优先级。枚举名直接入库（@Enumerated(STRING)），保证可读、可演进。
 */
public enum TicketPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}
