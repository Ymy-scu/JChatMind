## ADDED Requirements

### Requirement: 文档上传 SHALL 校验后缀白名单并遵循 multipart 大小上限

系统 SHALL 在 `DocumentController` 入口对 `MultipartFile` 做空检查与后缀白名单校验，命中之外的后缀 MUST 拒绝；同时通过 `spring.servlet.multipart.max-file-size` / `max-request-size` 限制单请求体积。

#### Scenario: 后缀白名单配置

- **WHEN** 运维在 `application.yaml` 设置 `jchatmind.rag.upload.allowed-extensions: pdf,docx,doc,md,txt,markdown`
- **THEN** `RagProperties.Upload.allowedExtensions` MUST 加载为该 List，`DocumentController.validateUpload` MUST 引用该配置

#### Scenario: 拒绝非白名单后缀

- **WHEN** 用户上传 `payload.exe` / `attack.jsp` / `run.sh` 等非白名单后缀文件
- **THEN** `validateUpload` MUST 抛出 `IllegalArgumentException`，经全局异常处理返回 4xx，MUST NOT 进入解析层

#### Scenario: 大小写不敏感

- **WHEN** 用户上传 `Guide.PDF` / `Note.MD`
- **THEN** 校验 MUST 大小写不敏感，允许通过

#### Scenario: multipart 上限

- **WHEN** 用户上传的文件超过 50MB 或整个 multipart 请求超过 60MB
- **THEN** Spring MUST 直接拒绝并返回 `MaxUploadSizeExceededException`，不进入 Controller

### Requirement: 文档入库 SHALL 用批量 embed 并对 docx 提供段落兜底切分

`DocumentFacadeServiceImpl.embedAndInsert` SHALL 用 `RagService.embedBatch(texts)` 一次拿全量向量，失败位置为 `null` 不影响其他 chunk 入库；`WordParserServiceImpl` SHALL 在 docx 完全无 heading 结构时按段落切分。

#### Scenario: 批量 embed 一次调用

- **WHEN** 一篇文档经切分器产出 N 个非空 chunk
- **THEN** 系统 MUST 只调用一次 `ragService.embedBatch(texts)`（其中 `texts.size() == N`），MUST NOT 在循环里串行调 `embed(...)`

#### Scenario: 单 chunk embed 失败不影响其他

- **WHEN** `embedBatch` 返回数组中位置 `i` 为 `null`
- **THEN** 系统 MUST 跳过该 chunk 并在日志记录 `documentId + chunkIndex + 原因`，继续处理其他成功位置

#### Scenario: docx heading 缺失兜底

- **WHEN** `WordParserServiceImpl` 解析 docx 后 `sections.isEmpty()`（简历、说明书、纯正文 docx）
- **THEN** 系统 MUST 调用 `fallbackParagraphSplitDocx`，按段落聚合、软上限 1200 字符切分，MUST NOT 把整篇 docx 变成单个 chunk

#### Scenario: 兜底段落 heading 派生

- **WHEN** 兜底切分产出一个 chunk
- **THEN** 该 chunk 的 `title / headingPath` MUST 取该段落首行前 30 字符（超出加 `…`），空段落取占位 `段落`
