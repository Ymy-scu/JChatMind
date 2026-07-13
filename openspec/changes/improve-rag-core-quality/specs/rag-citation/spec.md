## ADDED Requirements

### Requirement: SSE 响应携带引用溯源信息

系统 SHALL 在每次触发 RAG 的问答流程末尾，通过 SSE 向前端发送 `references` 事件，携带用于还原来源的完整元数据。

#### Scenario: 命中 chunk 时发送 references 事件
- **WHEN** Agent 在一次问答中至少命中 1 条 chunk 并完成回答
- **THEN** 系统 MUST 在结束前发送一个 `event: references` 的 SSE 事件，`data` 为 JSON 数组，每一项包含 `documentId, filename, pageNumber, headingPath, chunkIndex, snippet` 字段

#### Scenario: 未命中时的空引用
- **WHEN** 一次问答未命中任何 chunk（阈值全部过滤）
- **THEN** 系统 MUST 发送 `event: references`，`data` 为 `[]`，用于前端清空上一次的引用面板

#### Scenario: snippet 长度上限
- **WHEN** 构造 `references` 事件中的 `snippet` 字段
- **THEN** `snippet` MUST 为对应 chunk `content` 的前 200 个字符，超出部分以 `…` 截断；不得触发额外的 LLM 调用

#### Scenario: 引用信息持久化到消息
- **WHEN** 一次问答完成，助手消息被写入 `chat_message` 表
- **THEN** 该消息的 `references` 字段（JSON）MUST 与本轮 SSE 推送的 `references` 一致，供历史消息回显
