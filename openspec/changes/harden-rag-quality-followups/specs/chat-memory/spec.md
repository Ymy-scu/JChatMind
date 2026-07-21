## ADDED Requirements

### Requirement: 对话记忆压缩 SHALL 异步执行且同步清理 DB 老消息

`ChatMemoryCompressionService` SHALL 提供异步入口 `compressAsync(sessionId, chatClient)`，且 `compress` SHALL 在压缩完成后同步清理 DB 中除末尾窗口以外的老消息，避免下次 `loadMemory` 从 DB 拉回老消息与摘要重复堆叠。

#### Scenario: 异步入口

- **WHEN** `JChatMind.checkAndCompress` 判断需要压缩
- **THEN** 系统 MUST 调用 `compressAsync(sessionId, chatClient)`，MUST NOT 在流式响应链路上同步调用 LLM

#### Scenario: 复用 taskExecutor

- **WHEN** `compressAsync` 实际执行
- **THEN** 系统 MUST 通过 Spring `@Async` 复用 `AsyncConfig.taskExecutor`（4 核 / 10 峰值 / 100 队列），MUST NOT 每次新建线程

#### Scenario: DB 老消息同步清理

- **WHEN** `compress` 完成摘要写入并推入 Redis 摘要缓存
- **THEN** 系统 MUST 调用 `chatMessageMapper.deleteOldestExcludingRecent(sessionId, KEEP_RECENT_MESSAGES)`，只保留末尾窗口

#### Scenario: DB 清理失败降级

- **WHEN** `deleteOldestExcludingRecent` 抛异常
- **THEN** 系统 MUST 记录 `WARN` 日志（含 `sessionId + 原因`）并继续正常返回，MUST NOT 中断压缩流程或抛出到 caller

#### Scenario: 压缩提示词长度上限

- **WHEN** `buildCompressionPrompt` 拼接历史消息到 prompt
- **THEN** 拼接结果长度超过 `MAX_COMPRESSION_CHARS`（默认 20000）时 MUST 从尾部截断，避免撞 LLM context 上限

### Requirement: SystemMessage 上下文 SHALL 由 rebuildSystemContext 统一维护在位置 0

`JChatMind` SHALL 通过 `rebuildSystemContext()` 把 `systemPrompt` + 最新摘要 + `currentReferences` 三个来源拼成一条 `SystemMessage` 放在 chatMemory 位置 0；`JChatMindFactory.loadMemory` MUST 跳过历史 SystemMessage，交给 `rebuildSystemContext` 统一处理，避免打断 `tool_calls ↔ tool_response` 相邻性。

#### Scenario: 三来源合一

- **WHEN** `rebuildSystemContext` 被触发
- **THEN** 系统 MUST 把 `systemPrompt`、最新摘要、`currentReferences`（本轮检索结果）拼成一条 `SystemMessage`（三段用 markdown 分节标题包裹），放在 chatMemory 位置 0

#### Scenario: 幂等重建

- **WHEN** `rebuildSystemContext` 被多次调用（例如多轮工具循环）
- **THEN** 系统 MUST 使用 `chatMemory.clear + add` 模式幂等重建，MUST NOT 在末尾累积多条 SystemMessage

#### Scenario: 加载记忆跳过历史 SystemMessage

- **WHEN** `JChatMindFactory.loadMemory` 从持久化恢复消息
- **THEN** 对 `Message.getMessageType() == SYSTEM` 的历史条目 MUST `continue` 跳过，只恢复 USER / ASSISTANT / TOOL 消息

#### Scenario: 恢复后立刻重建

- **WHEN** `loadMemory` 完成消息恢复
- **THEN** 系统 MUST 在返回 Agent 之前调用一次 `rebuildSystemContext`，保证 chatMemory 位置 0 有当前 SystemMessage

#### Scenario: 不打断 tool_calls 相邻性

- **WHEN** chatMemory 中存在一段 `USER → ASSISTANT(tool_calls) → TOOL(tool_response) → ASSISTANT` 的工具循环
- **THEN** `rebuildSystemContext` MUST NOT 在这段序列内部插入 SystemMessage；SystemMessage MUST 只出现在位置 0
