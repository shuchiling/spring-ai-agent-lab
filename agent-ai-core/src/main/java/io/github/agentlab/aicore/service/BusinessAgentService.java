package io.github.agentlab.aicore.service;

import io.github.agentlab.businesstools.tool.OrderTool;
import io.github.agentlab.common.context.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class BusinessAgentService {

    private final ChatClient chatClient;
    private final OrderTool orderTool;

    public BusinessAgentService(ChatClient.Builder chatClientBuilder, OrderTool orderTool) {
        this.chatClient = chatClientBuilder
                .build();
        this.orderTool = orderTool;
    }

    public String businessChat(String message) {
        String userMessage = normalizeMessage(message);
        long startTime = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        try {
            String content = chatClient.prompt()
                    .system("""
                            你是一个订单客服助手。
                            你的能力范围仅限订单状态、物流、发货问题，如果用户问题与订单无关，不要回答具体内容，只说明你只能处理订单相关问题，并引导用户提供订单号或订单问题。
                            当用户询问订单状态、物流、发货情况时，如果用户提供了订单号，请调用订单查询工具。
                            如果用户没有提供订单号，请不要调用工具，直接追问用户订单号。
                            不要编造订单状态。
                            """)
                    .user(userMessage)
                    .tools(orderTool)
                    .call()
                    .content();
            log.info("model call success, type=businessChat, traceId={}, message={}, cost={}ms",
                    traceId, userMessage, System.currentTimeMillis() - startTime);
            return content;
        } catch (RuntimeException e) {
            log.error("model call failed, type=businessChat, traceId={}, message={}, cost={}ms",
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
