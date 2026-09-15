package io.github.agentlab.dao.entity;

import io.github.agentlab.common.enums.TicketAuditAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 工单审计日志实体（对应 ticket_audit_log 表）。
 *
 * <p>为什么单独一张表而不合并进 tickets？</p>
 * <ul>
 *     <li>一张工单生命周期会有多次事件（生成草稿、确认、后续可能取消/改派），主表只能存当前状态，审计要存事件流。</li>
 *     <li>审计表是 append-only，主表是 mutable，生命周期和写入模式不同。</li>
 *     <li>payload 存当时的快照 JSON，便于事后还原"确认那一刻工单长什么样"，而不是当前状态。</li>
 * </ul>
 */
@Entity
@Table(name = "ticket_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @Column(name = "ticket_id", length = 64)
    private String ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 32, nullable = false)
    private TicketAuditAction action;

    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "trace_id", length = 64, nullable = false)
    private String traceId;

    /**
     * 草稿/工单快照 JSON。开发期用 TEXT；生产建议 jsonb 以支持查询。
     */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
