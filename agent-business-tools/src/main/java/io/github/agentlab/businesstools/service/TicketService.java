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

    /**
     * 锁内执行草稿创建（含四层幂等检查 + DB 降级兜底）。
     *
     * <p>幂等检查顺序（按性能与业务优先级）：</p>
     * <ol>
     *     <li><b>已确认检查（Redis 快路径）</b>：查 Redis confirmed 映射，TTL 24h。
     *         命中则说明工单已创建且在短期重试窗口内，直接拒绝。</li>
     *     <li><b>已确认检查（DB 降级兜底）</b>：Redis 映射过期时查 DB tickets 表。
     *         防止 24h+ 后 Redis 过期但 DB 工单永久存在时，重复生成草稿和审计日志。</li>
     *     <li><b>草稿存在检查</b>：Redis 主体存在 → 返回原草稿、原 confirmToken。</li>
     *     <li><b>并发写入检查</b>：SETNX 失败 → 回读草稿返回（另一实例已完成写入）。</li>
     * </ol>
     *
     * <p>性能分析：</p>
     * <ul>
     *     <li>99.9% 场景走 Redis 快路径（检查 1，~1ms）</li>
     *     <li>0.1% 场景 Redis 过期才查 DB（检查 2，~3ms，有 idempotency_key 索引）</li>
     *     <li>首次建草稿：Redis miss + DB miss，性能损失 ~2ms，可接受</li>
     * </ul>
     *
     * <p>关键设计：confirm 成功后 Redis 草稿被删除，confirmed 映射保留有限时间（24h）。
     * 本方法通过「Redis 缓存 + DB 兜底」双重检查，在 Redis TTL 过期后仍能正确拒绝重复建草稿。</p>
     */
    private TicketDraft createDraftUnderLock(String idempotencyKey, String orderNo, String reason,
                                             TicketPriority priority, String traceId) {
        // ===== 幂等检查 1A：该意图是否已确认（Redis 快路径） =====
        // confirm 成功后会写入 ticket:confirmed:{idempotencyKey} → ticketId，TTL 24h
        // 这是 99.9% 场景的快速检查，命中则立即拒绝（微秒级响应）
        Optional<String> confirmedTicketId = draftStore.findConfirmedTicketId(idempotencyKey);
        if (confirmedTicketId.isPresent()) {
            String ticketId = confirmedTicketId.get();
            log.warn("工单已创建(Redis命中)，拒绝重复建草稿: traceId={}, idempotencyKey={}, existingTicketId={}",
                    traceId, idempotencyKey, ticketId);
            // 抛业务异常，由 Tool 层转换成「工单已存在」的成功响应（带 ticketId）
            throw new BusinessException(
                    ErrorCode.TICKET_ALREADY_CREATED,
                    "该订单的售后工单已创建（工单号: " + ticketId + "），无需重复提交"
            );
        }

        // ===== 幂等检查 1B：该意图是否已确认（DB 降级兜底） =====
        // 场景：confirm 成功 24h+ 后，Redis confirmed 映射 TTL 过期自动删除，
        // 但 DB tickets 表中工单永久存在。若不加此检查，会重复生成草稿：
        //   - Redis 映射查不到 → 当成"未确认" → 生成新草稿、新 token
        //   - 用户 confirm 时 DB 唯一约束挡住 → 最终不会重复建工单
        //   - 但已浪费：多一份草稿、多一条 DRAFT_CREATED 审计、用户体验混乱
        // 通过查 DB（有 idempotency_key 索引，性能可接受），在 Redis 过期后仍能识别"已确认"
        Optional<TicketEntity> existingTicket = ticketRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTicket.isPresent()) {
            TicketEntity ticket = existingTicket.get();
            log.warn("工单已创建(DB兜底)，Redis映射已过期: traceId={}, idempotencyKey={}, existingTicketId={}, " +
                            "createdAt={}（距今 {}h+），建议适当延长 CONFIRMED_TTL 或监控此日志频率",
                    traceId, idempotencyKey, ticket.getTicketId(), ticket.getCreatedAt(),
                    Duration.between(ticket.getCreatedAt(), Instant.now()).toHours());
            // 同样抛异常，行为与 Redis 命中时一致，对调用方透明
            throw new BusinessException(
                    ErrorCode.TICKET_ALREADY_CREATED,
                    "该订单的售后工单已创建（工单号: " + ticket.getTicketId() + "），无需重复提交"
            );
        }

        // ===== 幂等检查 2：草稿主体是否存在 =====
        // 查询 ticket:draft:{idempotencyKey}，若存在则返回原草稿、原 token
        // 适用场景：模型重复调 Tool、用户多次发起同一意图但尚未确认
        Optional<TicketDraft> existing = draftStore.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            TicketDraft draft = existing.get();
            log.info("草稿幂等返回: traceId={}, idempotencyKey={}, draftId={}, confirmToken={}",
                    traceId, idempotencyKey, draft.draftId(), draft.confirmToken());
            return draft;
        }

        // ===== 首次创建：生成草稿并原子写入 Redis =====
        TicketDraft draft = buildDraft(idempotencyKey, orderNo, reason, priority, Instant.now().plus(DRAFT_TTL));
        
        // ===== 幂等检查 3：并发 SETNX 防护 =====
        // saveIfAbsent 内部执行 SET ... NX，只有首个到达者能成功
        // 若返回 false，说明另一实例在微秒级并发下已完成写入，回读草稿返回
        boolean created = draftStore.saveIfAbsent(draft);
        if (!created) {
            TicketDraft concurrentDraft = draftStore.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_CREATE_FAILED,
                            "草稿并发写入失败且回查为空，可能 Redis 异常"));
            log.info("草稿并发写入已由其他请求完成: traceId={}, idempotencyKey={}, draftId={}",
                    traceId, idempotencyKey, concurrentDraft.draftId());
            return concurrentDraft;
        }

        // ===== 写审计日志：仅首次创建时记录 =====
        // 幂等返回（检查 2/3）不重复写审计，避免一个意图刷满审计表
        auditLogRepository.save(buildAudit(
                TicketAuditAction.DRAFT_CREATED,
                draft.draftId(),
                traceId,
                JSON.toJSONString(draft)));
        
        log.info("草稿创建成功: traceId={}, idempotencyKey={}, draftId={}, confirmToken={}, orderNo={}, 过期时间={}",
                traceId, idempotencyKey, draft.draftId(), draft.confirmToken(), orderNo, draft.expireAt());
        return draft;
    }

    /**
     * 锁内执行工单确认（含幂等回查、订单验真、事务落库、事务后清理）。
     *
     * <p>执行顺序（按事务边界和数据一致性设计）：</p>
     * <ol>
     *     <li><b>幂等回查</b>：查询 Redis confirmed 映射，若已确认则直接返回已有工单。</li>
     *     <li><b>订单验真</b>：调用 OrderTool 验证订单存在且状态满足售后条件（已发货/已签收）。</li>
     *     <li><b>事务内落库</b>：调用 {@link TicketPersistenceService} 写 tickets 表和审计日志。</li>
     *     <li><b>事务提交后</b>：写 Redis confirmed 映射、删除草稿。Redis 操作必须在事务外，
     *         避免事务回滚后 Redis 已脏（如：DB 回滚了，但 Redis 已标记已确认）。</li>
     * </ol>
     *
     * <p>关键设计：{@link TicketPersistenceService} 单独标注 {@code @Transactional}，
     * 本方法不标注事务，编排逻辑（验真、清理）在事务外执行，保证 Redis 与 DB 顺序正确。</p>
     */
    private Ticket confirmUnderLock(TicketDraft draft, String confirmToken, String idempotencyKey, String traceId) {
        // ===== 幂等回查：同一 idempotencyKey 是否已确认 =====
        // 场景：并发 confirm 中，先到者已提交事务并写入映射，后到者在锁内查到映射直接返回
        // 映射 TTL 24h，覆盖合理重试窗口；超时后若 DB 中工单存在，仍能通过 idempotencyKey 查到
        Optional<String> confirmedTicketId = draftStore.findConfirmedTicketId(idempotencyKey);
        if (confirmedTicketId.isPresent()) {
            Ticket ticket = loadTicketById(confirmedTicketId.get());
            log.info("工单确认幂等返回(按 idempotencyKey): traceId={}, idempotencyKey={}, ticketId={}",
                    traceId, idempotencyKey, ticket.ticketId());
            return ticket;
        }

        // ===== 订单前置校验：写操作前的业务规则验证 =====
        // 草稿阶段（createDraft）不验订单真实性，只验格式；确认阶段才查订单系统验真
        // 规则：订单必须已发货或已签收，才可建售后单（待发货不可售后，符合业务常识）
        validateOrderEligibleForAfterSales(draft.orderNo(), traceId);

        // ===== 事务内落库：工单表 + 审计日志 =====
        // persistConfirmedTicket 内部标注 @Transactional，确保两张表原子写入
        // 若撞 DB 唯一约束（idempotency_key），catch 异常后回查已有工单返回，不抛 500
        TicketEntity saved = ticketPersistenceService.persistConfirmedTicket(draft, traceId);
        Ticket ticket = toDto(saved);

        // ===== 事务提交后：写确认映射、删除草稿 =====
        // 关键：此时 persistConfirmedTicket 的事务已提交，DB 中工单已持久化
        // 若在事务内写 Redis → 事务回滚 → Redis 已标记已确认 → 数据不一致
        // 顺序：先 DB commit，再 Redis 标记，Redis 可以丢，DB 是真相源
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
