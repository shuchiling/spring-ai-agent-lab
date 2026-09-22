package io.github.agentlab.businesstools.service;

import io.github.agentlab.businesstools.tool.OrderTool;
import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.common.dto.Ticket;
import io.github.agentlab.common.dto.TicketDraft;
import io.github.agentlab.common.enums.ErrorCode;
import io.github.agentlab.common.enums.TicketAuditAction;
import io.github.agentlab.common.enums.TicketPriority;
import io.github.agentlab.common.enums.TicketStatus;
import io.github.agentlab.common.exception.BusinessException;
import io.github.agentlab.dao.entity.TicketEntity;
import io.github.agentlab.dao.lock.DistributedLock;
import io.github.agentlab.dao.redis.TicketDraftStore;
import io.github.agentlab.dao.repository.TicketAuditLogRepository;
import io.github.agentlab.dao.repository.TicketRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private TicketDraftStore draftStore;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketAuditLogRepository auditLogRepository;
    @Mock
    private TicketPersistenceService ticketPersistenceService;
    @Mock
    private OrderTool orderTool;
    @Mock
    private DistributedLock distributedLock;

    @InjectMocks
    private TicketService ticketService;

    @BeforeEach
    void setUpTrace() {
        MDC.put(TraceContext.TRACE_ID, "test-trace");
    }

    @AfterEach
    void tearDownTrace() {
        MDC.remove(TraceContext.TRACE_ID);
    }

    @Test
    void createDraft_persistsNewDraftAndWritesAudit() {
        stubLockPassthrough();
        when(draftStore.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(draftStore.saveIfAbsent(any())).thenReturn(true);

        TicketDraft draft = ticketService.createDraft(
                "ORD-20260911-0001", "商品破损", TicketPriority.HIGH);

        assertThat(draft.confirmToken()).isNotBlank();
        assertThat(draft.orderNo()).isEqualTo("ORD-20260911-0001");
        verify(auditLogRepository).save(any());
    }

    @Test
    void createDraft_returnsExistingDraftWithoutAudit() {
        stubLockPassthrough();
        TicketDraft existing = sampleDraft("key-1", "token-1");
        when(draftStore.findByIdempotencyKey(any())).thenReturn(Optional.of(existing));

        TicketDraft draft = ticketService.createDraft(
                "ORD-20260911-0001", "商品破损", TicketPriority.NORMAL);

        assertThat(draft).isEqualTo(existing);
        verify(auditLogRepository, never()).save(any());
        verify(draftStore, never()).saveIfAbsent(any());
    }

    @Test
    void confirm_createsTicketAndClearsDraft() {
        stubLockPassthrough();
        TicketDraft draft = sampleDraft("idem-1", "confirm-token");
        when(draftStore.findTicketIdByConfirmToken("confirm-token")).thenReturn(Optional.empty());
        when(draftStore.findByToken("confirm-token")).thenReturn(Optional.of(draft));
        when(draftStore.findConfirmedTicketId("idem-1")).thenReturn(Optional.empty());
        when(orderTool.queryOrderStatus("ORD-20260911-0001")).thenReturn(
                new OrderTool.OrderQueryResult(true, "ORD-20260911-0001", "已发货", "ok", null, null));

        TicketEntity entity = TicketEntity.builder()
                .ticketId("T-1")
                .orderNo("ORD-20260911-0001")
                .reason("商品破损")
                .priority(TicketPriority.HIGH)
                .status(TicketStatus.CREATED)
                .idempotencyKey("idem-1")
                .traceId("test-trace")
                .createdAt(Instant.now())
                .build();
        when(ticketPersistenceService.persistConfirmedTicket(draft, "test-trace")).thenReturn(entity);

        Ticket ticket = ticketService.confirm("confirm-token");

        assertThat(ticket.ticketId()).isEqualTo("T-1");
        verify(draftStore).markConfirmed("idem-1", "confirm-token", "T-1");
        verify(draftStore).remove("confirm-token");
    }

    @Test
    void confirm_rejectsExpiredDraft() {
        TicketDraft expired = new TicketDraft(
                "d1", "tok", "idem", "ORD-20260911-0001", "reason",
                TicketPriority.NORMAL, Instant.now().minusSeconds(30));
        when(draftStore.findTicketIdByConfirmToken("tok")).thenReturn(Optional.empty());
        when(draftStore.findByToken("tok")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> ticketService.confirm("tok"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TICKET_DRAFT_EXPIRED);
    }

    @Test
    void confirm_rejectsOrderNotInAfterSalesWindow() {
        stubLockPassthrough();
        TicketDraft draft = new TicketDraft(
                "draft-2", "tok-2", "idem-2", "ORD-20260911-0002", "退货",
                TicketPriority.NORMAL, Instant.now().plus(Duration.ofMinutes(5)));
        when(draftStore.findTicketIdByConfirmToken("tok-2")).thenReturn(Optional.empty());
        when(draftStore.findByToken("tok-2")).thenReturn(Optional.of(draft));
        when(draftStore.findConfirmedTicketId("idem-2")).thenReturn(Optional.empty());
        when(orderTool.queryOrderStatus("ORD-20260911-0002")).thenReturn(
                new OrderTool.OrderQueryResult(true, "ORD-20260911-0002", "待发货", "waiting", null, null));

        assertThatThrownBy(() -> ticketService.confirm("tok-2"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_IN_AFTER_SALES);
    }

    @Test
    void confirm_idempotentByConfirmTokenAfterSuccess() {
        when(draftStore.findTicketIdByConfirmToken("tok-done")).thenReturn(Optional.of("T-done"));
        TicketEntity entity = TicketEntity.builder()
                .ticketId("T-done")
                .orderNo("ORD-20260911-0003")
                .reason("r")
                .priority(TicketPriority.LOW)
                .status(TicketStatus.CREATED)
                .idempotencyKey("k")
                .createdAt(Instant.now())
                .build();
        when(ticketRepository.findById("T-done")).thenReturn(Optional.of(entity));

        Ticket ticket = ticketService.confirm("tok-done");

        assertThat(ticket.ticketId()).isEqualTo("T-done");
        verify(distributedLock, never()).executeWithLock(any(), any(), any(), any());
    }

    private void stubLockPassthrough() {
        when(distributedLock.executeWithLock(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> action = invocation.getArgument(3);
                    return action.get();
                });
    }

    private static TicketDraft sampleDraft(String idempotencyKey, String confirmToken) {
        return new TicketDraft(
                "draft-1",
                confirmToken,
                idempotencyKey,
                "ORD-20260911-0001",
                "商品破损",
                TicketPriority.HIGH,
                Instant.now().plus(Duration.ofMinutes(5))
        );
    }
}
