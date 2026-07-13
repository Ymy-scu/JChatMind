## Context

JChatMind 的现有 RAG 由 `DocumentFacadeServiceImpl` + `RagServiceImpl` + `ChunkBgeM3Mapper` 三段组成。索引侧按 Markdown/PDF/Word 各自的解析结果切分成 chunk，通过 Ollama `bge-m3` 生成 embedding，存入 pgvector 的 `chunk_bge_m3` 表，索引类型为 `ivfflat (vector_l2_ops)`。检索侧由 Agent 在 `ensureKnowledgeContext` 中调用 `similaritySearch(query, topK=5, threshold=0.7)`，把命中片段作为 SystemMessage 注入 chatMemory。

代码级审查发现四个致命错位：
1. `DocumentFacadeServiceImpl` 对 `title` 做 embedding，却返回 `content` —— 检索向量空间与召回内容不一致。
2. `ChunkBgeM3Mapper.xml` 使用 `<->`（L2 距离），bge-m3 官方要求 cosine（`<=>`）；ivfflat 索引也是 `vector_l2_ops`，语义与索引一起错。
3. 切分完全依赖解析器输出，无 token 上限、无 overlap，长段落直接超过 bge-m3 8k token 上限被截断。
4. chunk 只有 `content + embedding`，没有 filename / pageNumber / heading 面包屑，无法引用溯源、无法基于元数据过滤。

除此之外，`SecurityConfig` 已允许匿名访问 `/document/**`，但知识库尚未做 `user_id` 隔离；本变更暂不涉及权限修复，仅在数据模型上预留 `user_id` 冗余字段以便后续变更接入。

## Goals / Non-Goals

**Goals:**
- 用最小改动修复"嵌入-召回"错位与索引-算子错位，让现有 chunk 立刻能正确检索。
- 通过一层 token 二次切分与元数据补齐，使得切分结果稳定、可追溯，不再受解析器随意长段影响。
- 引入 Hybrid + Rerank + 引用溯源，把 Top-5 命中率从"能凑"提升到"能用"。
- 让阈值/TopN/TopK/rerank 开关等可通过 `application.yaml` 热配置，方便回归对比。
- 提供在线双读双写迁移路径，保证升级过程不中断线上问答。

**Non-Goals:**
- 不引入 GraphRAG、HyDE、SPLADE、Contextual Retrieval 等更高级方案（放到后续变更 `enhance-rag-advanced-retrieval`）。
- 不做 PDF OCR / Marker / MinerU 高保真解析（另开变更 `improve-doc-parsing-high-fidelity`）。
- 不做自动化评测集（RAGAS/TruLens），只做手工对比验证。
- 不做多租户权限过滤，仅预留字段。
- 不改动 Agent ReAct 循环与工具调用体系。

## Decisions

### 决策 1：切分策略——「解析器结构 + Token 二次切分」两段式

**选择**：保留现有 Markdown/PDF/Word 解析器输出的粗结构 chunk，之后统一走一个 `TokenAwareSplitter`：
- 阈值：`maxTokens=512`、`overlap=64`、`minTokens=64`。
- token 计数用 `bge-m3` 的 tokenizer 近似（无原生 Java 实现时退化为 UTF-8 字节数 / 3 的估算）。
- 遇到过短片段与相邻同 heading 段合并；遇到过长片段按句子边界（`。！？；.!?;`）二次切；仍超长再硬切。

**为什么不用**：
- 纯 fixed-size：完全丢失文档结构。
- 纯 Recursive Character Splitter（LangChain 风格）：与已有 Markdown/PDF 解析结果重复计算，浪费。
- Semantic Chunker（按 embedding 相似度切）：额外一轮 embedding，成本高且效果不稳定；放到后续变更。
- Parent-Child Chunking：需要改数据模型（新增父子关系表），先跑通基础再上。

### 决策 2：向量存储——迁到 HNSW + Cosine

**选择**：
- 表 `chunk_bge_m3` 中 `embedding` 保留 `vector(1024)`，索引类型改成 `hnsw (embedding vector_cosine_ops) WITH (m=16, ef_construction=64)`。
- Mapper 中距离算子由 `<->` 改成 `<=>`，相似度返回 `1 - (embedding <=> :q::vector)`。
- 阈值语义从 L2 距离改成 cosine similarity，默认 `0.35`（原 `0.7` L2 距离约等于 cosine 0.3~0.4，取中值），通过配置暴露。

**为什么不用**：
- 保留 ivfflat：召回精度对 lists 参数敏感，且需要 `ANALYZE` 后才生效；HNSW 在中小规模（<100 万 chunk）上表现更稳。
- 保留 L2：bge-m3 是归一化向量，L2 与 cosine 数学等价但语义习惯不同；官方文档、评测集全部以 cosine 汇报，保留 L2 只会让排错更困难。

### 决策 3：Hybrid 检索——「向量 Top-N + BM25 Top-N + RRF」

**选择**：
- 新增 `content_tsv tsvector` 列 + GIN 索引，采用 `to_tsvector('simple', content)` 生成（中文用 zhparser 需要额外插件，先用 simple 覆盖英文与专有名词；中文分词放到未来变更迭代）。
- 查询侧：并行发起向量召回（Top-20，cosine）与 BM25 召回（`ts_rank_cd`, Top-20），Java 端做 RRF 融合（`score = Σ 1/(k + rank_i)`，`k=60`），取 Top-N（默认 20）。
- Feature Flag `jchatmind.rag.hybrid.enabled` 控制是否启用；关闭时退化为纯向量。

**为什么不用**：
- 只做向量：专有名词、代码符号、缩写命中率低。
- 数据库端一次 SQL 融合：会阻碍未来接入外部检索器（Elasticsearch/OpenSearch），Java 端融合更灵活。
- SPLADE：需要额外模型部署，收益边际递减，放到后续变更。

### 决策 4：Rerank——「bge-reranker-v2-m3 通过 Ollama HTTP」

**选择**：
- 用 Ollama `/api/rerank`（若不支持则 `/api/generate` 走 prompt 打分兼容方案）调用 `bge-reranker-v2-m3`。
- 输入为「query + Top-N 文本」，输出重排后取 Top-K（默认 5）。
- 超时 3s，失败降级为不 rerank 的融合结果（不阻断问答）。
- Feature Flag `jchatmind.rag.rerank.enabled` 控制开关。

**为什么不用**：
- Cohere/Voyage rerank：API 需要外网与费用，不适合当前本地化部署。
- 自己训练 cross-encoder：ROI 太低。
- LLM-as-rerank（让主模型自己打分）：又贵又慢又不稳定。

### 决策 5：引用溯源——「SSE 追加 references 事件」

**选择**：
- `ChunkBgeM3` 每条元数据固定字段：`documentId, filename, pageNumber, headingPath, chunkIndex, tokenCount`。
- Agent 完成答复后，在 SSE 流末尾发送一个事件：
  ```
  event: references
  data: [{"documentId":123,"filename":"user-guide.pdf","pageNumber":12,"headingPath":"第3章 / 3.2 权限","snippet":"..."}]
  ```
- 前端 `ChatPanel` 消费 `references` 事件渲染成"参考资料"卡片；DTO 中的 `references` 字段和 `content` 并存，历史消息也可回显。

**为什么不用**：
- 直接把 `[[source: xxx]]` 塞进主答复 prompt：占 token、模型可能改写、结构脆弱。
- 独立接口 `/chat/{id}/references` 二次拉取：需要多余一次请求，前端逻辑复杂。

### 决策 6：迁移方式——「双写不双读，一次性回填」

**选择**：
- 上线新代码后，写入路径立刻走「正文 embedding + cosine + 新元数据」新逻辑。
- 读取路径通过 `jchatmind.rag.read.mode = new | legacy | both`：
  - `both`：向量列都读，Java 端合并（迁移期默认）。
  - `new`：只读新数据（迁移完成后切）。
- 提供 `RebuildEmbeddingsRunner`（`--spring.profiles.active=rebuild-embeddings`）批量回填历史 chunk 的 embedding 与元数据。
- 索引变更 `DROP INDEX ... IF EXISTS` + `CREATE INDEX CONCURRENTLY` 保证在线可执行。

## Risks / Trade-offs

- [阈值语义变化导致召回率抖动] → 提供 `jchatmind.rag.similarity.threshold` 与 `jchatmind.rag.legacy-mode` 开关，允许灰度回退；上线前用一组固定问题跑对比。
- [Hybrid + Rerank 增加单轮延迟 200~500ms] → 向量与 BM25 并行；rerank 与 chatMemory 拼装并行；rerank 失败降级；给 SSE 前端加 `retrieving` 事件让用户感知。
- [tsvector 对中文不友好] → 本期只保证不劣化英文/代码/符号命中；中文分词插件 zhparser 放到后续变更；文档里注明当前中文侧仍主要靠向量。
- [HNSW 索引重建期间检索性能下降] → 使用 `CREATE INDEX CONCURRENTLY`；老索引保留到新索引 `pg_index.indisvalid=true` 后再删。
- [`RebuildEmbeddingsRunner` 期间占用 Ollama] → 提供 `--rate=<qps>` 限速参数；默认 5 qps；生产环境建议凌晨运行。
- [Feature Flag 数量膨胀] → 集中放到 `RagProperties`，明确"迁移完成后需要移除"的一组开关，记入 archive 时的 cleanup checklist。
- [老 chunk 元数据不全（无 pageNumber、headingPath）] → 回填时用 `filename + chunk_index` 兜底；前端引用卡片对空字段做优雅降级。

## Migration Plan

1. **准备**：在 dev 环境执行 `V2__rag_core_quality.sql`（加列 + 加 GIN 索引 + 加 HNSW cosine 索引）。
2. **发布**：先发布新代码，`jchatmind.rag.read.mode=both`、`rerank.enabled=false`、`hybrid.enabled=false`；只跑"正文 embedding + cosine 索引"这一层。跑一天，观察相似度分布。
3. **回填**：用 `RebuildEmbeddingsRunner` 批量回填历史 chunk（正文嵌入 + 元数据）。
4. **打开 Hybrid**：`hybrid.enabled=true`，观察 Top-5 命中率与延迟。
5. **打开 Rerank**：`rerank.enabled=true`，观察最终延迟；如超过 SLA，先降低 Top-N 或临时关闭。
6. **切换只读新**：`read.mode=new`，删除老的 `ivfflat` 索引与 legacy 分支代码路径（在 archive 变更时清理）。
7. **回滚方案**：任一阶段异常，把对应 Feature Flag 关掉即可；无需回滚数据库（新增列不影响旧代码）。

## Open Questions

- 是否将 `content_tsv` 改成 `to_tsvector('chinese_zh', content)`（需要 zhparser/pgroonga 支持）在本期落地？倾向"不"，但需要与部署方确认现网 PostgreSQL 是否有扩展权限。
- rerank 走 Ollama 原生 `/api/rerank` 还是自建 `/rerank` Python 微服务？取决于运维现有部署 Ollama 版本是否 ≥ 0.4（新版本支持）。
- 引用溯源的 `snippet` 字段是取原始 chunk 前 200 字，还是让模型基于问题动态摘要？倾向前者，避免又一次 LLM 调用。
