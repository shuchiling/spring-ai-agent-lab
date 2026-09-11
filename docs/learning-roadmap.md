# Spring AI Agent 实战学习路线

这份文档是 `spring-ai-agent-lab` 的任务清单和学习路线。

学习方式采用“我派任务，你实现，我 Review”的节奏推进。每一关都围绕一个明确能力点展开：先跑通最小闭环，再逐步补齐工程化能力，最后组合成企业知识库 RAG 和业务 Agent 两条项目线。

## 学习目标

通过这个项目，需要逐步吃透以下能力：

1. 使用 Spring AI 构建基础 LLM 应用。
2. 理解 Prompt、结构化输出、流式响应和模型参数。
3. 掌握 Tool Calling，让模型安全地调用 Java 后端能力。
4. 掌握 RAG，从文档入库到带引用回答。
5. 掌握 Memory，区分聊天上下文、长期记忆、任务状态和业务数据。
6. 掌握 Agent 编排，把模型、工具、知识库和人审流程组合起来。
7. 掌握工程化能力，包括日志、trace、评测、权限、安全和成本控制。

## 总体路线

```text
任务 01：LLM 最小闭环 + 结构化输出
任务 02：Tool Calling 基础：让模型调用订单查询工具
任务 03：业务 Agent 基础：订单查询 + 参数缺失追问
任务 04：工单创建工具：敏感动作前的人审确认
任务 05：Memory 基础：基于 conversationId 的多轮会话
任务 06：Memory 进阶：长期用户偏好和任务状态
任务 07：RAG 基础：本地文档切片、入库、检索
任务 08：RAG 问答：基于检索上下文回答并返回引用
任务 09：RAG + Tool Agent：先查知识库，再创建工单
任务 10：Agent Orchestrator：状态机、幂等、失败恢复
任务 11：可观测性：模型日志、工具调用日志、执行轨迹
任务 12：评测与安全：评测集、回归测试、Prompt Injection 防护
```

## 任务 01：LLM 最小闭环 + 结构化输出

### 目标

在现有基础代码上，补齐一个后端 AI 应用最基本的工程外壳：

- 请求参数校验
- 统一响应结构
- 全局异常处理
- traceId
- 结构化意图识别
- 模型调用日志

这一关不是普通 Controller 包装。它会成为后续 Agent 执行轨迹、工具调用审计、RAG 失败兜底和生产化监控的地基。

### 实现要求

1. 给 `AiChatRequest.message` 增加非空校验。

   建议使用 Jakarta Validation：

   ```java
   public record AiChatRequest(
           @NotBlank(message = "message must not be blank")
           String message
   ) {
   }
   ```

2. Controller 方法增加 `@Valid`。

   ```java
   public ApiResponse<AiChatResponse> chat(@Valid @RequestBody AiChatRequest request)
   ```

3. 新增统一响应结构 `ApiResponse<T>`。

   建议放在 `agent-common` 模块：

   ```java
   public record ApiResponse<T>(
           boolean success,
           T data,
           String errorCode,
           String errorMessage,
           String traceId
   ) {
   }
   ```

   可以补充静态工厂方法，例如 `success(...)`、`fail(...)`。

4. 新增全局异常处理。

   建议放在 `agent-app` 模块，例如：

   ```text
   io.github.agentlab.app.handler.GlobalExceptionHandler
   ```

   至少处理：

   - 参数校验异常
   - `IllegalArgumentException`
   - 兜底异常

5. `/api/ai/chat` 返回 `ApiResponse<AiChatResponse>`。

6. `/api/ai/intent` 返回 `ApiResponse<IntentAnalysis>`。

7. 新增 `IntentType` 枚举。

   建议放在 `agent-common` 模块：

   ```java
   public enum IntentType {
       GENERAL_CHAT,
       KNOWLEDGE_QA,
       ORDER_QUERY,
       TICKET_CREATE,
       UNKNOWN
   }
   ```

8. 将 `IntentAnalysis.intent` 从 `String` 改成 `IntentType`。

9. 修改 `SimpleChatService.analyzeIntent()` 的 system prompt。

   Prompt 里要明确告诉模型：

   - `intent` 字段只能使用 `GENERAL_CHAT`、`KNOWLEDGE_QA`、`ORDER_QUERY`、`TICKET_CREATE`、`UNKNOWN`
   - 不确定时使用 `UNKNOWN`
   - 缺少订单号、问题描述等必要信息时写入 `missingFields`

10. 增加模型调用日志。

    先不用入库，使用日志打印即可。

    日志至少包含：

    - `traceId`
    - 用户输入
    - 接口类型：`chat` 或 `intent`
    - 是否成功
    - 耗时毫秒数

### 建议改造点

当前 `traceId` 在 Controller 中生成。你可以继续保留在 Controller，也可以下沉到 Service 入参。

为了让日志能拿到 traceId，建议将 Service 方法改成：

```java
public String chat(String message, String traceId)

public IntentAnalysis analyzeIntent(String message, String traceId)
```

这样第一阶段先保持简单。后续任务会再演进成真正的 trace 上下文和执行轨迹表。

### 验收标准

执行测试：

```powershell
mvn test
```

启动应用：

```powershell
mvn -pl agent-app spring-boot:run
```

调用普通问答接口：

```http
POST http://localhost:8080/api/ai/chat
Content-Type: application/json

{
  "message": "什么是 RAG？"
}
```

期望返回统一结构：

```json
{
  "success": true,
  "data": {
    "answer": "...",
    "traceId": "..."
  },
  "errorCode": null,
  "errorMessage": null,
  "traceId": "..."
}
```

调用意图识别接口：

```http
POST http://localhost:8080/api/ai/intent
Content-Type: application/json

{
  "message": "帮我查一下订单 ORD-20260911-0001 的物流状态"
}
```

期望返回类似：

```json
{
  "success": true,
  "data": {
    "intent": "ORDER_QUERY",
    "summary": "用户想查询订单物流状态",
    "requiresBusinessTool": true,
    "missingFields": []
  },
  "errorCode": null,
  "errorMessage": null,
  "traceId": "..."
}
```

调用参数错误场景：

```http
POST http://localhost:8080/api/ai/chat
Content-Type: application/json

{
  "message": ""
}
```

期望：

- `success` 为 `false`
- 有明确 `errorCode`
- 有明确 `errorMessage`
- 有 `traceId`

### 本关要吃透的问题

完成任务后，请回答下面问题：

1. 为什么 Agent 系统必须尽早引入 `traceId`？
2. 为什么 `IntentAnalysis.intent` 不建议继续使用 `String`？
3. 模型结构化输出失败时，后端应该如何兜底？
4. 参数校验异常和模型调用异常，为什么要走统一响应结构？
5. 这次日志里记录耗时，对后续成本统计和性能优化有什么帮助？

## 后续任务预告

### 任务 02：Tool Calling 基础

目标：让模型通过 Spring AI 调用 `OrderTool.queryOrderStatus(...)`。

会实践：

- `@Tool`
- `@ToolParam`
- 工具注册
- 工具调用日志
- 工具参数缺失处理
- 工具结果再交给模型总结

### 任务 03：业务 Agent 基础

目标：实现一个最小业务 Agent：

```text
用户输入
-> 意图识别
-> 判断是否需要订单工具
-> 有订单号则查询
-> 无订单号则追问
-> 返回统一回答
```

### 任务 04：工单创建工具

目标：引入有副作用的业务动作，学习为什么 Agent 不能随便执行写操作。

会实践：

- 创建工单工具
- 工具入参设计
- 敏感操作确认
- 幂等 key
- 工具调用审计

### 任务 05：Memory 基础

目标：实现基于 `conversationId` 的多轮对话。

会实践：

- 保存最近 N 轮消息
- 将历史消息注入 Prompt
- 控制上下文长度
- 区分会话记忆和业务数据

### 任务 06：Memory 进阶

目标：加入长期用户偏好和任务状态。

会实践：

- 用户偏好抽取
- 长期记忆保存
- 任务状态机
- 记忆更新策略

### 任务 07：RAG 基础

目标：实现最小文档检索链路。

会实践：

- 文档解析
- 文本切片
- Embedding
- 向量库
- 相似度检索

### 任务 08：RAG 问答

目标：让模型基于检索结果回答，并返回引用来源。

会实践：

- 检索上下文拼装
- 引用来源
- 无答案拒答
- 简单幻觉控制

### 任务 09：RAG + Tool Agent

目标：组合知识问答和业务工具。

示例：

```text
用户问售后政策
-> Agent 查询知识库
-> 判断是否需要创建工单
-> 收集必要信息
-> 用户确认
-> 创建工单
```

### 任务 10：Agent Orchestrator

目标：把 Agent 从“流程 Demo”升级为可维护编排。

会实践：

- 状态机
- 步骤记录
- 失败恢复
- 幂等
- 人审

### 任务 11：可观测性

目标：看得清一次 Agent 到底做了什么。

会实践：

- 模型调用日志
- 工具调用日志
- RAG 检索日志
- Agent 执行轨迹
- Token 和耗时统计

### 任务 12：评测与安全

目标：让 Agent 有质量基线，不靠感觉上线。

会实践：

- 构造评测集
- 回归测试
- Prompt Injection 防护
- 敏感工具权限控制
- 拒答策略

## 当前进度

- [x] 初始化 Maven 多模块工程
- [x] 接入 Spring Boot 3.x 和 Spring AI 1.x
- [x] 提供基础 ChatClient 调用
- [x] 提供结构化意图识别接口
- [x] 创建业务工具模块雏形
- [ ] 任务 01：LLM 最小闭环 + 结构化输出
- [ ] 任务 02：Tool Calling 基础
- [ ] 任务 03：业务 Agent 基础
- [ ] 任务 04：工单创建工具
- [ ] 任务 05：Memory 基础
- [ ] 任务 06：Memory 进阶
- [ ] 任务 07：RAG 基础
- [ ] 任务 08：RAG 问答
- [ ] 任务 09：RAG + Tool Agent
- [ ] 任务 10：Agent Orchestrator
- [ ] 任务 11：可观测性
- [ ] 任务 12：评测与安全
