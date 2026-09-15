package io.github.agentlab.dao.lock;

/**
 * 获取分布式锁失败时抛出（基础设施层异常）。
 *
 * <p>它是基础设施层的异常，不带业务 ErrorCode。Service 层应捕获它并翻译成
 * {@code BusinessException(ErrorCode.TICKET_CONFIRM_IN_PROGRESS)} 之类——
 * "基础设施异常 → 业务异常"的翻译发生在服务边界，是分层的好习惯。</p>
 */
public class LockAcquireException extends RuntimeException {

    public LockAcquireException(String message) {
        super(message);
    }

    public LockAcquireException(String message, Throwable cause) {
        super(message, cause);
    }
}
