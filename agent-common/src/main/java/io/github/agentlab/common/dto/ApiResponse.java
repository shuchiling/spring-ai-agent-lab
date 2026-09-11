package io.github.agentlab.common.dto;

import io.github.agentlab.common.context.TraceContext;

public record ApiResponse<T>(
        boolean success,
        T data,
        String errorCode,
        String errorMessage,
        String traceId
) {
    public static <T> ApiResponse<T> success(T data) {
        return success(TraceContext.getTraceId(), data);
    }

    public static <T> ApiResponse<T> success(String traceId, T data) {
        return new ApiResponse<>(true, data, null, null, traceId);
    }

    public static <T> ApiResponse<T> fail(String errorCode, String errorMessage) {
        return fail(TraceContext.getTraceId(), errorCode, errorMessage);
    }

    public static <T> ApiResponse<T> fail(String traceId, String errorCode, String errorMessage) {
        return new ApiResponse<>(false, null, errorCode, errorMessage, traceId);
    }
}
