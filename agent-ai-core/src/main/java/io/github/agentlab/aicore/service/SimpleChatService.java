package io.github.agentlab.aicore.service;

import io.github.agentlab.common.context.TraceContext;
import io.github.agentlab.common.dto.IntentAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
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
        long startTime = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        try {
            String content = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();
            log.info("模型调用成功: 类型=普通对话, traceId={}, 用户输入={}, 耗时={}ms",
                    traceId, userMessage, System.currentTimeMillis() - startTime);
            return content;
        } catch (RuntimeException e) {
            log.error("模型调用异常: 类型=普通对话, traceId={}, 用户输入={}, 耗时={}ms",
                    traceId, userMessage, System.currentTimeMillis() - startTime, e);
            throw e;
        }
    }

    public IntentAnalysis analyzeIntent(String message) {
        String userMessage = normalizeMessage(message);
        long startTime = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        try {
            IntentAnalysis intentAnalysis = chatClient.prompt()
                    .system("""
                            你是一个意图识别器。请判断用户问题是否需要调用业务工具。
                            intent 字段只能使用 GENERAL_CHAT、KNOWLEDGE_QA、ORDER_QUERY、TICKET_CREATE、UNKNOWN。
                            不确定时使用 UNKNOWN。
                            如果缺少必要字段，比如订单号、问题描述，请写入 missingFields。
                            """)
                    .user(userMessage)
                    .call()
                    .entity(IntentAnalysis.class);
            log.info("模型调用成功: 类型=意图识别, traceId={}, 用户输入={}, 识别结果={}, 耗时={}ms",
                    traceId, userMessage, intentAnalysis, System.currentTimeMillis() - startTime);
            return intentAnalysis;
        } catch (RuntimeException e) {
            log.error("模型调用异常: 类型=意图识别, traceId={}, 用户输入={}, 耗时={}ms",
                    traceId, userMessage, System.currentTimeMillis() - startTime, e);
            throw e;
        }
    }

    private String normalizeMessage(String message) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message.trim();
    }
}
