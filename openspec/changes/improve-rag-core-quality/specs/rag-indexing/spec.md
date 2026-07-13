## ADDED Requirements

### Requirement: 文档入库时对最终返回的正文进行嵌入

系统 SHALL 在文档切分入库阶段，对每个 chunk 最终存入 `chunk_bge_m3.content` 字段的正文本身生成 embedding，禁止使用标题或其他非返回内容作为嵌入来源。

#### Scenario: Markdown 文档正文嵌入
- **WHEN** 用户上传一个带 heading 结构的 Markdown 文档，系统解析出若干 chunk
- **THEN** 每个 chunk 的 `embedding` MUST 由 `RagService.embed(chunk.content)` 生成，且 `chunk.content` 与生成 embedding 时使用的字符串完全一致

#### Scenario: PDF 文档正文嵌入
- **WHEN** 用户上传一个 PDF 文档，`PdfParserService` 输出 chunk 列表
- **THEN** 每个 chunk 的 `embedding` MUST 由 chunk 的正文（而非页面标题或文件名）生成

#### Scenario: 空正文兜底
- **WHEN** 解析出的 chunk 正文为空字符串或仅包含空白字符
- **THEN** 系统 MUST 跳过该 chunk，不写入向量表，并在日志中记录 `documentId + chunk_index + 原因`

### Requirement: 系统提供基于 Token 的二次切分器

系统 SHALL 在解析器输出结构化 chunk 之后，统一执行一次基于 token 的二次切分，保证任意 chunk 的 token 数不超过配置上限。

#### Scenario: 超长 chunk 二次切分
- **WHEN** 解析器输出的单个 chunk 估算 token 数超过 `jchatmind.rag.chunk.max-tokens`（默认 512）
- **THEN** 系统 MUST 按句子边界（`。！？；.!?;\n`）将其切分为多个子 chunk，且每个子 chunk 的 token 数 ≤ 上限

#### Scenario: 相邻短片段合并
- **WHEN** 相邻 chunk 属于同一 `headingPath` 且各自 token 数均低于 `jchatmind.rag.chunk.min-tokens`（默认 64）
- **THEN** 系统 SHALL 将其合并为一个 chunk，直到累计 token 数达到 `min-tokens` 或遇到新的 heading

#### Scenario: Overlap 上下文保留
- **WHEN** 一个原始 chunk 被二次切分为多个子 chunk
- **THEN** 除第一个子 chunk 外，每个子 chunk 的开头 SHALL 包含上一个子 chunk 末尾 `overlap` 个 token（默认 64）的内容

### Requirement: 每个 chunk 记录检索所需的完整元数据

系统 SHALL 在 `chunk_bge_m3` 表中为每个 chunk 记录 `filename`、`page_number`、`heading_path`、`chunk_index`、`token_count` 字段，用于后续排序、过滤与引用溯源。

#### Scenario: PDF chunk 记录页码
- **WHEN** PDF 文档被解析并切分入库
- **THEN** 每个 chunk 的 `page_number` MUST 反映其在源 PDF 中的实际起始页；无法确定时置为 NULL 而非 0

#### Scenario: Markdown chunk 记录 headingPath
- **WHEN** Markdown chunk 位于 `# 第一章 > ## 1.2 权限` 的 heading 结构中
- **THEN** 该 chunk 的 `heading_path` MUST 存储为 `第一章 / 1.2 权限`（层级之间以 ` / ` 分隔）

#### Scenario: chunk_index 单调递增
- **WHEN** 同一 `documentId` 下有若干 chunk 入库
- **THEN** 各 chunk 的 `chunk_index` MUST 从 0 开始按入库顺序单调递增
