package io.github.agentlab.app.controller;

import io.github.agentlab.aicore.service.BusinessAgentService;
import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.aicore.service.BusinessAgentService.ToolRouteEvalResponse;
import io.github.agentlab.common.dto.AiChatRequest;
import io.github.agentlab.common.dto.AiChatResponse;
import io.github.agentlab.common.dto.ApiResponse;
import io.github.agentlab.common.dto.ToolRouteDecision;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/")
@RequiredArgsConstructor
public class AiAgentChatController {

    private final BusinessAgentService businessAgentService;

    @PostMapping("business-chat")
    public ApiResponse<AiChatResponse> businessChat(@Valid @RequestBody AiChatRequest request) {
        String traceId = TraceContext.getTraceId();
        return ApiResponse.success(traceId, new AiChatResponse(businessAgentService.businessChat(request.message()), traceId));
    }

    @PostMapping("tool-route")
    public ApiResponse<ToolRouteDecision> toolRoute(@Valid @RequestBody AiChatRequest request) {
        String traceId = TraceContext.getTraceId();
        ToolRouteDecision toolRouteDecision = businessAgentService.routeTool(request.message());
        return ApiResponse.success(traceId, toolRouteDecision);
    }

    @PostMapping("tool-route/eval")
    public ApiResponse<ToolRouteEvalResponse> toolRouteEval() {
        String traceId = TraceContext.getTraceId();
        return ApiResponse.success(traceId, businessAgentService.evalToolRoute());
    }
}
