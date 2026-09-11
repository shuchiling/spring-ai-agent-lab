package io.github.agentlab.businesstools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class OrderTool {

    @Tool(description = "查询订单状态。只有用户提供明确订单号时才可以调用。")
    public OrderStatus queryOrderStatus(
            @ToolParam(description = "订单号，例如 ORD-20260911-0001") String orderNo) {
        return new OrderStatus(orderNo, "SHIPPED", "订单已发货，预计 2 天内送达。");
    }

    public record OrderStatus(String orderNo, String status, String description) {
    }
}