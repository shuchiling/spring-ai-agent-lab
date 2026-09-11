package io.github.agentlab.app.controller;

import io.github.agentlab.aicore.service.SimpleChatService;
import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.common.dto.AiChatRequest;
import io.github.agentlab.common.dto.AiChatResponse;
import io.github.agentlab.common.dto.ApiResponse;
import io.github.agentlab.common.dto.IntentAnalysis;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final SimpleChatService simpleChatService;


    @PostMapping("/chat")
    public ApiResponse<AiChatResponse> chat(@Valid @RequestBody AiChatRequest request) {
        String answer = simpleChatService.chat(request.message());
        String traceId = TraceContext.getTraceId();
        return ApiResponse.success(new AiChatResponse(answer, traceId));
    }

    @PostMapping("/intent")
    public ApiResponse<IntentAnalysis> analyzeIntent(@Valid @RequestBody AiChatRequest request) {
        IntentAnalysis intentAnalysis = simpleChatService.analyzeIntent(request.message());
        return ApiResponse.success(intentAnalysis);
    }
}
