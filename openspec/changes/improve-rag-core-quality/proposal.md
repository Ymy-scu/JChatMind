## Why

当前 JChatMind 的 RAG 链路存在几个"低成本、高影响"的短板，直接决定检索质量：向量嵌入的是标题而不是正文导致召回严重错位；距离算子与索引不匹配（`<->`/`vector_l2_ops` 用在 bge-m3 上，官方推荐 cosine）；切分仅按结构、无 token 上限与 overlap；无重排、无关键词兜底、无引用溯源。这些是"20% 的工作换来 80% 的检索收益"的关键点，先修补它们，再谈更高级的 GraphRAG/Agentic Retrieval 才有意义。

## What Changes

- **BREAKING** 向量表 `chunk_bge_m3` 索引由 `ivfflat (embedding vector_l2_ops)` 迁移到 `hnsw (embedding vector_cosine_ops)`；SQL 中距离算子由 `<->` 改为 `<=>`。旧数据需要重建索引。
- **BREAKING** 修正嵌入内容：`DocumentFacadeServiceImpl` 中原来 `ragService.embed(title)` 改为对最终入库 `content` 做嵌入，历史数据需要执行一次性回填脚本 `rebuild-embeddings`。
- 新增结构化切分器：在现有 Markdown/PDF/Word 解析结果之上，加一层基于 token 的二次切分（默认 512 token / overlap 64），保证单 chunk 不超模型上限。
- 新增 chunk 元数据：`filename`、`page_number`、`heading_path`（面包屑）、`chunk_index`、`token_count`，供检索排序与引用溯源使用。
- 新增 Hybrid Retrieval：向量召回 + PostgreSQL `tsvector` BM25 关键词召回，使用 Reciprocal Rank Fusion（RRF, k=60）融合 Top-N。
- 新增 Reranker：接入 bge-reranker-v2-m3（通过 Ollama 或 HTTP），对融合后的 Top-N（默认 20）二次排序取 Top-K（默认 5）。
- 新增引用溯源：`/chat` 流式响应在最终答复末尾追加 `references` 事件，携带 `documentId / filename / pageNumber / headingPath / snippet`；前端可据此高亮来源。
- 相似度阈值、Top-N、Top-K、rerank 开关全部下沉到 `application.yaml` 的 `jchatmind.rag.*`，运行时可调。

## Capabilities

### New Capabilities
- `rag-indexing`: 文档从解析到入库的索引侧能力，包含结构感知 + token 二次切分、正文嵌入、chunk 元数据写入、cosine 向量索引结构。
- `rag-retrieval`: 查询侧能力，包含向量召回（cosine）、BM25 关键词召回、RRF 融合、可配置的阈值与 Top-N/Top-K。
- `rag-reranking`: 基于 cross-encoder（bge-reranker-v2-m3）的二次排序能力，作为检索链路中的可选可切换阶段。
- `rag-citation`: 面向答复的引用溯源能力，检索结果附带足以还原来源位置的元数据，并通过 SSE `references` 事件透出到前端。

### Modified Capabilities
<!-- 当前 openspec/specs/ 为空，本次全部为新增能力，无 Modified Capabilities。 -->

## Impact

- **代码**：
  - `service/impl/DocumentFacadeServiceImpl.java`：调整入库前的切分与嵌入逻辑，写入新元数据。
  - `service/impl/RagServiceImpl.java`：新增 `similaritySearchHybrid`、`rerank` 方法，重构 similarity 计算走 cosine。
  - `mapper/ChunkBgeM3Mapper.xml`：SQL 由 `<->` 改为 `<=>`，新增 BM25 查询语句与 RRF 融合入口。
  - `agent/JChatMind.java` / `ChatMessageFacadeServiceImpl`：拼装 references 事件，串联到 SSE 输出。
  - `config/RagConfig.java`（新增）：暴露阈值/TopN/TopK/rerank 开关配置。
- **数据库**：
  - `chunk_bge_m3` 新增列 `filename`、`page_number`、`heading_path`、`chunk_index`、`token_count`、`content_tsv tsvector`。
  - 新增 GIN 索引 `idx_chunk_content_tsv`；重建 HNSW cosine 索引 `idx_chunk_embedding_cosine`。
  - 提供迁移脚本 `V2__rag_core_quality.sql` 与回填程序 `RebuildEmbeddingsRunner`。
- **依赖**：无新增 Maven 依赖；reranker 通过 HTTP 调用 Ollama 已有部署。
- **兼容性**：老 chunk 在回填完成前会存在"标题嵌入 + L2 索引"的历史数据；迁移期通过 Feature Flag `jchatmind.rag.legacy-mode` 双写双读，避免中断。
- **性能**：Hybrid + Rerank 会给每次问答增加约 200~500ms LLM/HTTP 开销，通过异步预取与结果缓存缓解。
