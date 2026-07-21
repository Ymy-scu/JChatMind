## ADDED Requirements

### Requirement: 向量召回结果 SHALL 携带余弦距离供 Java 层做真阈值过滤

系统 SHALL 让 `similaritySearch` SQL 返回 chunk 的 cosine `distance`，并在 Java 层用 `1 - distance ≥ threshold` 做真阈值过滤；SELECT 列表 MUST 不再包含 `embedding` 大字段。

#### Scenario: SimilarityResultMap 携带 distance

- **WHEN** MyBatis 执行 `similaritySearch`
- **THEN** 结果集 MUST 包含 `embedding <=> #{vectorLiteral}::vector AS distance` 列，通过 `SimilarityResultMap extends BaseResultMap` 映射到 `ChunkBgeM3.score` 字段

#### Scenario: SELECT 不含 embedding 大字段

- **WHEN** `similaritySearch` 或 `bm25Search` 执行 SQL
- **THEN** SELECT 列表 MUST 不含 `embedding` 列，避免每次拉回 4KB × TopN 的向量列

#### Scenario: Java 侧阈值过滤

- **WHEN** `RagServiceImpl.filterByCosineThreshold` 处理 `similaritySearch` 结果
- **THEN** 保留 `1 - distance ≥ threshold` 的条目；`distance == null`（来自 BM25 分支）时 MUST 保守放过，不因缺 distance 直接丢弃

### Requirement: Agent 检索路径 SHALL 具备 same-query 短路 / 多 KB 并行 / 单 chunk 长度上限

`JChatMind.ensureKnowledgeContext` SHALL 具备下述三项能力，避免同轮反复检索、多知识库串行等待、以及单 chunk 撑爆 prompt。

#### Scenario: same-query 短路

- **WHEN** `ensureKnowledgeContext(query)` 被同一个 Agent 实例连续调用，且 `Objects.equals(query, lastRetrievalQuery)`
- **THEN** 系统 MUST 跳过整条检索链路，仅用现有的 `currentReferences` 走 `rebuildSystemContext`

#### Scenario: 多 KB 并行召回

- **WHEN** 会话绑定多个知识库 `kbIds`（size > 1），触发一次检索
- **THEN** 系统 MUST 用 `CompletableFuture.supplyAsync(...)` 复用 `AsyncConfig.taskExecutor` 并行召回每个 KB，最终 `allOf().join()` 汇总结果

#### Scenario: 单 chunk 长度上限

- **WHEN** 拼接检索结果到 SystemMessage
- **THEN** 单个 chunk 的 `content` 长度 > `PROMPT_CHUNK_CONTENT_MAX_LEN`（默认 800 字符）时 MUST 截断并追加 `……`，避免撑爆 prompt

#### Scenario: 命中后统一重建 SystemMessage

- **WHEN** 检索命中并需要注入到 chatMemory
- **THEN** 系统 MUST 调用 `rebuildSystemContext()` 统一重建位置 0 的 SystemMessage，MUST NOT 向 chatMemory 末尾 append 新的 SystemMessage
