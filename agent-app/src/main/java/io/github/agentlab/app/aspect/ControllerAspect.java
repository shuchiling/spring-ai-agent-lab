package io.github.agentlab.app.aspect;

import com.alibaba.fastjson.JSON;
import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.common.dto.ApiResponse;
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
        Object[] args = point.getArgs();
        log.info("接口请求开始: 控制器={}, 方法={}, traceId={}, 请求参数={}",
                serviceOrClassName, methodName, traceId, JSON.toJSONString(args));
        try {
            Object response = point.proceed(args);
            if (response instanceof ApiResponse<?> apiResponse) {
                log.info("接口请求成功: 控制器={}, 方法={}, traceId={}, 请求参数={}, 响应类型={}, success={}, errorCode={}, 耗时={}ms",
                        serviceOrClassName, methodName, traceId, JSON.toJSONString(args),
                        response.getClass().getSimpleName(), apiResponse.success(), apiResponse.errorCode(),
                        System.currentTimeMillis() - startTime);
            } else {
                log.info("接口请求成功: 控制器={}, 方法={}, traceId={}, 请求参数={}, 响应类型={}, 耗时={}ms",
                        serviceOrClassName, methodName, traceId, JSON.toJSONString(args),
                        response == null ? "null" : response.getClass().getSimpleName(), System.currentTimeMillis() - startTime);
            }
            return response;
        } catch (Throwable e) {
            log.error("接口请求异常: 控制器={}, 方法={}, traceId={}, 请求参数={}, 耗时={}ms",
                    serviceOrClassName, methodName, traceId, JSON.toJSONString(args), System.currentTimeMillis() - startTime, e);
            throw e;
        }
    }
}
