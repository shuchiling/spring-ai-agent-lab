package io.github.agentlab.businesstools.tool;

import com.alibaba.fastjson.JSON;
import io.github.agentlab.common.context.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderTool {

    @Tool(description = "查询订单状态。只有用户提供明确订单号时才可以调用。")
    public OrderStatus queryOrderStatus(
            @ToolParam(description = "订单号，例如 ORD-20260911-0001") String orderNo) {
        log.info("queryOrderStatus tool start");
        long start = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        OrderStatus orderStatus = null;
        try {
            orderStatus = new OrderStatus(orderNo, "SHIPPED", "订单已发货，预计 2 天内送达。");
        } catch (Exception e) {
            log.error("调用queryOrderStatus工具失败: orderNo:{}, traceId:{}, cost {} ms", orderNo, traceId, System.currentTimeMillis() - start);
            throw e;
        }
        log.info("调用queryOrderStatus工具成功: orderNo:{}, response:{}, traceId:{}, cost {} ms", orderNo, JSON.toJSONString(orderStatus), traceId, System.currentTimeMillis() - start);
        return orderStatus;
    }

    public record OrderStatus(String orderNo, String status, String description) {
    }
}