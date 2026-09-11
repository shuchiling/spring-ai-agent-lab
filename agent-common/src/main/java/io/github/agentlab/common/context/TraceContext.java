package io.github.agentlab.common.context;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String TRACE_HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    public static String getTraceId() {
        return MDC.get(TRACE_ID);
    }

    public static String requireTraceId() {
        String traceId = getTraceId();
        return StringUtils.hasText(traceId) ? traceId : "UNKNOWN";
    }
}

