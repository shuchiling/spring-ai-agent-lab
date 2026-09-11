package io.github.agentlab.app.controller;

import io.github.agentlab.aicore.service.SimpleChatService;
import io.github.agentlab.common.dto.AiChatRequest;
import io.github.agentlab.common.dto.AiChatResponse;
import io.github.agentlab.common.dto.IntentAnalysis;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private final SimpleChatService simpleChatService;

    public AiChatController(SimpleChatService simpleChatService) {
        this.simpleChatService = simpleChatService;
    }

    @PostMapping("/chat")
    public AiChatResponse chat(@RequestBody AiChatRequest request) {
        String traceId = UUID.randomUUID().toString();
        String answer = simpleChatService.chat(request.message());
        return new AiChatResponse(answer, traceId);
    }

    @PostMapping("/intent")
    public IntentAnalysis analyzeIntent(@RequestBody AiChatRequest request) {
        return simpleChatService.analyzeIntent(request.message());
    }
}