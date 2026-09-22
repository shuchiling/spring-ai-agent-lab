package io.github.agentlab.businesstools.service;

import com.alibaba.fastjson.JSON;
import io.github.agentlab.common.dto.TicketDraft;
import io.github.agentlab.common.enums.ErrorCode;
import io.github.agentlab.common.enums.TicketAuditAction;
import io.github.agentlab.common.enums.TicketStatus;
import io.github.agentlab.common.exception.BusinessException;
import io.github.agentlab.dao.entity.TicketEntity;
import io.github.agentlab.dao.repository.TicketAuditLogRepository;
import io.github.agentlab.dao.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 工单 DB 写入专用服务：单独拆出 {@link Transactional} 边界，避免 {@link TicketService}
 * 在同类自调用时事务不生效，也便于在 confirm 编排里「先提交事务、再清 Redis」。
 */
@Service
@RequiredArgsConstructor
public class TicketPersistenceService {

    private final TicketRepository ticketRepository;
    private final TicketAuditLogRepository auditLogRepository;

    /**
     * 在单事务内落库工单并写 TICKET_CONFIRMED 审计。
     * 撞 idempotency_key 唯一约束时回查已存在记录，作为并发兜底。
     */
    @Transactional
    public TicketEntity persistConfirmedTicket(TicketDraft draft, String traceId) {
        TicketEntity entity = TicketEntity.builder()
                .ticketId(UUID.randomUUID().toString())
                .orderNo(draft.orderNo())
                .reason(draft.reason())
                .priority(draft.priority())
                .status(TicketStatus.CREATED)
                .idempotencyKey(draft.idempotencyKey())
                .traceId(traceId)
                .createdAt(Instant.now())
                .build();
        try {
            TicketEntity saved = ticketRepository.save(entity);
            auditLogRepository.save(TicketService.buildAudit(
                    TicketAuditAction.TICKET_CONFIRMED,
                    saved.getTicketId(),
                    traceId,
                    JSON.toJSONString(TicketService.toDto(saved))));
            return saved;
        } catch (DataIntegrityViolationException ex) {
            return ticketRepository.findByIdempotencyKey(draft.idempotencyKey())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_CREATE_FAILED));
        }
    }
}
