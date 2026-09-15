package io.github.agentlab.businesstools.service;

import io.github.agentlab.businesstools.tool.OrderTool;
import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.common.dto.Ticket;
import io.github.agentlab.common.dto.TicketDraft;
import io.github.agentlab.common.enums.TicketAuditAction;
import io.github.agentlab.common.enums.TicketPriority;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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
 * <h2>关键工程难点（实现时务必想通，review 会盯这几条）</h2>
 * <ul>
 *     <li><b>幂等双层</b>：Redis SETNX 挡住绝大多数重复请求；DB 唯一约束(idempotency_key)
 *     挡住 Redis 没生效的极端并发。两层都要有，缺任一层都有漏洞。</li>
 *     <li><b>分布式锁</b>：confirm 用 Redisson 锁住 idempotencyKey，把"幂等回查 + 落库"压在临界区里，
 *     减少击穿 DB 唯一约束的次数。但锁只是优化、不是安全边界——DB 唯一约束不能拆。</li>
 *     <li><b>事务边界</b>：confirm 里"写工单 + 写审计日志"必须在同一 @Transactional；
 *     但 Redis 操作（删草稿、标记已确认）必须在事务提交之后，否则事务回滚后 Redis 已脏。</li>
 *     <li><b>并发击穿兜底</b>：撞 DB 唯一约束时，catch 异常，回查已存在的工单返回，
 *     不能直接抛 500 给用户。</li>
 *     <li><b>草稿过期</b>：TTL 到了 Redis 自动失效；过期后 confirm 要返回明确错误码，不能静默创建。</li>
 *     <li><b>订单前置校验</b>：confirm 时调 OrderTool 校验订单真实存在 + 在售后期内。
 *     这个校验收在 confirm 而不是 createDraft，想想为什么（提示：草稿阶段是模型提议，
 *     真正写之前才需要做"业务前置校验"这道关卡）。</li>
 * </ul>
 *
 * <h2>你需要实现的方法</h2>
 * <ul>
 *     <li>{@link #createDraft}：生成草稿 + confirmToken，存 Redis，写 DRAFT_CREATED 审计。</li>
 *     <li>{@link #confirm}：消费 token → 幂等回查 → 订单校验 → 事务内落库 → 事务后清 Redis。</li>
 * </ul>
 * 方法体已留空，按 Javadoc 的语义要求实现。拿不准的先按自己理解写。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TicketService {

    private final TicketDraftStore draftStore;
    private final TicketRepository ticketRepository;
    private final TicketAuditLogRepository auditLogRepository;
    private final OrderTool orderTool;
    private final DistributedLock distributedLock;

    /** 草稿 TTL，与 draftStore 保持一致。 */
    public static final java.time.Duration DRAFT_TTL = java.time.Duration.ofMinutes(10);
    /** confirm 加锁等待时间：拿不到锁说明同一草稿正在被确认，返回"处理中"。 */
    public static final Duration LOCK_WAIT = Duration.ofSeconds(3);
    /** 锁键前缀。 */
    public static final String LOCK_KEY_PREFIX = "ticket:confirm:";

    /**
     * 生成工单草稿（模型/用户提议创建工单，但不落库）。
     *
     * <p><b>场景意义</b>：模型调用 TicketCreateTool.createTicketDraft 时最终落到这里。
     * 这一步只做"提议"——把用户给的 orderNo/reason/priority 暂存进 Redis，
     * 返回一个 confirmToken，等用户二次确认。不碰 Postgres。</p>
     *
     * <p><b>实现要求</b>：</p>
     * <ol>
     *     <li>生成 idempotencyKey = sha256(orderNo + reason)，同一意图幂等。</li>
     *     <li>调用 {@link TicketDraftStore#saveIfAbsent}：返回 false 说明已存在，
     *         应返回原草稿、原 token（幂等），不要生成新 token。</li>
     *     <li>草稿过期时间 = 当前时间 + {@link #DRAFT_TTL}。</li>
     *     <li>写一条 DRAFT_CREATED 审计日志，payload 存草稿快照 JSON。</li>
     *     <li>日志带 traceId。</li>
     * </ol>
     *
     * <p><b>注意</b>：草稿阶段不要校验订单是否真实存在（那是 confirm 的事），
     * 这里只做格式校验。想想为什么这样分层。</p>
     *
     * @return 草稿，包含 confirmToken
     */
    public TicketDraft createDraft(String orderNo, String reason, TicketPriority priority) {
        // TODO 任务04（核心）：实现草稿生成 + 幂等 + 审计
        throw new UnsupportedOperationException("TODO 任务04: createDraft 由你实现");
    }

    /**
     * 确认草稿并真正创建工单（落库）。
     *
     * <p><b>场景意义</b>：用户/客服拿着 confirmToken 调这个方法，才真正把工单写进 Postgres。
     * 这是"后端主导的执行"动作，模型永远碰不到这步。</p>
     *
     * <p><b>实现要求</b>（按顺序）：</p>
     * <ol>
     *     <li>从 draftStore.findByToken 取草稿；不存在/过期 → 抛
     *         {@code BusinessException(TICKET_DRAFT_NOT_FOUND)} 或 {@code TICKET_DRAFT_EXPIRED}。
     *         取到草稿后拿到 {@code idempotencyKey}，作为后续加锁和幂等回查的键。</li>
     *     <li><b>分布式锁</b>：用 {@link DistributedLock#executeWithLock} 对
     *         {@code "ticket:confirm:" + idempotencyKey} 上锁（waitTime 短，如 3s；leaseTime 传
     *         {@link Duration#ZERO} 走看门狗续期）。锁内执行 3-6 步。获取锁失败时 catch
     *         {@link LockAcquireException} → 抛 {@code BusinessException(TICKET_CONFIRM_IN_PROGRESS)}。
     *         这一层是"尽量挡"——真正兜底仍是 DB 唯一约束，两层都要在。</li>
     *     <li><b>幂等回查</b>（锁内）：draftStore.findConfirmedTicketId(idempotencyKey) 命中，
     *         说明同一 token 已确认过，直接返回已存在的 Ticket（不创建第二个）。</li>
     *     <li><b>订单前置校验</b>（锁内）：调 {@link OrderTool#queryOrderStatus(String)}。
     *         订单不存在 → {@code ORDER_NOT_FOUND}。这一步体现"写之前先验真"。</li>
     *     <li><b>事务内</b>（@Transactional）：
     *         <ul>
     *           <li>构造 TicketEntity，status=CREATED，ticketId=UUID，idempotencyKey，traceId。</li>
     *           <li>save 到 ticketRepository。撞唯一约束 → catch，回查
     *               {@link TicketRepository#findByIdempotencyKey} 返回已存在工单。</li>
     *           <li>写 TICKET_CONFIRMED 审计日志，payload 存工单快照。</li>
     *         </ul>
     *     </li>
     *     <li><b>事务提交后</b>（注意不是事务内）：draftStore.markConfirmed + draftStore.remove。
     *         想清楚为什么 Redis 操作要放在事务外——回滚了却删了草稿/标记了已确认，会脏。</li>
     * </ol>
     *
     * <p><b>注意</b>：把 @Transactional 限定在"DB 写"这一段，Redis 清理在事务方法返回之后做。
     * 一种推荐做法是把事务方法（如 persistTicket）和非事务编排分开，本方法做编排。
     * 但这关先用最简单可解释的结构，拿不准就先写一个 @Transactional 包住 DB 部分，Redis 放方法末尾——
     * review 时我会和你抠这个边界。</p>
     *
     * @return 已创建的工单
     */
    public Ticket confirm(String confirmToken) {
        // TODO 任务04（核心）：实现草稿消费 + 幂等回查 + 订单校验 + 事务落库 + 事务后清 Redis
        throw new UnsupportedOperationException("TODO 任务04: confirm 由你实现");
    }

    // ===== 以下为辅助方法，可直接用 =====

    /** 构造草稿对象。idempotencyKey/expireAt 由调用方传入。 */
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

    /** Entity → DTO 转换，避免泄漏 JPA 实体到 API 层。 */
    static Ticket toDto(TicketEntity e) {
        return new Ticket(e.getTicketId(), e.getOrderNo(), e.getReason(),
                e.getPriority(), e.getStatus(), e.getCreatedAt());
    }

    /** 构造审计日志实体，payload 由调用方传入 JSON 字符串。 */
    static TicketAuditLogEntity buildAudit(TicketAuditAction action, String ticketId,
                                           String traceId, String payloadJson) {
        return TicketAuditLogEntity.builder()
                .action(action)
                .ticketId(ticketId)
                .operator(null) // 任务 12 做权限时填
                .traceId(traceId)
                .payload(payloadJson)
                .occurredAt(Instant.now())
                .build();
    }

    /** 当前 traceId，便于在方法里直接取。 */
    static String currentTraceId() {
        return TraceContext.getTraceId();
    }
}
