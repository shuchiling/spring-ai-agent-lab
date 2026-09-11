# Spring AI Agent Lab

`spring-ai-agent-lab` 是一个面向 Java 后端工程师的 Spring AI 实战学习项目。

项目目标不是只做一个能聊天的 Demo，而是从后端工程视角，逐步实践大模型应用、RAG、Memory、Tool Calling、Agent 编排和生产化能力，最终形成一个可展示、可复盘、可继续扩展的企业级 Agent 学习工程。

## 仓库用途

这个仓库主要用于：

1. 学习 Spring AI 在 Java 后端项目中的真实使用方式。
2. 实践 LLM 应用从“模型问答”到“业务 Agent”的完整演进过程。
3. 沉淀 RAG、Memory、工具调用、Agent 编排、评测和可观测性等核心能力。
4. 构建一个可以放入作品集的企业级 AI 应用项目。
5. 记录 Java 后端开发者转向 Agent 工程方向的学习路线和代码实践。

## 技术栈

当前项目优先选择企业项目中更容易落地的稳定组合：

```text
JDK 17
Spring Boot 3.x
Spring AI 1.x
Maven 多模块
Docker Desktop
PostgreSQL / pgvector
Redis
Elasticsearch 或 OpenSearch
```

后续会根据学习阶段逐步引入向量库、文档解析、SSE 流式响应、执行轨迹、评测集和安全防护能力。

## 模块说明

```text
spring-ai-agent-lab
|- agent-app              # Spring Boot 启动模块，对外暴露 HTTP API
|- agent-common           # 通用 DTO、枚举、异常、响应模型
|- agent-ai-core          # 模型调用、ChatClient、Prompt、结构化输出
|- agent-business-tools   # 订单查询、工单创建等业务工具
|- agent-rag              # 文档解析、切片、Embedding、向量检索、引用回答
|- agent-memory           # 会话记忆、用户偏好、长期记忆、任务状态
`- agent-orchestrator     # Agent 编排、工具调用、人审确认、执行轨迹
```

### agent-app

应用入口模块，负责启动 Spring Boot 应用，并对外提供 REST API。  
Controller、接口鉴权、请求校验、统一异常处理、SSE 流式接口等能力会优先放在这里。

### agent-common

公共基础模块，放置跨模块共享的 DTO、枚举、异常、常量和通用响应结构。  
这个模块保持轻量，避免引入具体业务和 AI 框架依赖。

### agent-ai-core

AI 能力核心模块，负责封装 Spring AI 的基础能力：

- ChatClient 调用
- System Prompt / User Prompt 管理
- 结构化输出
- 模型参数配置
- 流式响应
- 模型调用日志
- Token 和成本统计

### agent-business-tools

业务工具模块，用来模拟真实企业系统里的业务能力。  
例如：

- 查询订单状态
- 查询客户信息
- 创建售后工单
- 修改工单优先级
- 发送通知

这些能力后续会通过 Spring AI Tool Calling 暴露给 Agent 使用。

### agent-rag

RAG 实践模块，负责把企业知识接入大模型。  
后续会覆盖：

- 文档上传
- PDF / Markdown / TXT 解析
- 文本切片
- Embedding 生成
- 向量库存储
- 相似度检索
- 混合检索
- Rerank
- 带引用来源的回答
- Prompt Injection 防护

### agent-memory

记忆模块，负责实践不同类型的 Memory。  
这里会重点区分：

- 短期上下文：当前多轮对话窗口
- 会话记忆：某个 conversationId 下的历史消息
- 长期记忆：用户偏好、画像、稳定事实
- 任务状态：Agent 当前执行到哪一步
- 业务状态：订单、工单等系统真实数据

### agent-orchestrator

Agent 编排模块，负责把模型、RAG、Memory 和业务工具组合成完整工作流。  
后续会实践：

- 意图识别
- 工具选择
- 多步骤执行
- 敏感操作二次确认
- 工具调用幂等
- 执行失败兜底
- Agent 执行轨迹
- 人工介入与审批

## 项目路线

详细任务清单见：[Spring AI Agent 实战学习路线](docs/learning-roadmap.md)。

### 第 1 阶段：LLM 最小闭环

目标：先把大模型调用链路跑通。

实践内容：

- 使用 Spring AI ChatClient 完成普通问答
- 实现结构化 JSON 输出
- 理解 System Prompt、User Prompt、模型参数
- 记录一次模型调用的 traceId
- 提供基础 REST API

阶段产物：

- `/api/ai/chat`
- `/api/ai/intent`

### 第 2 阶段：Tool Calling 业务工具

目标：让模型不只是回答，还能调用后端业务能力。

实践内容：

- 定义 `@Tool` 方法
- 设计工具入参和出参
- 模拟订单查询工具
- 模拟工单创建工具
- 处理工具参数缺失
- 记录工具调用日志

重点理解：

模型不会直接调用数据库或接口。  
模型只会表达“想调用哪个工具、参数是什么”，真正执行工具的是后端应用。

### 第 3 阶段：RAG 企业知识库

目标：让模型基于企业私有知识回答问题。

实践内容：

- 文档入库
- 文本切片
- Embedding
- 向量检索
- 召回上下文注入 Prompt
- 回答附带引用来源
- 无答案时拒答

重点理解：

RAG 的核心不是“把文档塞给模型”，而是先用检索系统找到相关片段，再让模型基于这些片段回答。

### 第 4 阶段：Memory 记忆系统

目标：让应用具备多轮对话和长期上下文能力。

实践内容：

- 基于 conversationId 管理会话
- 保存最近多轮对话
- 总结长期用户偏好
- 区分记忆和业务数据
- 控制上下文长度

重点理解：

Memory 不是无限保存聊天记录。  
真正有价值的是选择哪些信息该记、记多久、什么时候取出来用。

### 第 5 阶段：RAG + Tool 的业务 Agent

目标：把知识问答和业务动作组合成一个可用 Agent。

示例场景：

用户咨询售后政策，Agent 先查询知识库，判断是否满足售后条件，再询问必要信息，最后创建工单。

实践内容：

- 意图识别
- RAG 问答
- 工具调用
- 多步骤任务状态
- 用户确认
- 工单创建
- 执行轨迹查看

### 第 6 阶段：工程化与生产化

目标：从 Demo 走向可上线系统。

实践内容：

- 统一异常处理
- 模型调用日志
- 工具调用审计
- Token 用量统计
- 成本统计
- 限流和超时
- 敏感信息脱敏
- Prompt Injection 防护
- Agent 评测集
- 回归测试
- 可观测性面板

## 本地运行

运行前设置 OpenAI 兼容 API Key：

```powershell
$env:OPENAI_API_KEY="your-api-key"
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
  "message": "用三句话解释什么是 RAG"
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

## 学习原则

这个项目会遵循几个原则：

1. 先跑通闭环，再追求复杂架构。
2. 每个 AI 概念都必须有对应代码实践。
3. 所有 Agent 能力都要回到后端工程问题：权限、事务、幂等、日志、安全、监控。
4. 不把模型当万能黑盒，能确定的流程尽量用代码确定下来。
5. 每个阶段都保留可运行、可测试、可复盘的版本。

## 当前进度

- [x] 初始化 Maven 多模块工程
- [x] 接入 Spring Boot 3.x 和 Spring AI 1.x
- [x] 提供基础 ChatClient 调用
- [x] 提供结构化意图识别接口
- [x] 创建业务工具模块雏形
- [ ] 实现 Tool Calling 闭环
- [ ] 实现 RAG 文档问答
- [ ] 实现 Memory 会话记忆
- [ ] 实现业务 Agent 编排
- [ ] 补充评测、日志、审计和安全能力
