package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 相关配置项。
 *
 * <p>集中管理 chunking、检索、rerank、迁移开关等参数，方便通过
 * {@code application.yaml} 的 {@code jchatmind.rag.*} 前缀热配置。</p>
 */
@Data
@ConfigurationProperties(prefix = "jchatmind.rag")
public class RagProperties {

    /** Embedding 服务参数 */
    private Embedding embedding = new Embedding();

    /** 切分策略参数 */
    private Chunk chunk = new Chunk();

    /** 相似度阈值 */
    private Similarity similarity = new Similarity();

    /** Hybrid 检索开关与参数 */
    private Hybrid hybrid = new Hybrid();

    /** Rerank 开关与参数 */
    private Rerank rerank = new Rerank();

    /** 读取模式（new / legacy / both），支持灰度迁移 */
    private Read read = new Read();

    /** 上传相关（后缀白名单等） */
    private Upload upload = new Upload();

    /** 兼容旧行为的开关（迁移完成后应删除） */
    private boolean legacyMode = false;

    @Data
    public static class Embedding {
        /** Embedding 服务 base URL；本地 Ollama 默认 http://localhost:11434 */
        private String url = "http://localhost:11434";
        /** Embedding 模型名 */
        private String model = "bge-m3";
        /** 单次 embed 请求超时（毫秒），避免 Ollama 挂时全链路阻塞 */
        private int timeoutMs = 8000;
        /** 是否启用 embedding（关闭后 embed() 抛异常，测试/降级场景使用） */
        private boolean enabled = true;
        /**
         * 文档入库时并行 embedding 的并发度。
         * 本地 Ollama 单机 2-4 已经够用，过高会打爆 GPU 显存或队列。
         */
        private int concurrency = 3;
    }

    @Data
    public static class Chunk {
        /** 单个 chunk 的最大 token 数 */
        private int maxTokens = 512;
        /** 单个 chunk 的最小 token 数（低于此值会与相邻同 heading chunk 合并） */
        private int minTokens = 64;
        /** 相邻子 chunk 的 overlap token 数 */
        private int overlap = 64;
    }

    @Data
    public static class Similarity {
        /** cosine 相似度阈值，低于此值的召回结果会被过滤 */
        private double threshold = 0.35;
    }

    @Data
    public static class Hybrid {
        /** 是否启用 hybrid（向量 + BM25 + RRF）检索 */
        private boolean enabled = false;
        /** 向量召回 Top-N */
        private int vectorTopN = 20;
        /** BM25 召回 Top-N */
        private int bm25TopN = 20;
        /** 融合后返回的 Top-N */
        private int finalTopN = 20;
        /** RRF 平滑常数 k，通常取 60 */
        private int rrfK = 60;
    }

    @Data
    public static class Rerank {
        /** 是否启用 rerank */
        private boolean enabled = false;
        /**
         * rerank 服务提供方。
         * <ul>
         *   <li>{@code ollama}：本地 Ollama 部署的 bge-reranker-v2-m3，走 {@code /api/rerank}</li>
         *   <li>{@code dashscope}：阿里云百炼 gte-rerank，走 {@code /api/v1/services/rerank/text-rerank/text-rerank}</li>
         * </ul>
         */
        private String provider = "ollama";
        /** rerank 模型名 */
        private String model = "bge-reranker-v2-m3";
        /** rerank HTTP 服务地址（Ollama 默认 http://localhost:11434；DashScope 用 https://dashscope.aliyuncs.com） */
        private String baseUrl = "http://localhost:11434";
        /** DashScope 等云服务的 API Key（Ollama 本地部署可留空） */
        private String apiKey = "";
        /** rerank 调用超时（毫秒） */
        private int timeoutMs = 3000;
        /** 最终返回的 Top-K */
        private int topK = 5;
    }

    @Data
    public static class Read {
        /** 读模式：new = 仅新路径，legacy = 仅旧路径，both = 迁移期同时读 */
        private String mode = "both";
    }

    @Data
    public static class Upload {
        /**
         * 允许上传的文件后缀白名单（不带点，大小写不敏感）。
         * 默认覆盖 PDF/Word/Markdown/纯文本；yaml 中可用逗号分隔覆盖。
         */
        private java.util.List<String> allowedExtensions = java.util.List.of(
                "pdf", "docx", "doc", "md", "txt", "markdown"
        );
    }
}
