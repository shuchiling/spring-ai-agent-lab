package io.github.agentlab.common.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 工单确认请求。消费草稿的 confirmToken，真正触发落库。
 */
public record TicketConfirmRequest(
        @NotBlank(message = "confirmToken 不能为空")
        String confirmToken
) {
}
