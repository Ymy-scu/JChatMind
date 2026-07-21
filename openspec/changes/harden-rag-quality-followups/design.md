## Context

`improve-rag-core-quality` 把 RAG 主干（cosine + HNSW + hybrid + rerank + 引用溯源）跑通之后，实际打通链路的收尾问题集中在四个地方：

1. **检索侧还差一层过滤**：SQL 已经用 `<=>` 算距离，但 Java 层没拿到 `distance` 就没法做真阈值过滤；同时 `SELECT embedding` 每次都把 4KB × TopN 的大列拉回 Java，浪费带宽。Agent 侧则会同一轮对话反复触发检索，多知识库串行召回、单 chunk 撑爆 prompt。
2. **索引侧上传兜底缺失**：`DocumentController.uploadDocument` 只判空，任意后缀都能穿到解析层；且 `embedAndInsert` 内串行调 `embed(...)`，一篇长文档常 3-4 分钟。
3. **认证上下文空转**：`JwtAuthenticationFilter` 只往 `request.attribute` 写 userId，`SecurityContextHolder` 全程为空；EventSource 又不能带 `Authorization` header，SSE 场景等于匿名。想在后续接入 `@AuthenticationPrincipal` 与资源归属校验，必须先补齐上下文承载。
4. **对话记忆 4 个隐患**：压缩同步 LLM 卡住流式尾部；压缩只清 Redis 不清 DB，`loadMemory` 从 DB 又拉回老消息导致摘要重放；`JChatMind` 每轮 append 一段 SystemMessage 到末尾，摘要被挤到 1 号位，且反复注入检索命中；`loadMemory` 里 `add(0, SystemMessage)` 会把摘要塞到 tool_calls 和 tool_response 之间，打断 Deepseek/GLM 严格校验的相邻性。

所有问题都可以在不改数据库、不 breaking 接口、不动 SecurityConfig 兜底规则的前提下修完，因此单独抽成 `harden-rag-quality-followups`。

## Goals / Non-Goals

**Goals:**
- 让 cosine 阈值过滤真的生效，SELECT 剪枝把单次召回传输量降 80%。
- 让 Agent 检索路径具备 same-query 短路、多 KB 并行、单 chunk 长度上限，避免同轮反复检索 + prompt 撑爆。
- 上传路径加后缀白名单和 multipart 上限，阻断 `.exe / .jsp` 等非文档类型。
- 用 Spring `@Async` 把压缩异步化，DB 与 Redis 同步清理防重放；`rebuildSystemContext` 统一 System 上下文语义。
- `JwtAuthenticationFilter` 补齐 `SecurityContextHolder` 写入，为后续 `.anyRequest().authenticated()` 与 `@AuthenticationPrincipal String userId` 打地基；同时用 `?access_token=` query 兜底支持 EventSource。

**Non-Goals:**
- 不收紧 `SecurityConfig.anyRequest().permitAll()`。前端和 Controller 端点还没接 `Bearer` 头，收紧会一次性把所有匿名调用打断。
- 不删除 `retrieveLegacy` / `retrieveBoth` / `legacyMode` 死路代码。这些代码留待下一个 change 与前端改造一起收敛。
- 不为 Rerank 加 Resilience4j 熔断或 Caffeine 本地缓存。放到后续 change。
- 不改动 `AsyncConfig.taskExecutor` 的核心/峰值参数（4/10/100 已够压缩场景）。

## Decisions

### 决策 1：cosine 阈值过滤在 Java 层做，不用 SQL `HAVING`

**选择**：`SimilarityResultMap extends BaseResultMap` 加一列 `distance`，Java 侧 `1 - distance ≥ threshold` 过滤，`distance == null` 保守放过。

**为什么不用 SQL `HAVING`**：
- BM25 分支没有 distance，硬统一 SQL 阈值会让 hybrid 融合前的候选数出现"偏 vector"的偏差。
- 阈值可能来自运行时 `RagProperties`，走 SQL 需要参数化传递，与现有的 `#{vectorLiteral}::vector` 混在一起可读性差。

### 决策 2：Agent 检索用"缓存 + 并行"两把小刀

**选择**：
- same-query 短路：`Objects.equals(query, lastRetrievalQuery)` 命中则跳过整个检索链路，只在 `rebuildSystemContext` 里用旧的 `currentReferences`。
- 多 KB 并行：`kbIds.stream().map(id -> CompletableFuture.supplyAsync(() -> retrieve(id, query), taskExecutor))` 然后 `allOf().join()`；执行器复用 `AsyncConfig.taskExecutor`。
- 单 chunk 截断：`PROMPT_CHUNK_CONTENT_MAX_LEN = 800`，超长追加 `……`。

**为什么不用 Caffeine 缓存**：
- Agent 单轮生命周期短（问答后就丢），缓存命中主要在"同一轮多 tool 循环"，用实例字段 `lastRetrievalQuery` 就够。
- Caffeine 需要 key 里带 kb 列表、query、topK 等多个维度，收益覆盖不了复杂度。

### 决策 3：上传白名单放 `RagProperties.upload.allowedExtensions`，不硬编码

**选择**：`application.yaml` 列白名单，`RagProperties` 加 `Upload` 内部类；`DocumentController.validateUpload` 大小写不敏感比对文件名末尾 `.` 后段。

**为什么不用 MIME type**：
- 浏览器 MIME 类型可被伪造，且 `docx` / `md` 在不同浏览器 MIME 不一致。
- 后缀白名单足够阻断 `.exe / .jsp / .sh` 等非文档类型；深度检测（magic bytes、病毒扫描）留待独立 change。

### 决策 4：`SecurityContext` 写入不覆盖上游，但 `finally` 一定清理

**选择**：
- 只在 `SecurityContextHolder.getContext().getAuthentication() == null` 时写入，允许上游 filter 已经认证的场景保留原状。
- `finally` 里无条件 `clearContext()`，避免 Tomcat/Undertow 线程池复用把身份带到下一个请求。
- token 提取顺序：Header `Authorization: Bearer` → query `?access_token=<jwt>`。query 兜底只给 EventSource，Controller 端点不建议依赖。

**为什么不做 `filterChain.doFilter` 之前和之后的双阶段清理**：
- Spring Security 官方 `SecurityContextPersistenceFilter` 已经在链路末尾做一次清理；`finally` 是双保险。
- 双阶段清理会让上游 filter 依赖 `SecurityContext` 时出现空指针。

### 决策 5：`SecurityConfig` 兜底 permitAll 一行不动

**选择**：保留 `.anyRequest().permitAll()`，只加注释说明这是"过渡态"。

**为什么不收紧**：
- 前端 `ui/src/api/http.ts` 目前不带 `Authorization` header，`ui` 里的 SSE 也没有 `?access_token=` 参数。
- Controller 端点大量用 `request.attribute` 拿 userId，还没换成 `@AuthenticationPrincipal`。
- 收紧到 `.anyRequest().authenticated()` 会一次性打断所有匿名调用；本 change 只补齐承载能力，收紧留给后续 change 做端到端联调。

### 决策 6：压缩改 `@Async`，DB 老消息同步清理

**选择**：
- `compressAsync` 直接标 `@Async`，复用 `AsyncConfig.taskExecutor`（4 核 10 峰值 100 队列）。
- `compress` 尾部调 `chatMessageMapper.deleteOldestExcludingRecent(sessionId, KEEP_RECENT_MESSAGES)`。SQL 用 `id NOT IN (SELECT id FROM ... ORDER BY created_at DESC LIMIT keepRecent)`，保留末尾窗口。
- 删失败 `WARN` 兜底，不中断压缩流程；下次压缩仍会再试。

**为什么不用 Redis Stream / 消息队列**：
- 压缩失败可容忍（下一轮消息数触发时会再压一次），异步 `@Async` 足够；引 MQ 是过度设计。

**为什么保留 `keepRecent`（默认末尾 20 条）不动**：
- `loadMemory` 依赖末尾窗口做冷启动恢复；一次性删太多会导致 Redis miss 时 DB 也拉不回上下文。

### 决策 7：`rebuildSystemContext()` 统一 System 上下文

**选择**：
- `JChatMind` 只维护 3 个 System 来源：`systemPrompt` + `currentSummary`（最新摘要）+ `currentReferences`（本轮检索结果）。
- 三者拼成一条 `SystemMessage` 放位置 0；`chatMemory.clear + add` 幂等重建，不动 USER / ASSISTANT / TOOL 顺序。
- `loadMemory` 里 `case SYSTEM: continue`：历史 SystemMessage 一律跳过，交给 `rebuildSystemContext` 统一处理。

**为什么不用 `MessageAggregator` / 多条 SystemMessage**：
- Deepseek/GLM 只把位置 0 的 SystemMessage 当真系统指令；多条 SystemMessage 只有第一条生效。
- 摘要如果作为独立 SystemMessage 用 `add(0, ...)`，会把 `systemPrompt` 挤到 1 号位，语义丢失。
- 拼成一条最直白：`systemPrompt` 在前，摘要与 references 用分节标题包裹在后。

**为什么在 `loadMemory` 里跳过历史 SystemMessage**：
- 历史 SystemMessage 会跟 `tool_calls ↔ tool_response` 混在一起，如果按顺序恢复就会打断相邻性。
- `rebuildSystemContext` 会在恢复后立刻重建位置 0 的 SystemMessage，不丢信息。

## Risks / Trade-offs

- **`SecurityContext` 写入 + 前端不带 token → 全部匿名**：本 change 只写入承载能力，`anyRequest().permitAll()` 不动，实际业务行为不变；前端接入 token 在后续 change 处理。
- **`?access_token=` query 兜底泄漏日志**：常规 Nginx / Spring 访问日志会把 query string 写进 access log，token 会明文出现。缓解：（1）日志层可加 `access_token` 参数脱敏；（2）仅 EventSource 场景使用，其他 API 走 Bearer header。
- **同 query 短路误判**：user 换了 KB 却查同一 query，短路会用旧命中。缓解：`ensureKnowledgeContext` 里同时把 `kbIds` 作为短路 key 的一部分（当前 KB 由 session 绑定，切换会重建 Agent，一般不会踩到）。
- **异步压缩 + `taskExecutor` 队列满**：极端并发下 `@Async` 会走 `AbortPolicy` 或 caller-runs。缓解：`AsyncConfig` 已经配好，压缩比对话流量少 10~50 倍，队列足够。
- **DB 老消息删除的窗口 race**：`deleteOldestExcludingRecent` 与 `insert` 并发时，`keepRecent` 窗口内的新消息不会被删，但正在插入的消息如果晚 1ms 到，可能被 `NOT IN` 命中删掉。缓解：`created_at DESC LIMIT` 只在数百条量级操作，window 内新消息一般来自当前 session 的下一轮，间隔至少几秒，实际很难踩到。

## Migration Plan

无 schema 迁移。发布流程：

1. `mvn test` 全绿后合并主干。
2. 灰度环境部署，观察：
   - `similaritySearch` SQL 日志确认 SELECT 不再含 `embedding` 列。
   - 流式尾部延迟从 3-8s 掉到 0。
   - 上传 `.exe` 被 4xx 拒绝。
   - 服务日志能看到 `Set SecurityContext for userId=xxx`。
3. 稳定 1 天后，`jchatmind.rag.read.mode` 由 `both` 切到 `new`（YAML 已经改，重启即可）。
4. 后续独立 change 再做：前端 `Bearer` 头 + `?access_token=` 拼接 + `.anyRequest().authenticated()` 收紧 + `retrieveLegacy` 死路清理。

## Open Questions

- 是否把 `keepRecent` 从 `KEEP_RECENT_MESSAGES` 常量下沉到 `application.yaml`（`jchatmind.chat.memory.keep-recent`）？倾向"下次 change 一起下沉"，本次先保持常量。
- `PROMPT_CHUNK_CONTENT_MAX_LEN = 800` 是否应该按 LLM context 上限动态计算？倾向"不"，直接常量即可；模型换了再改。
