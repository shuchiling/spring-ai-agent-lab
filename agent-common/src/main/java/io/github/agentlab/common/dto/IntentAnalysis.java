package io.github.agentlab.common.dto;

import java.util.List;

public record IntentAnalysis(
        String intent,
        String summary,
        boolean requiresBusinessTool,
        List<String> missingFields
) {
}