package io.github.agentlab.businesstools.service;

import com.alibaba.fastjson.JSON;
import io.github.agentlab.businesstools.tool.OrderTool;
import io.github.agentlab.common.Sha256Util;
import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.common.dto.Ticket;
import io.github.agentlab.common.dto.TicketDraft;
import io.github.agentlab.common.enums.ErrorCode;
import io.github.agentlab.common.enums.TicketAuditAction;
import io.github.agentlab.common.enums.TicketPriority;
import io.github.agentlab.common.exception.BusinessException;
import io.github.agentlab.dao.entity.TicketAuditLogEntity;
import io.github.agentlab.dao.entity.TicketEntity;
import io.github.agentlab.dao.lock.DistributedLock;
import io.github.agentlab.dao.lock.LockAcquireException;
import io.github.agentlab.dao.redis.TicketDraftStore;
import io.github.agentlab.dao.repository.TicketAuditLogRepository;
import io.github.agentlab.dao.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 工单核心服务（任务 04：售后工单创建）。
 *
 * <h2>这关要解决什么</h2>
 * <p>前几关的工具全是只读查询，错了最多返回错答案。这关第一次引入"写操作"。
 * 一旦模型能触发创建工单，三个只在写场景才存在的问题立刻冒出来：<b>幂等、人审、审计</b>。
 * 本服务就是这三个问题在代码层面的落地。</p>
 *
 * <h2>场景意义</h2>
 * <p>用户咨询"ORD-xxx 商品破损要退货"，模型不直接落库，只生成草稿 + confirmToken；
 * 用户/客服确认后才真正落库。这是"模型只提议、后端执行写操作"边界的标准落地，
 * 也是企业 Agent 区别于 Demo 的核心。</p>
 *
 * <h2>模块归属说明</h2>
 * <p>本服务和 {@link io.github.agentlab.businesstools.tool.TicketCreateTool} 都放在 business-tools，
 * 因为"工单创建"是一项完整业务能力（工具入口 + 其幂等/人审/审计服务）。
 * 任务 10 做跨工具状态机时，会把跨工具编排抽到 agent-orchestrator。</p>
 *
 * <h2>关键工程难点</h2>
 * <ul>
 *     <li><b>幂等双层</b>：Redis SETNX + DB {@code idempotency_key} 唯一约束。</li>
 *     <li><b>分布式锁</b>：confirm 临界区加锁；锁是优化，DB 唯一约束才是最终兜底。</li>
 *     <li><b>事务边界</b>：DB 写入在 {@link TicketPersistenceService}；Redis 清理在事务提交之后。</li>
 *     <li><b>confirmToken 重试</b>：确认成功后保留 token→ticketId 映射，支持 HTTP 幂等重试。</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TicketService {

    private final TicketDraftStore draftStore;
    private final TicketRepository ticketRepository;
    private final TicketAuditLogRepository auditLogRepository;
    private final TicketPersistenceService ticketPersistenceService;
    private final OrderTool orderTool;
    private final DistributedLock distributedLock;

    /**
     * 草稿 TTL，与 {@link io.github.agentlab.dao.redis.RedisTicketDraftStore} 保持一致。
     */
    public static final Duration DRAFT_TTL = Duration.ofMinutes(10);
    /**
     * confirm 加锁最长等待；超时视为「处理中」。
     */
    public static final Duration LOCK_WAIT = Duration.ofSeconds(3);
    /**
     * 锁键前缀，createDraft / confirm 共用同一业务键空间，避免同一意图并发分叉。
     */
    public static final String LOCK_KEY_PREFIX = "ticket:confirm:";

    /**
     * 允许发起售后的订单状态（已发货/已签收；待发货视为尚未进入售后窗口）。
     */
    private static final String ORDER_STATUS_SHIPPED = "已发货";
    private static final String ORDER_STATUS_SIGNED = "已签收";

    /**
     * 生成工单草稿（模型/用户提议，不落库）。
     *
     * <p>同一 {@code orderNo + reason} 生成固定幂等键；重复调用返回同一草稿与 confirmToken，
     * 并写 DRAFT_CREATED 审计（仅首次创建时）。</p>
     */
    public TicketDraft createDraft(String orderNo, String reason, TicketPriority priority) {
        String idempotencyKey = Sha256Util.sha256(orderNo + reason);
        String traceId = currentTraceId();

        try {
            return distributedLock.executeWithLock(
                    LOCK_KEY_PREFIX + idempotencyKey,
                    LOCK_WAIT,
                    Duration.ZERO,
                    () -> createDraftUnderLock(idempotencyKey, orderNo, reason, priority, traceId));
        } catch (LockAcquireException ex) {
            log.warn("创建草稿获取锁失败: traceId={}, idempotencyKey={}", traceId, idempotencyKey);
            throw new BusinessException(ErrorCode.TICKET_CONFIRM_IN_PROGRESS);
        }
    }

    /**
     * 确认草稿并落库。模型与用户 API 的写操作边界：仅后端 confirm 可触发本方法。
     */
    public Ticket confirm(String confirmToken) {
        String traceId = currentTraceId();
        if (confirmToken == null || confirmToken.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "confirmToken must not be blank");
        }
        String normalizedToken = confirmToken.trim();

        // 已确认过的 HTTP 重试：草稿已删，靠 token→ticketId 映射直接返回
        Optional<String> ticketIdByToken = draftStore.findTicketIdByConfirmToken(normalizedToken);
        if (ticketIdByToken.isPresent()) {
            Ticket ticket = loadTicketById(ticketIdByToken.get());
            log.info("工单确认幂等返回(按 token): traceId={}, confirmToken={}, ticketId={}",
                    traceId, normalizedToken, ticket.ticketId());
            return ticket;
        }

        TicketDraft draft = draftStore.findByToken(normalizedToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_DRAFT_NOT_FOUND));

        if (draft.expireAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.TICKET_DRAFT_EXPIRED);
        }

        String idempotencyKey = draft.idempotencyKey();
        try {
            return distributedLock.executeWithLock(
                    LOCK_KEY_PREFIX + idempotencyKey,
                    LOCK_WAIT,
                    Duration.ZERO,
                    () -> confirmUnderLock(draft, normalizedToken, idempotencyKey, traceId));
        } catch (LockAcquireException ex) {
            log.warn("确认工单获取锁失败: traceId={}, confirmToken={}, idempotencyKey={}",
                    traceId, normalizedToken, idempotencyKey);
            throw new BusinessException(ErrorCode.TICKET_CONFIRM_IN_PROGRESS);
        }
    }

    private TicketDraft createDraftUnderLock(String idempotencyKey, String orderNo, String reason,
                                             TicketPriority priority, String traceId) {
        Optional<TicketDraft> existing = draftStore.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            TicketDraft draft = existing.get();
            log.info("草稿幂等返回: traceId={}, idempotencyKey={}, draftId={}, confirmToken={}",
                    traceId, idempotencyKey, draft.draftId(), draft.confirmToken());
            return draft;
        }

        TicketDraft draft = buildDraft(idempotencyKey, orderNo, reason, priority, Instant.now().plus(DRAFT_TTL));
        boolean created = draftStore.saveIfAbsent(draft);
        if (!created) {
            TicketDraft concurrentDraft = draftStore.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_CREATE_FAILED));
            log.info("草稿并发写入已由其他请求完成: traceId={}, idempotencyKey={}, draftId={}",
                    traceId, idempotencyKey, concurrentDraft.draftId());
            return concurrentDraft;
        }

        auditLogRepository.save(buildAudit(
                TicketAuditAction.DRAFT_CREATED,
                draft.draftId(),
                traceId,
                JSON.toJSONString(draft)));
        log.info("草稿创建成功: traceId={}, idempotencyKey={}, draftId={}, confirmToken={}, orderNo={}, 过期时间={}",
                traceId, idempotencyKey, draft.draftId(), draft.confirmToken(), orderNo, draft.expireAt());
        return draft;
    }

    private Ticket confirmUnderLock(TicketDraft draft, String confirmToken, String idempotencyKey, String traceId) {
        Optional<String> confirmedTicketId = draftStore.findConfirmedTicketId(idempotencyKey);
        if (confirmedTicketId.isPresent()) {
            Ticket ticket = loadTicketById(confirmedTicketId.get());
            log.info("工单确认幂等返回(按 idempotencyKey): traceId={}, idempotencyKey={}, ticketId={}",
                    traceId, idempotencyKey, ticket.ticketId());
            return ticket;
        }

        validateOrderEligibleForAfterSales(draft.orderNo(), traceId);

        // 事务在此方法返回后提交；Redis 清理必须在提交之后执行
        TicketEntity saved = ticketPersistenceService.persistConfirmedTicket(draft, traceId);
        Ticket ticket = toDto(saved);

        draftStore.markConfirmed(idempotencyKey, confirmToken, saved.getTicketId());
        draftStore.remove(confirmToken);

        log.info("工单确认成功: traceId={}, confirmToken={}, ticketId={}, orderNo={}, idempotencyKey={}",
                traceId, confirmToken, saved.getTicketId(), saved.getOrderNo(), idempotencyKey);
        return ticket;
    }

    /**
     * 写操作前的订单验真：存在性 + 是否进入售后窗口（演示规则：待发货不可建售后单）。
     */
    private void validateOrderEligibleForAfterSales(String orderNo, String traceId) {
        OrderTool.OrderQueryResult order = orderTool.queryOrderStatus(orderNo);
        if (!order.success()) {
            log.warn("工单确认订单不存在: traceId={}, orderNo={}, errorCode={}",
                    traceId, orderNo, order.errorCode());
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        String status = order.status();
        if (!ORDER_STATUS_SHIPPED.equals(status) && !ORDER_STATUS_SIGNED.equals(status)) {
            log.warn("工单确认订单不在售后期: traceId={}, orderNo={}, status={}", traceId, orderNo, status);
            throw new BusinessException(ErrorCode.ORDER_NOT_IN_AFTER_SALES);
        }
    }

    private Ticket loadTicketById(String ticketId) {
        return ticketRepository.findById(ticketId)
                .map(TicketService::toDto)
                .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_CREATE_FAILED));
    }

    static TicketDraft buildDraft(String idempotencyKey, String orderNo, String reason,
                                  TicketPriority priority, Instant expireAt) {
        return new TicketDraft(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", ""),
                idempotencyKey,
                orderNo,
                reason,
                priority,
                expireAt
        );
    }

    static Ticket toDto(TicketEntity e) {
        return new Ticket(e.getTicketId(), e.getOrderNo(), e.getReason(),
                e.getPriority(), e.getStatus(), e.getCreatedAt());
    }

    static TicketAuditLogEntity buildAudit(TicketAuditAction action, String ticketId,
                                           String traceId, String payloadJson) {
        return TicketAuditLogEntity.builder()
                .action(action)
                .ticketId(ticketId)
                .operator(null)
                .traceId(traceId)
                .payload(payloadJson)
                .occurredAt(Instant.now())
                .build();
    }

    static String currentTraceId() {
        return TraceContext.getTraceId();
    }
}
