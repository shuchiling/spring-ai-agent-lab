package io.github.agentlab.app.handler;

import io.github.agentlab.common.dto.ApiResponse;
import io.github.agentlab.common.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> exceptionHandler(IllegalArgumentException e) {
        log.warn("illegal argument: {}", e.getMessage());
        return ApiResponse.fail(ErrorCode.ILLEGAL_ARGUMENT.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> exceptionHandler(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse(ErrorCode.BAD_REQUEST.getMessage());
        log.warn("request validation failed: {}", message);
        return ApiResponse.fail(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> exceptionHandler(Exception e) {
        log.error("unexpected exception", e);
        return ApiResponse.fail(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage());
    }
}
