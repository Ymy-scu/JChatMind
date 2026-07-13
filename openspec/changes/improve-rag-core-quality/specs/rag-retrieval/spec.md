## ADDED Requirements

### Requirement: 向量索引使用 Cosine 距离与 HNSW

系统 SHALL 在 `chunk_bge_m3.embedding` 列上使用 HNSW 索引与 cosine 距离算子，不再使用 L2 距离。

#### Scenario: 数据库索引类型
- **WHEN** 迁移脚本 `V2__rag_core_quality.sql` 执行完毕
- **THEN** `chunk_bge_m3` 表 MUST 存在唯一一个可用于 `embedding` 列的向量索引，且该索引类型为 `hnsw`，操作符类为 `vector_cosine_ops`

#### Scenario: SQL 距离算子
- **WHEN** 检索 SQL 计算 chunk 与查询向量的距离
- **THEN** 该 SQL MUST 使用 `<=>` 算子，且相似度按 `1 - (embedding <=> :query::vector)` 计算返回

### Requirement: 系统提供 Hybrid 检索能力

系统 SHALL 在启用 Hybrid 时并行执行向量召回与 BM25 关键词召回，并通过 Reciprocal Rank Fusion 融合结果。

#### Scenario: Hybrid 检索融合
- **WHEN** `jchatmind.rag.hybrid.enabled=true`，用户提出问题触发 RAG
- **THEN** 系统 MUST 并行发起向量召回（Top-`vectorTopN`，默认 20）与 BM25 召回（Top-`bm25TopN`，默认 20），并按 RRF 公式 `score = Σ 1/(k + rank_i)`（`k=60`）融合，返回 Top-N（默认 20）候选

#### Scenario: Hybrid 关闭时降级
- **WHEN** `jchatmind.rag.hybrid.enabled=false`
- **THEN** 系统 MUST 只执行向量召回，Top-K 结果直接返回，不调用 BM25 查询

#### Scenario: BM25 查询列存在
- **WHEN** 迁移脚本执行完毕
- **THEN** `chunk_bge_m3` 表 MUST 存在生成列 `content_tsv tsvector`（`to_tsvector('simple', content)`）以及对应的 GIN 索引 `idx_chunk_content_tsv`

### Requirement: 相似度阈值与 TopK 可通过配置调整

系统 SHALL 通过 `application.yaml` 的 `jchatmind.rag.*` 配置暴露相似度阈值、TopN、TopK、hybrid/rerank 开关，运行时无需改代码。

#### Scenario: 相似度阈值可配置
- **WHEN** 运维在 `application.yaml` 设置 `jchatmind.rag.similarity.threshold=0.30`
- **THEN** 系统重启后 MUST 使用 `0.30` 作为向量召回的相似度阈值，低于该值的结果被过滤

#### Scenario: 空结果不打断问答
- **WHEN** 融合并阈值过滤后剩余 0 条候选
- **THEN** 系统 MUST 返回空的 references 列表而非抛出异常，且 Agent 继续基于对话历史正常回答
