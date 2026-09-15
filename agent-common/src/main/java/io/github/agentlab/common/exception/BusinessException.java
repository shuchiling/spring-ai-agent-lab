package io.github.agentlab.common.exception;

import io.github.agentlab.common.enums.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。Service 层用它携带明确的 {@link ErrorCode}，
 * 由全局异常处理器统一翻译成 {@code ApiResponse.fail}。
 *
 * <p>设计意图：把"业务校验失败"（草稿过期、订单不存在、订单不在售后期…）
 * 和"系统异常"（空指针、DB 连不上）区分开。前者是预期内、有明确错误码、走 warn 日志；
 * 后者是预期外、走 error 日志。这样可观测性和告警都能区分。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
