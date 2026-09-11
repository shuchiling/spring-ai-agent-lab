package io.github.agentlab.app.aspect;

import com.alibaba.fastjson.JSON;
import io.github.agentlab.common.context.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class ControllerAspect {

    @Around(value = "execution(public * io.github.agentlab.app.controller..*(..))")
    public Object traceAndTimingAdvice(ProceedingJoinPoint point) throws Throwable {
        String serviceOrClassName = point.getSignature().getDeclaringType().getSimpleName();
        String methodName = point.getSignature().getName();
        long startTime = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        log.info("api request start, controller={}, method={}, traceId={}", serviceOrClassName, methodName, traceId);
        Object[] args = point.getArgs();
        try {
            Object response = point.proceed(args);
            log.info("api request success, controller={}, method={}, traceId={}, request={}, cost={}ms",
                    serviceOrClassName, methodName, traceId, JSON.toJSONString(args), System.currentTimeMillis() - startTime);
            return response;
        } catch (Throwable e) {
            log.error("api request failed, controller={}, method={}, traceId={}, request={}, cost={}ms",
                    serviceOrClassName, methodName, traceId, JSON.toJSONString(args), System.currentTimeMillis() - startTime, e);
            throw e;
        }
    }
}
