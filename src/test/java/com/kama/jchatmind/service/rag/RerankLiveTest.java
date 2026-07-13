package com.kama.jchatmind.service.rag;

import com.kama.jchatmind.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DashScope {@code gte-rerank} 与本地 Ollama {@code bge-reranker} 的真实调用测试。
 *
 * <p>默认跳过；本地或 CI 上想跑时通过环境变量启用：</p>
 * <pre>
 *   # DashScope
 *   set RAG_RERANK_DASHSCOPE_KEY=sk-xxxx
 *   # Ollama
 *   set RAG_RERANK_OLLAMA_URL=http://localhost:11434
 * </pre>
 *
 * <p>这两个测试直接命中生产实现类 {@link BgeRerankerServiceImpl}，用于验证
 * provider 分支、请求体、响应字段绑定与超时行为在真实网络下都能跑通。</p>
 */
class RerankLiveTest {

    private RetrievedChunk c(String id, String content) {
        return RetrievedChunk.builder()
                .id(id)
                .documentId(id)
                .chunkIndex(0)
                .content(content)
                .build();
    }

    private List<RetrievedChunk> corpus() {
        return List.of(
                c("mysql", "MySQL 索引优化策略"),
                c("jmm", "Java 内存模型规定了主存与工作内存的交互规则"),
                c("redis", "Redis 集群分片方案"),
                c("volatile", "volatile 关键字保证可见性与禁止指令重排")
        );
    }

    // ------------------------------------------------------------
    // DashScope
    // ------------------------------------------------------------

    @Test
    @EnabledIfEnvironmentVariable(named = "RAG_RERANK_DASHSCOPE_KEY", matches = ".+")
    @DisplayName("DashScope gte-rerank-v2 真实调用：Java 内存模型问题应把 jmm 排最前")
    void dashscope_gteRerank_rankRelevantFirst() {
        RagProperties props = new RagProperties();
        props.getRerank().setEnabled(true);
        props.getRerank().setProvider("dashscope");
        props.getRerank().setModel("gte-rerank-v2");
        props.getRerank().setBaseUrl("https://dashscope.aliyuncs.com");
        props.getRerank().setApiKey(System.getenv("RAG_RERANK_DASHSCOPE_KEY"));
        props.getRerank().setTimeoutMs(8000);
        props.getRerank().setTopK(3);

        BgeRerankerServiceImpl svc = new BgeRerankerServiceImpl(props, WebClient.builder());
        List<RetrievedChunk> out = svc.rerank("什么是 Java 内存模型", corpus(), 3);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).getId()).isEqualTo("jmm");
        assertThat(out.get(0).getScore()).isGreaterThan(0.5);
    }

    // ------------------------------------------------------------
    // Ollama
    // ------------------------------------------------------------

    @Test
    @EnabledIfEnvironmentVariable(named = "RAG_RERANK_OLLAMA_URL", matches = ".+")
    @DisplayName("Ollama bge-reranker-v2-m3 真实调用：应把 jmm 排最前")
    void ollama_bgeReranker_rankRelevantFirst() {
        RagProperties props = new RagProperties();
        props.getRerank().setEnabled(true);
        props.getRerank().setProvider("ollama");
        props.getRerank().setModel("bge-reranker-v2-m3");
        props.getRerank().setBaseUrl(System.getenv("RAG_RERANK_OLLAMA_URL"));
        props.getRerank().setTimeoutMs(8000);
        props.getRerank().setTopK(3);

        BgeRerankerServiceImpl svc = new BgeRerankerServiceImpl(props, WebClient.builder());
        List<RetrievedChunk> out = svc.rerank("什么是 Java 内存模型", corpus(), 3);

        assertThat(out).hasSize(3);
        // 只要 jmm 或 volatile 出现在 top1 就算通过（bge-reranker 更宽容）
        assertThat(out.get(0).getId()).isIn("jmm", "volatile");
    }
}
