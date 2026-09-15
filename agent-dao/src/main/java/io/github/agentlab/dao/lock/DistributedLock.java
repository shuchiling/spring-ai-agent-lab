package io.github.agentlab.dao.lock;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 分布式锁抽象。
 *
 * <p>为什么用分布式锁而不是 JVM 内的 synchronized / ReentrantLock？</p>
 * <ul>
 *     <li>Agent 应用未来会多实例部署，同一 idempotencyKey 的并发请求可能落到不同机器，
 *         JVM 锁跨不了进程。</li>
 *     <li>敏感写操作（创建工单）的并发防护必须在共享存储层做，Redis 是最轻量的选择。</li>
 * </ul>
 *
 * <p>为什么用 Redisson 而不是手写 SETNX 锁？</p>
 * <ul>
 *     <li>Redisson RLock 自带看门狗（watchdog）续期：leaseTime 传 -1 时，锁会自动续期，
 *         避免业务执行慢导致锁提前释放、被别人抢锁。手写 SETNX 要自己处理续期和释放，容易踩坑。</li>
 *     <li>可重入、支持公平锁、释放失败有明确语义。</li>
 * </ul>
 *
 * <p><b>重要边界</b>：分布式锁只是"尽量挡"，不是"必须挡"的最后一道墙。
 * 写操作兜底永远是 DB 唯一约束。锁 + 唯一约束两层都要在，缺 DB 约束就是把安全押在锁的可用性上——不可靠。</p>
 */
public interface DistributedLock {

    /**
     * 在分布式锁保护下执行 action。
     *
     * @param lockKey    锁键，建议带业务前缀，如 {@code ticket:confirm:}{idempotencyKey}
     * @param waitTime   最多等待获取锁的时间；超时未获取到 → 抛 {@code LockAcquireException}
     * @param leaseTime  锁持有时间。传 {@link Duration#ZERO} 或 null 表示用看门狗自动续期（推荐）
     * @param action     临界区逻辑
     * @return action 的返回值
     */
    <T> T executeWithLock(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action);
}
