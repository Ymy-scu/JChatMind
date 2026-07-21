package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.RagProperties;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.rag.RerankService;
import com.kama.jchatmind.service.rag.RetrievedChunk;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * {@link RagService} 的默认实现。
 *
 * <p>核心链路：</p>
 * <pre>
 *   retrieve(kbId, query)
 *     ├─ 向量召回（cosine）            ─┐
 *     │                                  ├─ RRF 融合 → 阈值过滤 → rerank → Top-K
 *     └─ BM25 召回（当 hybrid.enabled）─┘
 * </pre>
 *
 * <p>Hybrid / Rerank 通过 {@link RagProperties} 开关控制；关闭时自动退化。</p>
 */
@Slf4j
@Service
public class RagServiceImpl implements RagService {

    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final boolean embeddingEnabled;
    private final RagProperties ragProperties;
    private final RerankService rerankService;
    private final ExecutorService hybridExecutor;
    private final String embeddingModel;
    private final Duration embeddingTimeout;

    public RagServiceImpl(
            WebClient.Builder builder,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            RagProperties ragProperties,
            RerankService rerankService,
            @Qualifier("hybridExecutor") ExecutorService hybridExecutor,
            @Value("${embedding.url:}") String legacyEmbeddingUrl,
            @Value("${embedding.enabled:#{null}}") Boolean legacyEmbeddingEnabled
    ) {
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.ragProperties = ragProperties;
        this.rerankService = rerankService;
        this.hybridExecutor = hybridExecutor;

        // 优先读 RagProperties.embedding.*，兼容旧的 embedding.url / embedding.enabled 顶级配置
        RagProperties.Embedding cfg = ragProperties.getEmbedding();
        String url = (legacyEmbeddingUrl != null && !legacyEmbeddingUrl.isBlank())
                ? legacyEmbeddingUrl : cfg.getUrl();
        this.embeddingEnabled = legacyEmbeddingEnabled != null ? legacyEmbeddingEnabled : cfg.isEnabled();
        this.embeddingModel = cfg.getModel();
        this.embeddingTimeout = Duration.ofMillis(Math.max(1000, cfg.getTimeoutMs()));

        this.webClient = builder.baseUrl(url).build();
        log.info("RagService 初始化: url={}, model={}, enabled={}, timeoutMs={}",
                url, embeddingModel, embeddingEnabled, cfg.getTimeoutMs());
    }

    // ------------------------------------------------------------------
    // 基础能力：embedding
    // ------------------------------------------------------------------

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    @Override
    public float[] embed(String text) {
        if (!embeddingEnabled) {
            throw new IllegalStateException("Embedding 服务未启用");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embed 入参不能为空");
        }
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", embeddingModel,
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .timeout(embeddingTimeout)
                .block();
        if (resp == null || resp.getEmbedding() == null) {
            throw new IllegalStateException("Embedding 返回为空");
        }
        return resp.getEmbedding();
    }

    /**
     * 并行批量 embedding：用于文档入库时的 chunk 批处理。
     *
     * <p>并发度由 {@code jchatmind.rag.embedding.concurrency} 控制（默认 3）；
     * 单条超时受 {@code embedding.timeoutMs} 保护；某条失败会记录 WARN 但整批继续。</p>
     *
     * @return 与入参同长的数组；失败位置为 {@code null}
     */
    @Override
    public float[][] embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return new float[0][];
        int concurrency = Math.max(1, Math.min(texts.size(), ragProperties.getEmbedding().getConcurrency()));
        float[][] out = new float[texts.size()][];

        java.util.concurrent.Semaphore permit = new java.util.concurrent.Semaphore(concurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i++) {
            final int idx = i;
            final String t = texts.get(i);
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    permit.acquire();
                    out[idx] = embed(t);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("批量 embed 被打断: idx={}", idx);
                } catch (Exception e) {
                    log.warn("批量 embed 失败: idx={}, err={}", idx, e.getMessage());
                } finally {
                    permit.release();
                }
            }, hybridExecutor);
            futures.add(f);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return out;
    }

    // ------------------------------------------------------------------
    // 兼容旧签名
    // ------------------------------------------------------------------

    @Override
    public List<String> similaritySearch(String kbId, String query) {
        return retrieve(kbId, query).stream()
                .map(RetrievedChunk::getContent)
                .toList();
    }

    @Override
    public List<String> multiQuerySimilaritySearch(String kbId, List<String> queries, int topK) {
        // 保留多查询去重语义；每个查询走 retrieve()，最后合并去重
        LinkedHashMap<String, String> seen = new LinkedHashMap<>();
        for (String query : queries) {
            try {
                for (RetrievedChunk c : retrieve(kbId, query)) {
                    seen.putIfAbsent(idKey(c), c.getContent());
                    if (seen.size() >= topK * queries.size()) break;
                }
            } catch (Exception e) {
                log.error("查询 '{}' 检索失败", query, e);
            }
        }
        List<String> results = new ArrayList<>(seen.values());
        log.info("多查询检索完成: 查询数={}, 返回结果数={}", queries.size(), results.size());
        return results;
    }

    // ------------------------------------------------------------------
    // 一站式检索：按 read.mode 分发 new / legacy / both
    // ------------------------------------------------------------------

    @Override
    public List<RetrievedChunk> retrieve(String kbId, String query) {
        if (query == null || query.isBlank() || kbId == null || kbId.isBlank()) {
            return List.of();
        }

        String mode = ragProperties.getRead().getMode();
        if (mode == null || mode.isBlank()) mode = "new";
        mode = mode.trim().toLowerCase();

        switch (mode) {
            case "legacy":
                return retrieveLegacy(kbId, query);
            case "both":
                return retrieveBoth(kbId, query);
            case "new":
            default:
                return retrieveNew(kbId, query);
        }
    }

    /**
     * 新路径：hybrid（向量 + BM25 + RRF）→ 阈值过滤 → rerank → Top-K。
     *
     * <p>由 {@link RagProperties} 的 hybrid/rerank 子开关精细控制；两者都关闭时
     * 等价于"仅向量 Top-N"。</p>
     */
    private List<RetrievedChunk> retrieveNew(String kbId, String query) {
        int vectorTopN = ragProperties.getHybrid().getVectorTopN();
        int bm25TopN = ragProperties.getHybrid().getBm25TopN();
        int finalTopN = ragProperties.getHybrid().getFinalTopN();
        int rerankTopK = ragProperties.getRerank().getTopK();
        double threshold = ragProperties.getSimilarity().getThreshold();
        boolean hybridEnabled = ragProperties.getHybrid().isEnabled();

        // 1) 并行召回
        List<ChunkBgeM3> vectorHits;
        List<ChunkBgeM3> bm25Hits;
        if (hybridEnabled) {
            CompletableFuture<List<ChunkBgeM3>> vf = CompletableFuture.supplyAsync(
                    () -> safeVectorSearch(kbId, query, vectorTopN), hybridExecutor);
            CompletableFuture<List<ChunkBgeM3>> bf = CompletableFuture.supplyAsync(
                    () -> safeBm25Search(kbId, query, bm25TopN), hybridExecutor);
            CompletableFuture.allOf(vf, bf).join();
            vectorHits = vf.join();
            bm25Hits = bf.join();
        } else {
            vectorHits = safeVectorSearch(kbId, query, vectorTopN);
            bm25Hits = List.of();
        }

        // 2) 阈值过滤（仅向量路径带相似度语义；BM25 分数不做绝对阈值）
        List<ChunkBgeM3> vectorFiltered = filterByCosineThreshold(vectorHits, threshold);

        // 3) RRF 融合
        List<RetrievedChunk> fused = reciprocalRankFusion(
                List.of(toRetrieved(vectorFiltered), toRetrieved(bm25Hits)),
                ragProperties.getHybrid().getRrfK(),
                finalTopN);

        if (fused.isEmpty()) {
            return List.of();
        }

        // 4) rerank（内部会根据开关决定是否真正调用）
        return rerankService.rerank(query, fused, rerankTopK);
    }

    /**
     * 旧路径：仅向量召回 Top-N，不做 BM25、不做 RRF、不做 rerank。
     *
     * <p>用于迁移期回归对比；生产环境切换到 {@code new} 后可以移除此分支。</p>
     */
    private List<RetrievedChunk> retrieveLegacy(String kbId, String query) {
        int topN = ragProperties.getHybrid().getFinalTopN();
        List<ChunkBgeM3> hits = safeVectorSearch(kbId, query, topN);
        if (hits.isEmpty()) {
            log.debug("[legacy] 向量召回为空: kbId={}, query={}", kbId, query);
            return List.of();
        }
        log.debug("[legacy] 向量召回 {} 条: kbId={}, query={}", hits.size(), kbId, query);
        return toRetrieved(hits);
    }

    /**
     * 双读：new 结果优先，legacy 按 {@code (docId, chunkIndex)} 去重补齐至 finalTopN。
     *
     * <p>迁移窗口临时能力，会同时消耗新旧两路的资源；线上验证平稳后应切回 {@code new}。</p>
     */
    private List<RetrievedChunk> retrieveBoth(String kbId, String query) {
        int finalTopN = ragProperties.getHybrid().getFinalTopN();
        List<RetrievedChunk> primary = retrieveNew(kbId, query);
        List<RetrievedChunk> fallback = retrieveLegacy(kbId, query);

        LinkedHashMap<String, RetrievedChunk> merged = new LinkedHashMap<>();
        for (RetrievedChunk c : primary) {
            merged.putIfAbsent(idKey(c), c);
        }
        for (RetrievedChunk c : fallback) {
            if (merged.size() >= finalTopN) break;
            merged.putIfAbsent(idKey(c), c);
        }
        log.debug("[both] new={} legacy={} merged={}", primary.size(), fallback.size(), merged.size());
        return new ArrayList<>(merged.values()).subList(0, Math.min(finalTopN, merged.size()));
    }

    // ------------------------------------------------------------------
    // 内部：召回 + 融合
    // ------------------------------------------------------------------

    private List<ChunkBgeM3> safeVectorSearch(String kbId, String query, int topN) {
        try {
            float[] vec = embed(query);
            return chunkBgeM3Mapper.similaritySearch(kbId, toPgVectorLiteral(vec), topN);
        } catch (Exception e) {
            log.warn("向量召回失败: kbId={}, query={}, err={}", kbId, query, e.getMessage());
            return List.of();
        }
    }

    private List<ChunkBgeM3> safeBm25Search(String kbId, String query, int topN) {
        try {
            return chunkBgeM3Mapper.bm25Search(kbId, query, topN);
        } catch (Exception e) {
            log.warn("BM25 召回失败: kbId={}, query={}, err={}", kbId, query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 用 {@code chunk.score}（SQL 返回的 cosine distance ∈ [0, 2]）过滤。
     * {@code similarity = 1 - distance}；threshold 越大越严。
     *
     * <p>兼容：若 score 为 null（旧调用路径 / mock），保守不砍。</p>
     */
    private List<ChunkBgeM3> filterByCosineThreshold(List<ChunkBgeM3> hits, double threshold) {
        if (hits.isEmpty() || threshold <= 0.0) return hits;
        List<ChunkBgeM3> kept = new ArrayList<>(hits.size());
        int dropped = 0;
        for (ChunkBgeM3 c : hits) {
            Double dist = c.getScore();
            if (dist == null) {
                kept.add(c);
                continue;
            }
            double sim = 1.0 - dist;
            if (sim >= threshold) {
                kept.add(c);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.debug("cosine 阈值过滤: 输入={}, 保留={}, 丢弃={}, threshold={}",
                    hits.size(), kept.size(), dropped, threshold);
        }
        return kept;
    }

    private List<RetrievedChunk> toRetrieved(List<ChunkBgeM3> chunks) {
        List<RetrievedChunk> out = new ArrayList<>(chunks.size());
        for (ChunkBgeM3 c : chunks) {
            // 有 distance 就换算成相似度，方便下游展示；BM25 路径 score 为 null → 0.0
            double sim = c.getScore() == null ? 0.0 : Math.max(0.0, 1.0 - c.getScore());
            out.add(RetrievedChunk.builder()
                    .id(c.getId())
                    .documentId(c.getDocId())
                    .filename(c.getFilename())
                    .pageNumber(c.getPageNumber())
                    .headingPath(c.getHeadingPath())
                    .chunkIndex(c.getChunkIndex())
                    .content(c.getContent())
                    .score(sim)
                    .build());
        }
        return out;
    }

    /**
     * RRF：{@code score = Σ 1 / (k + rank_i)}。
     *
     * <p>{@code rankedLists} 中允许 {@code null}/空列表；只要有一个非空即可产出结果。</p>
     */
    List<RetrievedChunk> reciprocalRankFusion(List<List<RetrievedChunk>> rankedLists, int k, int limit) {
        Map<String, RetrievedChunk> byKey = new HashMap<>();
        Map<String, Double> scoreByKey = new HashMap<>();

        for (List<RetrievedChunk> list : rankedLists) {
            if (list == null || list.isEmpty()) continue;
            for (int rank = 0; rank < list.size(); rank++) {
                RetrievedChunk c = list.get(rank);
                String key = idKey(c);
                double add = 1.0 / (k + rank + 1);
                scoreByKey.merge(key, add, Double::sum);
                byKey.putIfAbsent(key, c);
            }
        }
        if (byKey.isEmpty()) return List.of();

        List<RetrievedChunk> out = new ArrayList<>();
        scoreByKey.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .forEach(e -> {
                    RetrievedChunk c = byKey.get(e.getKey());
                    c.setScore(e.getValue());
                    out.add(c);
                });
        return out;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String idKey(RetrievedChunk c) {
        return (c.getDocumentId() == null ? "-" : c.getDocumentId())
                + "#" + (c.getChunkIndex() == null ? -1 : c.getChunkIndex());
    }

    /**
     * 把 {@code float[]} 转成 pgvector 文本字面量。
     *
     * <p>虽然 {@link com.kama.jchatmind.typehandler.PgVectorTypeHandler} 已在
     * insert/select 场景内自动处理，但 {@code similaritySearch} 需要把向量作为
     * WHERE/ORDER BY 里的字面量传入，MyBatis 不会走 TypeHandler，所以仍需在
     * 这里做一次显式转换。</p>
     */
    private static String toPgVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(',');
        }
        sb.append(']');
        return sb.toString();
    }
}
