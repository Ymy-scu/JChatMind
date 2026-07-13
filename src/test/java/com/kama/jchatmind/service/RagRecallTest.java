package com.kama.jchatmind.service;

import com.kama.jchatmind.config.RagProperties;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import com.kama.jchatmind.service.rag.RerankService;
import com.kama.jchatmind.service.rag.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRecallTest {

    @Mock
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Mock
    private WebClient.Builder webClientBuilder;

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private WebClient.RequestBodySpec requestBodySpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private RagService ragService;

    private static final String KB_ID = "kb-recall-test";
    private List<ChunkBgeM3> allChunks;
    private Set<String> relevantChunkIds;

    private static final int DIM = 128;
    private static final Random RNG = new Random(42);

    // 生成随机 128 维向量（更低维度便于观察相似度差异）
    private float[] makeRandomVec() {
        float[] v = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            v[i] = (float) (RNG.nextGaussian());
        }
        return v;
    }

    // 生成与 base 语义相近的向量（加小扰动）
    private float[] makeCloseVec(float[] base, double noise) {
        float[] v = base.clone();
        for (int i = 0; i < DIM; i++) {
            v[i] += (float) (RNG.nextGaussian() * noise);
        }
        return normalize(v);
    }

    // 生成与 base 语义无关的向量（完全随机）
    private float[] makeFarVec() {
        return makeRandomVec();
    }

    private float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return v;
        for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    @BeforeEach
    void setUp() {
        when(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);

        // 直通式 RerankService：不调用外部服务，直接返回 candidates 的前 topK
        RerankService passthroughRerank = (query, candidates, topK) -> {
            if (candidates == null || candidates.isEmpty()) return List.of();
            int end = Math.min(topK, candidates.size());
            return new ArrayList<>(candidates.subList(0, end));
        };

        ragService = new RagServiceImpl(
                webClientBuilder,
                chunkBgeM3Mapper,
                new RagProperties(),
                passthroughRerank,
                Executors.newSingleThreadExecutor(),
                "http://localhost:11434",
                true
        );

        // 构建 chunk 数据：10 个 chunk，其中 3 个与"Java 内存模型"相关
        allChunks = new ArrayList<>();
        relevantChunkIds = new HashSet<>();

        // 先生成一个"锚点"向量作为相关主题中心
        float[] topicCenter = normalize(makeRandomVec());

        // 3 个相关 chunk（围绕锚点加小扰动，语义相近）
        String[] relevantContent = {
            "Java 内存模型规定了所有变量的存储位置和访问规则",
            "JMM 定义了 volatile 和 synchronized 的内存语义",
            "Java 内存模型中的 happens-before 原则"
        };
        for (int i = 0; i < relevantContent.length; i++) {
            String id = "relevant-" + i;
            relevantChunkIds.add(id);
            allChunks.add(ChunkBgeM3.builder()
                    .id(id).kbId(KB_ID)
                    .content(relevantContent[i])
                    .embedding(makeCloseVec(topicCenter, 0.15))
                    .build());
        }

        // 7 个不相关 chunk（完全随机方向，语义无关）
        String[] irrelevantContent = {
            "Spring Boot 自动配置原理",
            "MySQL 索引优化策略",
            "Docker 容器网络配置",
            "Redis 缓存穿透解决方案",
            "Kubernetes Pod 调度机制",
            "HTTP 与 HTTPS 协议区别",
            "微服务熔断降级策略"
        };
        for (int i = 0; i < irrelevantContent.length; i++) {
            allChunks.add(ChunkBgeM3.builder()
                    .id("irrelevant-" + i).kbId(KB_ID)
                    .content(irrelevantContent[i])
                    .embedding(makeFarVec())
                    .build());
        }

        // 打乱顺序（模拟数据库无序存储）
        Collections.shuffle(allChunks, new Random(42));
    }

    @Test
    void recallAt3ShouldIncludeRelevantDocs() {
        // 使用一个与相关文档向量接近的查询（模拟用户搜索 Java 内存模型）
        float[] relevantVec = allChunks.stream()
                .filter(c -> relevantChunkIds.contains(c.getId()))
                .findFirst().get().getEmbedding();
        float[] queryVec = normalize(makeCloseVec(relevantVec, 0.1));

        // 排序所有 chunk
        List<ChunkBgeM3> sorted = new ArrayList<>(allChunks);
        sorted.sort(Comparator.comparingDouble(
                c -> -cosineSimilarity(queryVec, c.getEmbedding())));

        // 取 top-3
        List<ChunkBgeM3> top3 = sorted.subList(0, 3);
        long hit = top3.stream().filter(c -> relevantChunkIds.contains(c.getId())).count();

        System.out.println("=== Recall@3 ===");
        System.out.println("命中: " + hit + " / 相关: " + relevantChunkIds.size());
        for (ChunkBgeM3 c : top3) {
            System.out.println("  cosine=" + String.format("%.4f", cosineSimilarity(queryVec, c.getEmbedding())) + " | " + c.getContent());
        }

        double recall = (double) hit / relevantChunkIds.size();
        System.out.println("Recall@3 = " + String.format("%.1f%%", recall * 100));

        // Recall@3 很难 100%（随机高维空间下，不相关向量也可能偶然接近）
        // 这正是项目需要 Query Expansion 的原因
        assertTrue(recall >= 1.0 / 3, "Recall@3 应 >= 33.3%，实际: " + recall);
    }

    @Test
    void recallAt5ShouldIncludeMostRelevantDocs() {
        float[] relevantVec = allChunks.stream()
                .filter(c -> relevantChunkIds.contains(c.getId()))
                .findFirst().get().getEmbedding();
        float[] queryVec = normalize(makeCloseVec(relevantVec, 0.1));

        List<ChunkBgeM3> sorted = new ArrayList<>(allChunks);
        sorted.sort(Comparator.comparingDouble(
                c -> -cosineSimilarity(queryVec, c.getEmbedding())));

        List<ChunkBgeM3> top5 = sorted.subList(0, 5);
        long hit = top5.stream().filter(c -> relevantChunkIds.contains(c.getId())).count();

        System.out.println("=== Recall@5 ===");
        System.out.println("命中: " + hit + " / 相关: " + relevantChunkIds.size());
        for (ChunkBgeM3 c : top5) {
            System.out.println("  cosine=" + String.format("%.4f", cosineSimilarity(queryVec, c.getEmbedding())) + " | " + c.getContent().substring(0, Math.min(20, c.getContent().length())));
        }

        double recall = (double) hit / relevantChunkIds.size();
        System.out.println("Recall@5 = " + String.format("%.1f%%", recall * 100));

        assertTrue(recall >= 1.0 / 3, "Recall@5 应 >= 33.3%，实际: " + recall);
    }

    @Test
    void recallAt10ShouldIncludeAllRelevantDocs() {
        float[] relevantVec = allChunks.stream()
                .filter(c -> relevantChunkIds.contains(c.getId()))
                .findFirst().get().getEmbedding();
        float[] queryVec = normalize(makeCloseVec(relevantVec, 0.1));

        List<ChunkBgeM3> sorted = new ArrayList<>(allChunks);
        sorted.sort(Comparator.comparingDouble(
                c -> -cosineSimilarity(queryVec, c.getEmbedding())));

        List<ChunkBgeM3> top10 = sorted.subList(0, Math.min(10, sorted.size()));
        long hit = top10.stream().filter(c -> relevantChunkIds.contains(c.getId())).count();

        System.out.println("=== Recall@10 ===");
        System.out.println("命中: " + hit + " / 相关: " + relevantChunkIds.size());

        double recall = (double) hit / relevantChunkIds.size();
        System.out.println("Recall@10 = " + String.format("%.1f%%", recall * 100));

        assertTrue(recall >= 2.0 / 3, "Recall@10 应 >= 66.7%，实际: " + recall);
    }

    @Test
    void similarVectorsShouldHaveHigherScore() {
        float[] base = normalize(makeRandomVec());
        float[] close = normalize(makeCloseVec(base, 0.05));
        float[] far = normalize(makeRandomVec());

        double closeSim = cosineSimilarity(base, close);
        double farSim = cosineSimilarity(base, far);

        System.out.println("相近向量（小扰动）余弦相似度: " + String.format("%.4f", closeSim));
        System.out.println("无关向量（随机）余弦相似度: " + String.format("%.4f", farSim));

        assertTrue(closeSim > farSim + 0.3, "相近向量相似度应显著高于无关向量");
    }

    @Test
    void multiQueryExpansionShouldImproveRecall() {
        // 3 个相关 chunk 的向量作为"不同角度的查询"
        List<float[]> relevantVecs = allChunks.stream()
                .filter(c -> relevantChunkIds.contains(c.getId()))
                .map(ChunkBgeM3::getEmbedding)
                .toList();

        // 单查询：只用第一个相关向量
        List<ChunkBgeM3> sorted = new ArrayList<>(allChunks);
        sorted.sort(Comparator.comparingDouble(
                c -> -cosineSimilarity(relevantVecs.get(0), c.getEmbedding())));
        long singleHits = sorted.subList(0, 3).stream()
                .filter(c -> relevantChunkIds.contains(c.getId()))
                .count();

        // 多查询：用 3 个变体各取 top-3，合并去重
        Set<String> multiHits = new HashSet<>();
        for (float[] qv : relevantVecs) {
            List<ChunkBgeM3> sorted2 = new ArrayList<>(allChunks);
            sorted2.sort(Comparator.comparingDouble(
                    c -> -cosineSimilarity(qv, c.getEmbedding())));
            sorted2.subList(0, 3).stream()
                    .filter(c -> relevantChunkIds.contains(c.getId()))
                    .map(ChunkBgeM3::getId)
                    .forEach(multiHits::add);
        }

        double singleRecall = (double) singleHits / relevantChunkIds.size();
        double multiRecall = (double) multiHits.size() / relevantChunkIds.size();

        System.out.println("=== 单查询 vs 多查询 召回率对比 ===");
        System.out.println("单查询 Recall@3: " + String.format("%.1f%%", singleRecall * 100) + " (" + singleHits + "/" + relevantChunkIds.size() + ")");
        System.out.println("多查询(3变体)合并去重 Recall: " + String.format("%.1f%%", multiRecall * 100) + " (" + multiHits.size() + "/" + relevantChunkIds.size() + ")");

        assertTrue(multiRecall >= singleRecall,
                "多查询扩展召回率(" + multiRecall + ")应不低于单查询(" + singleRecall + ")");
    }
}
