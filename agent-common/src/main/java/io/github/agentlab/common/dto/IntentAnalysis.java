package io.github.agentlab.common.dto;

import io.github.agentlab.common.enums.IntentType;

import java.util.List;

public record IntentAnalysis(
        IntentType intent,
        String summary,
        boolean requiresBusinessTool,
        List<String> missingFields
) {
}