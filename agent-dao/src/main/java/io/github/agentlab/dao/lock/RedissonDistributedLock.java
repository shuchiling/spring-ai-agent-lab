package io.github.agentlab.dao.lock;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于 Redisson 的分布式锁实现。
 *
 * <p>实现要点（review 会盯）：</p>
 * <ul>
 *     <li>leaseTime 为 0/null 时传 -1 给 Redisson，启用 watchdog 自动续期。</li>
 *     <li>解锁前用 {@code isHeldByCurrentThread()} 判断，避免解锁到别人的锁（锁过期被别人抢走后）。</li>
 *     <li>获取不到锁抛 {@link LockAcquireException}，由调用方决定语义（重试 or 返回"处理中"）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RedissonDistributedLock implements DistributedLock {

    private final RedissonClient redissonClient;

    @Override
    public <T> T executeWithLock(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        long waitMillis = waitTime.toMillis();
        long leaseMillis = (leaseTime == null || leaseTime.isZero()) ? -1 : leaseTime.toMillis();
        boolean acquired;
        try {
            acquired = lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquireException("获取分布式锁被中断: " + lockKey, e);
        }
        if (!acquired) {
            throw new LockAcquireException("获取分布式锁失败（被占用）: " + lockKey);
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
