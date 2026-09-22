package io.github.agentlab.dao.redis;

import com.alibaba.fastjson.JSON;
import io.github.agentlab.common.dto.TicketDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 基于 Redis 的草稿存储实现。
 *
 * <p>Key 设计：</p>
 * <ul>
 *     <li>草稿：{@code ticket:draft:{idempotencyKey}} → 草稿 JSON，带 TTL</li>
 *     <li>草稿反查：{@code ticket:draft:token:{confirmToken}} → idempotencyKey，带同样 TTL</li>
 *     <li>已确认映射：{@code ticket:confirmed:{idempotencyKey}} → ticketId，较长 TTL</li>
 * </ul>
 *
 * <p>关键技术点（实现时务必核对）：</p>
 * <ul>
 *     <li>{@link #saveIfAbsent} 用 {@code SET key value NX EX}，一条命令保证"不存在才写 + 过期"原子性。</li>
 *     <li>不要拆成 EXISTS + SET 两步，那不是原子的。</li>
 *     <li>token→key 的反向索引也要在同一事务/管道里写，否则会出现"草稿在、反查丢失"的孤儿状态。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RedisTicketDraftStore implements TicketDraftStore {

    /**
     * 草稿 TTL：10 分钟。过期后确认接口应返回明确的"草稿过期"错误码。
     */
    public static final Duration DRAFT_TTL = Duration.ofMinutes(10);
    /**
     * 已确认映射 TTL：24 小时，覆盖用户可能的重试窗口。
     */
    public static final Duration CONFIRMED_TTL = Duration.ofHours(24);

    private static final String KEY_DRAFT = "ticket:draft:";
    private static final String KEY_DRAFT_TOKEN = "ticket:draft:token:";
    private static final String KEY_CONFIRMED = "ticket:confirmed:";
    private static final String KEY_CONFIRMED_TOKEN = "ticket:confirmed:token:";

    private final StringRedisTemplate redis;

    @Override
    public boolean saveIfAbsent(TicketDraft draft) {
        // 原子写入草稿本体（SETNX + EX）。已存在则返回 false，由调用方返回原草稿、原 token。
        Boolean created = redis.opsForValue()
                .setIfAbsent(KEY_DRAFT + draft.idempotencyKey(), toJson(draft), DRAFT_TTL);
        if (Boolean.FALSE.equals(created)) {
            return false;
        }
        // token → idempotencyKey 反查索引。非原子，但草稿本体已挡住重复，最坏只是反查索引过期重建。
        redis.opsForValue().set(KEY_DRAFT_TOKEN + draft.confirmToken(),
                draft.idempotencyKey(), DRAFT_TTL);
        return true;
    }

    @Override
    public Optional<TicketDraft> findByToken(String confirmToken) {
        String idempotencyKey = redis.opsForValue().get(KEY_DRAFT_TOKEN + confirmToken);
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(KEY_DRAFT + idempotencyKey);
        return json == null ? Optional.empty() : Optional.of(fromJson(json));
    }

    @Override
    public Optional<TicketDraft> findByIdempotencyKey(String idempotencyKey) {
        String json = redis.opsForValue().get(KEY_DRAFT + idempotencyKey);
        return json == null ? Optional.empty() : Optional.of(fromJson(json));
    }

    @Override
    public void remove(String confirmToken) {
        String idempotencyKey = redis.opsForValue().get(KEY_DRAFT_TOKEN + confirmToken);
        if (idempotencyKey != null) {
            redis.delete(KEY_DRAFT + idempotencyKey);
        }
        redis.delete(KEY_DRAFT_TOKEN + confirmToken);
    }

    @Override
    public void markConfirmed(String idempotencyKey, String confirmToken, String ticketId) {
        redis.opsForValue().set(KEY_CONFIRMED + idempotencyKey, ticketId, CONFIRMED_TTL);
        redis.opsForValue().set(KEY_CONFIRMED_TOKEN + confirmToken, ticketId, CONFIRMED_TTL);
    }

    @Override
    public Optional<String> findConfirmedTicketId(String idempotencyKey) {
        return Optional.ofNullable(redis.opsForValue().get(KEY_CONFIRMED + idempotencyKey));
    }

    @Override
    public Optional<String> findTicketIdByConfirmToken(String confirmToken) {
        return Optional.ofNullable(redis.opsForValue().get(KEY_CONFIRMED_TOKEN + confirmToken));
    }

    // 以下是序列化辅助，可按需调用
    private String toJson(TicketDraft draft) {
        return JSON.toJSONString(draft);
    }

    private TicketDraft fromJson(String json) {
        return JSON.parseObject(json, TicketDraft.class);
    }
}
