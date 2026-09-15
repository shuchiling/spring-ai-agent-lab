package io.github.agentlab.dao.redis;

import io.github.agentlab.common.dto.TicketDraft;

import java.util.Optional;

/**
 * 工单草稿临时存储（Redis 实现）。
 *
 * <p>为什么用 Redis 而不是写进 Postgres？</p>
 * <ul>
 *     <li>草稿是短生命周期、未确认的中间态，生命周期通常几分钟，不应该污染业务表。</li>
 *     <li>Redis 原生支持 TTL，过期自动失效，不必写定时清理任务。</li>
 *     <li>草稿读写频繁、对延迟敏感，Redis 比 Postgres 更合适。</li>
 * </ul>
 *
 * <p>幂等语义：{@link #saveIfAbsent(TicketDraft)} 必须是原子的"不存在才写入"，
 * 即 Redis 的 {@code SETNX} 语义。这一点是整个幂等设计的第一道闸门，实现时务必确认
 * 用的是 {@code SET ... NX EX} 而不是先 GET 再 SET，否则并发下会出现两个不同 token 的草稿。</p>
 */
public interface TicketDraftStore {

    /**
     * 原子地保存草稿：仅当该 idempotencyKey 对应的草稿不存在时才写入。
     *
     * @return true 表示本次写入成功；false 表示已存在（重复请求），调用方应返回原草稿、原 token
     */
    boolean saveIfAbsent(TicketDraft draft);

    /**
     * 按 confirmToken 取草稿。草稿过期或不存在时返回 empty。
     */
    Optional<TicketDraft> findByToken(String confirmToken);

    /**
     * 删除草稿。确认成功后调用，避免 token 被二次消费。
     */
    void remove(String confirmToken);

    /**
     * 记录"已确认"映射：idempotencyKey → ticketId。
     * 用于二次确认同一 token 时的幂等返回（草稿已删，但业务上要返回同一个工单）。
     */
    void markConfirmed(String idempotencyKey, String ticketId);

    /**
     * 查询某个 idempotencyKey 是否已经确认过。
     */
    Optional<String> findConfirmedTicketId(String idempotencyKey);
}
