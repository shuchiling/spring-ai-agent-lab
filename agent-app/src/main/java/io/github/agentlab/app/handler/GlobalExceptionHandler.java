package io.github.agentlab.app.handler;

import io.github.agentlab.common.dto.ApiResponse;
import io.github.agentlab.common.enums.ErrorCode;
import io.github.agentlab.common.context.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> exceptionHandler(IllegalArgumentException e) {
        log.warn("请求参数非法: traceId={}, 错误信息={}", TraceContext.getTraceId(), e.getMessage());
        return ApiResponse.fail(ErrorCode.ILLEGAL_ARGUMENT.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> exceptionHandler(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse(ErrorCode.BAD_REQUEST.getMessage());
        log.warn("请求参数校验失败: traceId={}, 错误信息={}", TraceContext.getTraceId(), message);
        return ApiResponse.fail(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> exceptionHandler(Exception e) {
        log.error("系统异常: traceId={}, 错误类型={}, 错误信息={}",
                TraceContext.getTraceId(), e.getClass().getSimpleName(), e.getMessage(), e);
        return ApiResponse.fail(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage());
    }
}
