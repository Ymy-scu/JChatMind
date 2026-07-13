## ADDED Requirements

### Requirement: 系统提供基于 Cross-Encoder 的重排能力

系统 SHALL 在启用 Rerank 时，对 Hybrid 融合后的 Top-N 结果调用 `bge-reranker-v2-m3` 进行二次排序，取 Top-K 作为最终引用。

#### Scenario: Rerank 生效
- **WHEN** `jchatmind.rag.rerank.enabled=true`，Hybrid 返回 Top-N=20 的候选
- **THEN** 系统 MUST 将 `(query, chunk.content)` 送入 reranker，按重排分数取 Top-K（默认 5）返回

#### Scenario: Reranker 服务失败降级
- **WHEN** Reranker HTTP 调用超时（默认 3 秒）或返回非 2xx
- **THEN** 系统 MUST 记录 WARN 日志并直接使用 Hybrid 融合后的前 Top-K 结果，不阻断 Agent 回答

#### Scenario: Rerank 关闭时透传
- **WHEN** `jchatmind.rag.rerank.enabled=false`
- **THEN** 系统 MUST 跳过 rerank 调用，把 Hybrid 融合后的前 Top-K 结果直接作为最终引用

#### Scenario: Rerank 输入去重
- **WHEN** 送入 reranker 的候选中存在 `documentId + chunkIndex` 完全相同的重复项
- **THEN** 系统 MUST 在调用 reranker 前先去重，避免同一条 chunk 被打两次分
