# JChatMind：下一代多模态认知智能体自动化中枢
> 企业级全栈智能体平台｜自主决策｜工具编排｜多模态交互｜私有化部署｜业务自动化


## 项目概述
JChatMind 是一款基于大模型原生架构深度构建的**企业级认知智能体自动化平台**，致力于构建具备自主感知、逻辑理解、动态决策、工具执行、长期记忆、自我反思与持续进化能力的全链路自治智能系统。

项目以通用人工智能（AGI）为技术导向，通过模块化引擎、插件化扩展、可视化编排、分布式调度、私有化部署、多智能体协同等能力，将大模型从单纯的对话交互升级为可独立完成复杂任务、对接业务系统、操作工具生态、处理企业数据、实现流程自动化的核心智能中枢。

平台彻底打通大模型与业务系统的壁垒，为企业与开发者提供**可定制、可扩展、可审计、可完全私有化**的下一代 AI 智能体解决方案，实现业务流程自动化、知识管理智能化、决策执行自动化、人机协作模式重构。

---

## 核心能力体系
### 🔹 高级认知智能
- 自主任务规划、逻辑推理、步骤拆解
- 动态决策、反思机制、错误修正
- 长短期记忆体系、上下文持久化管理
- 复杂意图识别、多轮对话深度管理

### 🔹 全链路工具自动化
- 标准化插件协议，支持任意第三方工具接入
- 可视化函数编排、流程自动化设计
- 数据库、API、云服务、操作系统指令深度集成
- 办公自动化、数据处理、文件操作、系统运维全支持
- 批量任务执行、定时任务、触发式自动化

### 🔹 企业级安全与架构
- 前后端分离、微服务模块化架构
- 完全私有化部署，数据零外泄
- 细粒度权限管理、操作日志审计
- 数据加密、接口限流、高可用保障
- Docker  容器化快速部署

### 🔹 多场景智能体框架
- 支持自定义智能体角色、行业知识库
- RAG 检索增强生成，企业私有知识问答
- 多智能体协同、分工合作、任务分发
- 低代码构建专属行业智能体
- 支持客服、分析、创作、运维、决策、知识管理等全场景

---

## 技术架构
JChatMind 采用现代化高可用分布式架构，具备极强的扩展性、稳定性与兼容性：
- 后端：高性能异步框架 + 模块化插件引擎 + 任务调度中心
- 前端：轻量化高交互可视化控制台 + 流程编排编辑器
- 模型层：支持本地大模型 + 云端大模型 + 多模型动态路由
- 数据层：内存记忆 + 向量数据库 + 关系型数据库
---

## 核心应用场景
- 企业智能办公自动化（OA/ERP/CRM 深度打通）
- 私有知识库问答、文档分析、知识管理
- 数据分析、报表生成、数据可视化
- 智能客服、智能助手、多轮对话系统
- 内容创作、文案生成、脚本编写、多模态生成
- 自动化运维、监控告警、服务器管理
- 金融、教育、医疗、制造、政务行业智能体
- 业务流程自动化（RPA + AI 融合）

---

## RAG 检索增强（Retrieval Augmented Generation）

JChatMind 内置一套面向企业私有知识库的 RAG 主链路，遵循"80% 收益来自 20% 功能"的取舍，聚焦 6 项核心能力：正确的 embedding、正确的距离度量、Token 感知切分、Hybrid 融合、Reranker 二次排序、引用溯源。

### 架构一览

```
用户提问
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ Agent (JChatMind)                                       │
│   ensureKnowledgeContext(query)                         │
│      └─► RagService.retrieve(kbId, query)               │
└─────────────────────────────────────────────────────────┘
           │
           ▼
┌──────────────── read.mode 分发 ────────────────┐
│  new       │ hybrid → 阈值 → rerank → Top-K     │
│  legacy    │ 仅向量召回 Top-N                    │
│  both      │ new 优先，legacy 去重补齐           │
└─────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────┐
│ Hybrid 召回（并行 CompletableFuture）                    │
│   ├─ 向量召回：bge-m3 embedding + pgvector cosine (HNSW) │
│   └─ BM25 召回：content_tsv (tsvector + GIN)             │
│                    ↓                                     │
│           Reciprocal Rank Fusion (k=60)                  │
└─────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────┐
│ Rerank：bge-reranker-v2-m3 (Ollama /api/rerank)         │
│   超时/失败自动降级为原顺序                              │
└─────────────────────────────────────────────────────────┘
           │
           ▼
       RetrievedChunk[]（含 filename / headingPath / pageNumber / chunkIndex）
           │
           ├─► System Message 注入 LLM
           └─► SSE `AI_REFERENCES` 事件 + chat_message.references JSONB 持久化
```

### 关键技术选型

| 环节 | 组件 | 说明 |
|------|------|------|
| Embedding | `bge-m3` | Ollama 本地部署，1024 维 |
| 向量存储 | `pgvector` | HNSW + `vector_cosine_ops` |
| 关键词 | PostgreSQL `tsvector` | GIN 索引，`plainto_tsquery('simple', ...)` |
| 融合 | RRF | `score = Σ 1 / (k + rank)`，`k = 60` |
| Reranker | `bge-reranker-v2-m3` / `gte-rerank-v2` | 双 provider：本地 Ollama 或阿里云 DashScope，超时 3~5s，失败降级 |
| 切分 | `TokenAwareSplitter` | 句子边界优先 + 短片段合并 + overlap |
| 元数据 | `filename / heading_path / page_number / chunk_index / token_count` | 支撑引用溯源 |

### 配置示例

`application.yaml`：

```yaml
jchatmind:
  rag:
    chunk:
      max-tokens: 512
      min-tokens: 64
      overlap: 64
    similarity:
      threshold: 0.35        # cosine similarity 阈值
    hybrid:
      enabled: true          # 关闭则退化为纯向量
      vector-top-n: 20
      bm25-top-n: 20
      final-top-n: 20
      rrf-k: 60
    rerank:
      enabled: true
      provider: dashscope           # ollama（本地）或 dashscope（阿里云百炼）
      model: gte-rerank-v2          # ollama 用 bge-reranker-v2-m3
      base-url: https://dashscope.aliyuncs.com   # ollama 换回 http://localhost:11434
      api-key: sk-xxxx              # 仅 dashscope 需要
      timeout-ms: 5000
      top-k: 5
    read:
      mode: new              # new | legacy | both（灰度迁移用）
```

Feature Flag 灰度：先 `both` → 观察 → 切 `new` → 删除 legacy 索引与代码。

### 引用溯源

每一次 AI 回答的 SSE 流末尾都会推送一个 `AI_REFERENCES` 事件，前端据此渲染"参考资料"卡片：

- `filename`：源文件名（`jmm-basics.md` 等）
- `headingPath`：面包屑，形如 `第一章 Java 内存模型 / 1.2 volatile 语义`
- `pageNumber`：PDF 场景下的起始页码
- `chunkIndex` + `documentId`：便于前端跳转与去重
- `content`：截取 200 字符预览

同一份数据会以 JSONB 形式写入 `chat_message.references`，方便回溯与审计。

### 相关文档

- 迁移手册：[`data/rag-migration-runbook.md`](./data/rag-migration-runbook.md)
- 手工验证脚本：[`data/verify-rag.md`](./data/verify-rag.md)
- 变更提案：[`openspec/changes/improve-rag-core-quality/`](./openspec/changes/improve-rag-core-quality/)

### 一次性回填（老库升级）

`embed(title)` → `embed(content)` 修复后需回填历史 embedding，以及 `filename / heading_path / page_number / chunk_index / token_count` 元数据：

```bash
# 后台跑，默认 5 qps，可通过 rag.rebuild.rate 调整
mvn spring-boot:run -Dspring-boot.run.profiles=rebuild-embeddings \
  -Dspring-boot.run.arguments="--rag.rebuild.rate=5 --rag.rebuild.page-size=200"
```

支持断点续跑：进度记录在 `data/rag-rebuild-progress.txt`，Ctrl+C 中断后再次启动会从上次的 `id` 继续。

---
