package io.github.agentlab.common.dto;

import io.github.agentlab.common.enums.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 创建工单草稿请求（模型/用户提议创建工单的入参）。
 */
public record TicketCreateRequest(

        @NotBlank(message = "订单号不能为空")
        @Pattern(regexp = "^ORD-\\d{8}-\\d{4}$", message = "订单号格式错误，示例 ORD-20260911-0001")
        String orderNo,

        @NotBlank(message = "退款原因不能为空")
        String reason,

        @NotNull(message = "优先级不能为空")
        TicketPriority priority
) {
}
