package io.github.agentlab.businesstools.tool;

import com.alibaba.fastjson.JSON;
import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.common.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderTool {

    @Tool(description = "查询订单状态。只有用户提供明确订单号时才可以调用。")
    public OrderQueryResult queryOrderStatus(
            @ToolParam(description = "订单号，例如 ORD-20260911-0001") String orderNo) {
        long start = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        String toolName = "queryOrderStatus";
        log.info("工具调用开始: 工具名称={}, traceId={}, 订单号={}", toolName, traceId, orderNo);

        if (StringUtils.isBlank(orderNo)) {
            OrderQueryResult result = new OrderQueryResult(false, null, null, null,
                    ErrorCode.ILLEGAL_ARGUMENT.getCode(), "订单号为空");
            logToolResult(toolName, traceId, result, start);
            return result;
        }

        String normalizedOrderNo = orderNo.trim();
        if (!validate(normalizedOrderNo)) {
            OrderQueryResult result = new OrderQueryResult(false, normalizedOrderNo, null, null,
                    ErrorCode.ILLEGAL_ARGUMENT.getCode(), "订单号不合法");
            logToolResult(toolName, traceId, result, start);
            return result;
        }

        try {
            OrderQueryResult result = switch (normalizedOrderNo) {
                case "ORD-20260911-0001" ->
                        new OrderQueryResult(true, normalizedOrderNo, "已发货", "订单已发货，预计 2 天内送达。", null, null);
                case "ORD-20260911-0002" ->
                        new OrderQueryResult(true, normalizedOrderNo, "待发货", "订单正在等待仓库发货，预计 24 小时内发出。", null, null);
                case "ORD-20260911-0003" ->
                        new OrderQueryResult(true, normalizedOrderNo, "已签收", "订单显示已签收，如有疑问请联系配送员或客服。", null, null);
                default -> new OrderQueryResult(false, normalizedOrderNo, null, null,
                        ErrorCode.BAD_REQUEST.getCode(), "订单不存在");
            };
            logToolResult(toolName, traceId, result, start);
            return result;
        } catch (RuntimeException e) {
            log.error("工具调用异常: 工具名称={}, traceId={}, 订单号={}, success=false, 耗时={}ms",
                    toolName, traceId, normalizedOrderNo, System.currentTimeMillis() - start, e);
            throw e;
        }
    }


    private boolean validate(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return false;
        }
        String regex = "^ORD-\\d{8}-\\d{4}$";
        return orderNo.matches(regex);
    }

    private void logToolResult(String toolName, String traceId, OrderQueryResult result, long start) {
        if (result.success()) {
            log.info("工具调用成功: 工具名称={}, traceId={}, 订单号={}, success={}, 耗时={}ms, 返回结果={}",
                    toolName, traceId, result.orderNo(), true, System.currentTimeMillis() - start,
                    JSON.toJSONString(result));
            return;
        }
        log.warn("工具调用业务失败: 工具名称={}, traceId={}, 订单号={}, success={}, 错误码={}, 耗时={}ms, 返回结果={}",
                toolName, traceId, result.orderNo(), false, result.errorCode(), System.currentTimeMillis() - start,
                JSON.toJSONString(result));
    }

    public record OrderQueryResult(boolean success,
                                   String orderNo,
                                   String status,
                                   String description,
                                   String errorCode,
                                   String errorMessage) {
    }
}
