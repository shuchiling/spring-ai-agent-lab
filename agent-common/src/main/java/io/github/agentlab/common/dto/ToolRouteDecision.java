package io.github.agentlab.common.dto;

import io.github.agentlab.common.enums.ToolRouteType;

public record ToolRouteDecision(
        ToolRouteType routeType,
        String reason,
        boolean hasOrderNo,
        String orderNo
) {
}