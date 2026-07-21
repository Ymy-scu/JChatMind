## 1. 检索侧：cosine 阈值真过滤与 SELECT 剪枝

- [x] 1.1 `ChunkBgeM3` 加瞬态字段 `private Double score;`（Javadoc：查询期派生字段，equals/hashCode 不参与）
- [x] 1.2 `ChunkBgeM3Mapper.xml` 新增 `SimilarityResultMap extends BaseResultMap`，映射 `distance → score`
- [x] 1.3 `similaritySearch` SELECT 去掉 `embedding` 大字段，追加 `embedding <=> #{vectorLiteral}::vector AS distance`
- [x] 1.4 `bm25Search` SELECT 去掉 `embedding` 大字段（无 distance 时 score 保持默认 null）
- [x] 1.5 `RagServiceImpl.filterByCosineThreshold`：`1 - distance ≥ threshold` 做真阈值过滤；`distance == null` 保守放过
- [x] 1.6 `RagServiceImpl.toRetrieved` 把 distance 转 similarity 传下游（BM25 无 distance 时 0.0）
- [x] 1.7 `mvn test` 通过，回归无 mapper 反序列化异常

## 2. 检索侧：Agent 检索缓存 / 并行 / 截断

- [x] 2.1 `JChatMind` 加 `private String lastRetrievalQuery;` + 常量 `PROMPT_CHUNK_CONTENT_MAX_LEN = 800`
- [x] 2.2 `ensureKnowledgeContext` 加 same-query 短路：`Objects.equals(query, lastRetrievalQuery)` 命中则跳过检索
- [x] 2.3 多 KB 并行召回：`CompletableFuture.supplyAsync(...)` 汇总，`allOf().join()`
- [x] 2.4 `formatChunkForPrompt`：单 chunk `content` 长度 > 800 时截断并追加 `……`
- [x] 2.5 命中后调 `rebuildSystemContext()` 统一重建 SystemMessage，不再 append

## 3. 索引侧：上传白名单与 multipart 上限

- [x] 3.1 `application.yaml` 新增 `spring.servlet.multipart.max-file-size: 50MB / max-request-size: 60MB`
- [x] 3.2 `application.yaml` 新增 `jchatmind.rag.upload.allowed-extensions: pdf,docx,doc,md,txt,markdown`
- [x] 3.3 `RagProperties` 新增 `Upload` 内部类 + `List<String> allowedExtensions`，`RagProperties` 顶层挂 `private Upload upload = new Upload();`
- [x] 3.4 `DocumentController.validateUpload(MultipartFile)`：空检查 + 后缀白名单校验；命中之外的抛 `IllegalArgumentException`
- [x] 3.5 `uploadDocument` 入口调 `validateUpload` 作为第一步
- [x] 3.6 `application.yaml` 的 `read.mode` 由 `both` 切到 `new`（灰度稳定后节省资源）

## 4. 索引侧：批量 embed 与 docx 兜底切分

- [x] 4.1 `RagService.embedBatch(List<String>)` default 方法：串行兜底，失败位置为 null
- [x] 4.2 `DocumentFacadeServiceImpl.embedAndInsert`：先过滤空文本，再 `embedBatch` 一次拿全量向量
- [x] 4.3 `WordParserServiceImpl.fallbackParagraphSplitDocx`：docx 完全没识别到 heading 时按段落切，软上限 1200 字符
- [x] 4.4 `PdfParserServiceImpl` 页码兜底逻辑保留

## 5. 认证上下文（新能力 auth-context）

- [x] 5.1 `JwtAuthenticationFilter` 除 `request.setAttribute("userId", ...)`，构造 `UsernamePasswordAuthenticationToken(userId, null, [ROLE_USER])`
- [x] 5.2 仅在 `SecurityContextHolder.getContext().getAuthentication() == null` 时写入
- [x] 5.3 `finally` 里 `SecurityContextHolder.clearContext()` 防线程池泄漏
- [x] 5.4 `extractToken` 加 `request.getParameter("access_token")` query 兜底（EventSource 场景）
- [x] 5.5 `SecurityConfig.anyRequest().permitAll()` 一行不动，仅新增注释说明"过渡态"

## 6. 对话记忆（新能力 chat-memory）

- [x] 6.1 `ChatMemoryCompressionService` 接口加 `void compressAsync(String sessionId, ChatClient chatClient);`
- [x] 6.2 `ChatMemoryCompressionServiceImpl.compressAsync` 用 Spring `@Async`（复用 `AsyncConfig.taskExecutor`）fire-and-forget
- [x] 6.3 注入 `ChatMessageMapper`；`compress` 尾部调 `deleteOldestExcludingRecent(sessionId, KEEP_RECENT_MESSAGES)` 清 DB 老消息
- [x] 6.4 删 DB 失败时 `WARN` 兜底，不打断压缩流程
- [x] 6.5 加常量 `MAX_COMPRESSION_CHARS = 20_000`，`buildCompressionPrompt` 尾部截断防撞 LLM context 上限
- [x] 6.6 `ChatMessageMapper.deleteOldestExcludingRecent(sessionId, keepRecent)` 接口 + XML SQL（`id NOT IN (SELECT id FROM ... ORDER BY created_at DESC LIMIT keepRecent)`）
- [x] 6.7 `JChatMind.rebuildSystemContext()` 统一 3 来源（`systemPrompt` + 最新摘要 + `currentReferences`）为一条 SystemMessage 放位置 0
- [x] 6.8 `JChatMind` 构造函数不再 append `systemPrompt`，改调 `rebuildSystemContext()`
- [x] 6.9 `JChatMind.checkAndCompress` 改调 `compressAsync` fire-and-forget
- [x] 6.10 `JChatMindFactory.loadMemory` `case SYSTEM: continue`：历史 System 一律跳过，交给 `rebuildSystemContext` 统一处理

## 7. 验证与文档

- [x] 7.1 `mvn test` 全绿：67 pass + 4 skipped Live，0 failures
- [x] 7.2 `openspec validate harden-rag-quality-followups --strict` 通过
- [ ] 7.3 手工冒烟：上传 1 篇 PDF + 5 轮对话，验证流式尾部无 3-8s 卡顿、摘要 SystemMessage 在位置 0、references 事件正常
- [ ] 7.4 手工冒烟：上传 `.exe` 后缀被 4xx 拒绝
- [ ] 7.5 手工冒烟：SSE 用 `?access_token=xxx` 建连，服务侧日志能看到 userId 写入 SecurityContext
