## Why

`improve-rag-core-quality` 完成主干 RAG 升级后，新的召回 / 压缩 / 上传链路暴露了几个"零 breaking、快速修复"的短板：

- SQL 层返回相似度但 Java 层没拿到 `distance`，`filterByCosineThreshold` 从未真正过滤。
- `similaritySearch` / `bm25Search` 每一次都把 `embedding` 大列（≈4KB）拉回 Java，占 80% 传输开销。
- `DocumentController` 只校验空文件、不校验后缀，任意 `.exe / .jsp` 都能穿到解析层。
- `ChatMemoryCompressionService` 同步跑 LLM，卡住流式响应尾部 3~8s；且只清 Redis 不清 DB，下轮 `loadMemory` 从 DB 拉回老消息，与摘要重复堆叠。
- `JChatMind` 每轮 append 一段 SystemMessage 到 `chatMemory` 末尾，导致摘要位置漂到 1 号位、多次检索命中重复注入，还会打断 `tool_calls ↔ tool_response` 的相邻性。
- `JwtAuthenticationFilter` 只往 `HttpServletRequest.attribute` 写 userId，`SecurityContextHolder` 全程为空；EventSource 又无法带 `Authorization` header，SSE 场景实际是"匿名"。

这些都是"改一行/一段就能立即生效"的收尾项，抽成独立 change 便于回溯与灰度。

## What Changes

### 检索侧（rag-retrieval 扩展）

- Mapper `similaritySearch` 结果映射改成 `SimilarityResultMap extends BaseResultMap`，携带 `distance` 列；`bm25Search` 无 distance 时 `score=0`。
- `RagServiceImpl.filterByCosineThreshold` 用 `1 - distance ≥ threshold` 做真阈值过滤；`distance == null` 保守放过（BM25 分支）。
- `similaritySearch` / `bm25Search` SELECT 去掉 `embedding` 大字段，`similaritySearch` 只多加一列 `embedding <=> #{vectorLiteral}::vector AS distance`。
- `ChunkBgeM3` 加瞬态字段 `Double score`（Javadoc 标注"查询期派生字段"），`equals/hashCode` 不参与。
- `JChatMind.ensureKnowledgeContext` 加 `lastRetrievalQuery` same-query 短路 + 多 KB `CompletableFuture.supplyAsync` 并行召回 + `PROMPT_CHUNK_CONTENT_MAX_LEN=800` 单 chunk 截断（超长补 `……`）。

### 索引侧（rag-indexing 扩展）

- `application.yaml` 新增 `spring.servlet.multipart.max-file-size=50MB / max-request-size=60MB`。
- `application.yaml` 新增 `jchatmind.rag.upload.allowed-extensions=pdf,docx,doc,md,txt,markdown`；`read.mode` 灰度稳定后由 `both` → `new`。
- `RagProperties` 新增 `Upload` 内部类 + `allowedExtensions` 字段（默认值同上）。
- `DocumentController.validateUpload(MultipartFile)`：空检查 + 后缀白名单，命中之外的后缀直接 `IllegalArgumentException`，走全局异常返回 4xx。
- `DocumentFacadeServiceImpl` 改成 `ragService.embedBatch(texts)` 一次拿全量向量，失败位置为 `null` 不影响其他 chunk 入库。
- `WordParserServiceImpl` 加 `fallbackParagraphSplitDocx`：docx 完全没识别到 heading 时按段落 + 软上限 1200 字符切分，避免整篇文档变成一个 chunk。
- `PdfParserServiceImpl` 沿用页码兜底逻辑。

### 认证上下文（auth-context，新能力）

- `JwtAuthenticationFilter` 除 `request.setAttribute("userId", ...)` 外，构造 `UsernamePasswordAuthenticationToken(userId, null, [ROLE_USER])` 写入 `SecurityContextHolder`；仅在 context 为空时写入，避免覆盖上游认证结果。
- `finally` 里 `SecurityContextHolder.clearContext()` 防线程池复用造成身份泄漏。
- `extractToken` 加 `?access_token=<jwt>` query 兜底（专给 EventSource；浏览器 `EventSource` 无法带自定义 header）。
- `SecurityConfig.anyRequest().permitAll()` 保留（仅新增注释说明这是"过渡态"），本变更不动兜底规则，避免连锁破坏前端。

### 对话记忆（chat-memory，新能力）

- `ChatMemoryCompressionService` 接口增加 `void compressAsync(String sessionId, ChatClient chatClient);`。
- `ChatMemoryCompressionServiceImpl.compressAsync` 用 Spring `@Async`（复用 `AsyncConfig.taskExecutor`，4 核 10 峰值 100 队列）fire-and-forget。
- `compress` 尾部调 `chatMessageMapper.deleteOldestExcludingRecent(sessionId, KEEP_RECENT_MESSAGES)` 删 DB 老消息，只保留末尾窗口；失败 `WARN` 兜底不中断。
- `buildCompressionPrompt` 尾部按 `MAX_COMPRESSION_CHARS=20_000` 截断，防撞 LLM context 上限。
- `JChatMind.rebuildSystemContext()` 统一 3 个 System 来源（`systemPrompt` + 最新摘要 + `currentReferences`）为一条 `SystemMessage` 放位置 0；`chatMemory.clear + add` 幂等重建，不动 USER / ASSISTANT / TOOL 顺序。
- `JChatMindFactory.loadMemory` `case SYSTEM: continue`：历史 System 消息一律跳过，交给 `rebuildSystemContext` 统一处理，保证 `tool_calls ↔ tool_response` 相邻性不被摘要/系统消息打断。
- `ChatMessageMapper.deleteOldestExcludingRecent(sessionId, keepRecent)`：`id NOT IN (SELECT ... ORDER BY created_at DESC LIMIT keepRecent)`。

## Capabilities

### New Capabilities

- `auth-context`：认证上下文承载能力。JWT 过滤器统一把 userId 写入 `SecurityContextHolder`，并支持 EventSource 场景下的 `?access_token=` query 兜底；线程池复用场景下 finally 清理防泄漏。为后续收紧 `.anyRequest().authenticated()` 打好地基。
- `chat-memory`：对话记忆管理能力。异步压缩、DB / Redis 一致清理、统一 SystemMessage 位置语义；保证 `tool_calls ↔ tool_response` 相邻性不被摘要/系统消息打断，同时把流式响应尾部的压缩延迟从同步 3-8s 降为异步 0。

### Modified Capabilities

- `rag-retrieval`：补齐 cosine 阈值真过滤、SELECT 剪枝、Agent 侧检索缓存/并行/截断。
- `rag-indexing`：补齐上传后缀白名单与 multipart 大小上限、`embedBatch` 并发化、docx 兜底段落切分。

## Impact

- **代码**：18 个文件，`+642 / −104`。
  - 检索侧：`RagService.java`、`RagServiceImpl.java`、`ChunkBgeM3.java`、`ChunkBgeM3Mapper.xml`、`JChatMind.java`、`JChatMindFactory.java`。
  - 索引侧：`RagProperties.java`、`DocumentController.java`、`DocumentFacadeServiceImpl.java`、`PdfParserServiceImpl.java`、`WordParserServiceImpl.java`、`application.yaml`。
  - 认证：`JwtAuthenticationFilter.java`、`SecurityConfig.java`。
  - 记忆：`ChatMemoryCompressionService.java`、`ChatMemoryCompressionServiceImpl.java`、`ChatMessageMapper.java`、`ChatMessageMapper.xml`。
- **数据库**：无迁移。全部兼容 `V2__rag_core_quality.sql` 现有 schema。
- **依赖**：无新增 Maven 依赖；复用 Spring `@Async` + 现有 `AsyncConfig.taskExecutor`。
- **兼容性**：全部零 breaking。`SecurityConfig` 兜底 `.anyRequest().permitAll()` 一行不改；`legacyMode` / `retrieveBoth` / `retrieveLegacy` 死路代码留待后续 change 清理。
- **性能**：
  - SELECT 剪枝节省单次召回 ~80% 网络传输（`embedding` 列 1024 × float ≈ 4KB × TopN）。
  - 多 KB 并行召回把 N 个知识库的串行 `N × T` 降到 `max(T)`。
  - 同 query 短路避免同一轮对话多次触发相同检索。
  - 异步压缩让流式响应尾部 3-8s 卡顿降到 0（用户无感）。
- **安全性**：
  - 上传白名单阻断 `.exe / .jsp / .sh` 等非文档类型；不改现有 `SecurityConfig` 规则。
  - `SecurityContext` 写入为后续基于身份的资源归属校验（`@AuthenticationPrincipal String userId`）铺路，本变更不启用授权收紧。
