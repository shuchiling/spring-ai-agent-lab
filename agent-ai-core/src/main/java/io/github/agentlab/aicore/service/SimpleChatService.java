package io.github.agentlab.aicore.service;

import io.github.agentlab.common.dto.IntentAnalysis;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SimpleChatService {

    private final ChatClient chatClient;

    public SimpleChatService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        你是一个帮助 Java 后端工程师学习 Spring AI、RAG、Memory 和 Agent 工程的教学助手。
                        回答要简洁、准确，并尽量结合后端工程实践。
                        """)
                .build();
    }

    public String chat(String message) {
        String userMessage = normalizeMessage(message);
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }

    public IntentAnalysis analyzeIntent(String message) {
        String userMessage = normalizeMessage(message);
        return chatClient.prompt()
                .system("""
                        你是一个意图识别器。请判断用户问题是否需要调用业务工具。
                        intent 可选值：GENERAL_CHAT, KNOWLEDGE_QA, ORDER_QUERY, TICKET_CREATE, UNKNOWN。
                        如果缺少必要字段，比如订单号、问题描述，请写入 missingFields。
                        """)
                .user(userMessage)
                .call()
                .entity(IntentAnalysis.class);
    }

    private String normalizeMessage(String message) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message.trim();
    }
}