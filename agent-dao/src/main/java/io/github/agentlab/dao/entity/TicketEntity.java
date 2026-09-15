package io.github.agentlab.dao.entity;

import io.github.agentlab.common.enums.TicketPriority;
import io.github.agentlab.common.enums.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 工单实体（对应 tickets 表）。
 *
 * <p>注意：实体不直接暴露给 Controller，Service 层负责 Entity ↔ DTO 转换。
 * 这是为了避免 JPA 实体的脏字段/代理对象泄漏到 API 层，也是真实后端的分层习惯。</p>
 */
@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketEntity {

    @Id
    @Column(name = "ticket_id", length = 64)
    private String ticketId;

    @Column(name = "order_no", length = 32, nullable = false)
    private String orderNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 16, nullable = false)
    private TicketPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private TicketStatus status;

    /** 幂等兜底字段，DB 唯一约束会挡住并发击穿。 */
    @Column(name = "idempotency_key", length = 64, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
