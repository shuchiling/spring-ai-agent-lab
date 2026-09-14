package io.github.agentlab.aicore.service;

import io.github.agentlab.businesstools.tool.AfterSalesTool;
import io.github.agentlab.businesstools.tool.LogisticsTool;
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
    private final LogisticsTool logisticsTool;
    private final AfterSalesTool afterSalesTool;

    public BusinessAgentService(ChatClient.Builder chatClientBuilder, OrderTool orderTool, LogisticsTool logisticsTool, AfterSalesTool afterSalesTool) {
        this.chatClient = chatClientBuilder
                .build();
        this.orderTool = orderTool;
        this.logisticsTool = logisticsTool;
        this.afterSalesTool = afterSalesTool;
    }

    public String businessChat(String message) {
        String userMessage = normalizeMessage(message);
        long startTime = System.currentTimeMillis();
        String traceId = TraceContext.getTraceId();
        try {
            String content = chatClient.prompt()
                    .system("""
                            你是一个订单客服助手。
                            用户问订单状态、是否发货、是否签收时，调用订单状态查询工具。
                            用户问快递、物流、包裹到哪里了、配送进度时，调用物流查询工具。
                            用户问退款、退货、售后、售后进度时，调用售后退款查询工具。
                            缺少订单号时，追问订单号
                            问题不属于订单客服范围时，说明只能处理订单、物流、售后相关问题
                            不要编造工具返回之外的信息
                            工具返回 success=false 时，不要编造
                            订单不存在时，提示用户检查订单号
                            参数缺失时，追问订单号
                            """)
                    .user(userMessage)
                    .tools(orderTool, logisticsTool, afterSalesTool)
                    .call()
                    .content();
            log.info("模型调用成功: 类型=订单客服, traceId={}, 用户输入={}, 耗时={}ms",
                    traceId, userMessage, System.currentTimeMillis() - startTime);
            return content;
        } catch (RuntimeException e) {
            log.error("模型调用异常: 类型=订单客服, traceId={}, 用户输入={}, 耗时={}ms",
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
