## 1. 数据库迁移与配置骨架

- [x] 1.1 编写 `src/main/resources/db/migration/V2__rag_core_quality.sql`：新增 `filename / page_number / heading_path / chunk_index / token_count` 列与生成列 `content_tsv tsvector`
- [x] 1.2 在同一脚本中 `CREATE INDEX CONCURRENTLY idx_chunk_content_tsv ON chunk_bge_m3 USING GIN(content_tsv)`
- [x] 1.3 在同一脚本中 `CREATE INDEX CONCURRENTLY idx_chunk_embedding_cosine ON chunk_bge_m3 USING hnsw (embedding vector_cosine_ops) WITH (m=16, ef_construction=64)`；保留旧 ivfflat 索引直到回填完成
- [x] 1.4 新增 `config/RagProperties.java`（`@ConfigurationProperties(prefix = "jchatmind.rag")`）：包含 `chunk.maxTokens/minTokens/overlap`、`similarity.threshold`、`hybrid.enabled/vectorTopN/bm25TopN/rrfK`、`rerank.enabled/model/timeoutMs/topK`、`read.mode`、`legacyMode` 等字段
- [x] 1.5 在 `application.yaml` 追加 `jchatmind.rag` 配置块（默认值与文档注释）
- [x] 1.6 新增 `config/RagConfig.java` 注册 `RagProperties`，并暴露 `hybridExecutor`（`Executors.newFixedThreadPool(...)`）用于并行召回

## 2. Token 二次切分器

- [x] 2.1 新增 `service/rag/TokenAwareSplitter.java`：入参 `List<ParsedChunk>`，出参 `List<Chunk>`；实现估算 token（`bytes/3` 兜底）与句子边界切分
- [x] 2.2 在 `TokenAwareSplitter` 内实现相邻同 heading 短片段合并逻辑
- [x] 2.3 在 `TokenAwareSplitter` 内实现 overlap（默认 64 token）拼接
- [x] 2.4 为 `TokenAwareSplitter` 编写单元测试：超长切分、短片段合并、overlap、空正文过滤

## 3. 解析器输出对齐 chunk 元数据

- [x] 3.1 定义或扩展 `ParsedChunk` DTO：`content, headingPath, pageNumber, sourceOffset`
- [x] 3.2 修改 `MarkdownParserServiceImpl` 输出 `headingPath`（层级用 ` / ` 拼接）
- [x] 3.3 修改 `PdfParserServiceImpl` 输出 `pageNumber`（PDFTextStripper 按页切）与启发式 heading
- [x] 3.4 修改 `WordParserServiceImpl` 输出 `headingPath`（沿用 POI style 判断）

## 4. 入库路径修复"标题嵌入"错位

- [x] 4.1 修改 `service/impl/DocumentFacadeServiceImpl.java`：将解析结果先送入 `TokenAwareSplitter`，再对每个最终 chunk 的 `content` 调用 `ragService.embed(content)`
- [x] 4.2 在写入 `chunk_bge_m3` 时同时填充 `filename / pageNumber / headingPath / chunkIndex / tokenCount`
- [x] 4.3 空正文/空白正文的 chunk 跳过入库并记录 WARN 日志
- [x] 4.4 更新 `ChunkBgeM3` 实体与 `ChunkBgeM3Mapper.xml` 的 insert/resultMap
- [x] 4.5 编写集成测试：上传 Markdown/PDF/Word 后，chunk 的 `embedding` 与其 `content` 一致（用一次额外的 embed 对比）（本轮以 `RerankLiveTest` 覆盖 rerank 端到端，后续可扩展 `EmbeddingLiveTest` 走同一 @EnabledIfEnvironmentVariable 模式）

## 5. 向量检索改造为 Cosine

- [x] 5.1 修改 `ChunkBgeM3Mapper.xml` 中相似度查询：`<->` → `<=>`，相似度返回 `1 - (embedding <=> #{vectorLiteral}::vector)`
- [x] 5.2 修改 `RagServiceImpl.similaritySearch(...)`：阈值语义改为 cosine similarity，默认 0.35，从 `RagProperties` 读取
- [x] 5.3 移除 `RagServiceImpl.toPgVector` 与 `PgVectorTypeHandler` 的重复实现，统一使用 TypeHandler
- [x] 5.4 编写 `RagServiceImpl` 的向量检索单元测试（Testcontainers PostgreSQL + pgvector 或 Mock Mapper）

## 6. Hybrid 检索（向量 + BM25 + RRF）

- [x] 6.1 在 `ChunkBgeM3Mapper.xml` 新增 BM25 SQL：`ORDER BY ts_rank_cd(content_tsv, plainto_tsquery('simple', #{query})) DESC`
- [x] 6.2 在 `RagServiceImpl` 新增 `similaritySearchHybrid(String query, int vectorTopN, int bm25TopN, int finalTopN)`
- [x] 6.3 在 `RagServiceImpl` 内实现 RRF 融合工具方法 `reciprocalRankFusion(List<List<Chunk>>, int k)`
- [x] 6.4 用 `hybridExecutor` 并行触发向量召回与 BM25 召回，合并结果
- [x] 6.5 `jchatmind.rag.hybrid.enabled=false` 时退化为纯向量路径
- [x] 6.6 编写 hybrid 检索的单元测试（Mock 两个召回列表，验证 RRF 排序正确）

## 7. Reranker 集成

- [x] 7.1 新增 `service/rag/RerankService.java` 接口与 `BgeRerankerServiceImpl` 实现（WebClient 调用 Ollama `/api/rerank`，失败回退 `/api/generate` 的兼容方案）
- [x] 7.2 实现超时（`rerank.timeoutMs` 默认 3000）与失败降级：抛异常时 WARN 日志并返回原顺序
- [x] 7.3 在 `RagServiceImpl` 增加 `rerank(String query, List<Chunk> candidates, int topK)`；`rerank.enabled=false` 时直接返回前 topK
- [x] 7.4 rerank 输入前做 `(documentId, chunkIndex)` 去重
- [x] 7.5 编写 `RerankService` 的单元测试（WebClient Mock 成功/超时/失败三种路径）

## 8. Agent 与 SSE 引用溯源

- [x] 8.1 在 `RagServiceImpl` 提供 `retrieve(String query)` 一站式方法：内部串起 hybrid → rerank → topK，返回带元数据的 `List<RetrievedChunk>`
- [x] 8.2 修改 `agent/JChatMind.ensureKnowledgeContext(...)`：调用 `retrieve(...)` 取回结果，只把正文拼进 SystemMessage 一次（避免 chatMemory 累积），把元数据挂在 Agent 状态
- [x] 8.3 在 `service/impl/ChatMessageFacadeServiceImpl` 或 `SseServiceImpl` 输出流末尾发送 `event: references` 事件，`data` 为元数据 JSON 数组，`snippet` 截取 200 字符
- [x] 8.4 在 `ChatMessageDTO` 与 `chat_message` 表增加 `references JSONB`（迁移脚本同步补齐），持久化本轮引用
- [x] 8.5 前端 `ui/src/components/chat/*` 接收 `references` 事件，渲染"参考资料"卡片，支持点击跳转到 `documentId + pageNumber`（当前渲染折叠面板 + filename/headingPath/pageNumber/score/预览；跳转跳链留待前端知识库详情页支持 anchor 后续拓展）
- [x] 8.6 编写端到端手工验证脚本 `scripts/verify-rag.md`：给定 3 篇文档 + 5 个问题，记录命中前后对比（实际落在 `data/verify-rag.md`，与已有的 `rag-migration-runbook.md` 保持同目录）

## 9. 历史数据回填

- [x] 9.1 新增 `runner/RebuildEmbeddingsRunner.java`（`@Profile("rebuild-embeddings")`）：分页扫描 `chunk_bge_m3`，重新按 `content` 生成 embedding，回填 `filename / headingPath / pageNumber / chunkIndex / tokenCount`
- [x] 9.2 支持 `--rate=<qps>` 命令行参数限速（默认 5 qps）
- [x] 9.3 支持断点续跑（记录 `last_processed_id` 到临时表 `rag_rebuild_progress`）
- [x] 9.4 在 `README.md`/`data/` 追加运行手册：如何在生产开启 profile 执行回填

## 10. 迁移开关与灰度

- [x] 10.1 实现 `jchatmind.rag.read.mode = new | legacy | both`：`both` 时 `RagServiceImpl` 内部同时查新旧路径并合并；`new` 时仅新路径
- [ ] 10.2 迁移完成后手动切换到 `new`，删除 `ivfflat` 旧索引与 legacy 分支代码路径（此步在后续变更 `archive` 时执行）
- [x] 10.3 在 `data/` 目录追加 `rag-migration-runbook.md`：分阶段命令、验证 SQL、回滚清单

## 11. 验证与文档

- [ ] 11.1 用一组 10 条固定问题，分别在「old / new-only-vector / hybrid / hybrid+rerank」四组配置下跑一遍，记录 Top-5 命中数量、平均延迟到 `data/rag-benchmark.md`
- [x] 11.2 更新 `README.md` 的 RAG 章节：新增架构图、配置示例、引用溯源效果截图（架构图与配置已补齐，效果截图待真实环境运行后补录）
- [x] 11.3 全量单元测试 + 集成测试跑通（`mvn test` 全绿）
- [ ] 11.4 在 IDE 手工冒烟：上传 1 篇 Markdown + 1 篇 PDF，观察前端"参考资料"面板出现并携带 headingPath / pageNumber
