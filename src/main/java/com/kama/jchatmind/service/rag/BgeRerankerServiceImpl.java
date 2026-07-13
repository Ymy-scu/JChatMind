package com.kama.jchatmind.service.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kama.jchatmind.config.RagProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * bge-reranker-v2-m3 / DashScope gte-rerank 的实现，按
 * {@link RagProperties.Rerank#getProvider()} 分派：
 * <ul>
 *   <li>{@code ollama}（默认）：调用本地 Ollama {@code POST /api/rerank}</li>
 *   <li>{@code dashscope}：调用阿里云百炼 {@code POST /api/v1/services/rerank/text-rerank/text-rerank}</li>
 * </ul>
 *
 * <p>失败降级策略：任何异常（超时、非 2xx、模型不支持）都记录 WARN 日志并
 * 直接返回候选的前 {@code topK}，保证 Agent 主链路不因 rerank 中断。</p>
 */
@Slf4j
@Service
public class BgeRerankerServiceImpl implements RerankService {

    private final RagProperties properties;
    private final WebClient webClient;

    public BgeRerankerServiceImpl(RagProperties properties, WebClient.Builder builder) {
        this.properties = properties;
        this.webClient = builder.baseUrl(properties.getRerank().getBaseUrl()).build();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // 去重：同一 (documentId, chunkIndex) 只保留第一次出现
        List<RetrievedChunk> deduped = dedup(candidates);
        int effectiveTopK = Math.min(topK, deduped.size());

        if (!properties.getRerank().isEnabled()) {
            return deduped.subList(0, effectiveTopK);
        }

        List<String> documents = new ArrayList<>(deduped.size());
        for (RetrievedChunk c : deduped) {
            documents.add(c.getContent() == null ? "" : c.getContent());
        }

        try {
            List<RerankItem> items = callProvider(query, documents, effectiveTopK);
            if (items == null || items.isEmpty()) {
                return deduped.subList(0, effectiveTopK);
            }
            List<RetrievedChunk> reranked = new ArrayList<>(effectiveTopK);
            for (RerankItem item : items) {
                int idx = item.getIndex();
                if (idx < 0 || idx >= deduped.size()) continue;
                RetrievedChunk c = deduped.get(idx);
                c.setScore(item.getRelevanceScore());
                reranked.add(c);
                if (reranked.size() >= effectiveTopK) break;
            }
            if (reranked.isEmpty()) {
                return deduped.subList(0, effectiveTopK);
            }
            return reranked;
        } catch (Exception e) {
            log.warn("Rerank 异常，降级为原顺序", e);
            return deduped.subList(0, effectiveTopK);
        }
    }

    // ------------------------------------------------------------
    // Provider 分发
    // ------------------------------------------------------------

    private List<RerankItem> callProvider(String query, List<String> documents, int topK) {
        String provider = properties.getRerank().getProvider();
        if (provider == null || provider.isBlank()) provider = "ollama";
        provider = provider.trim().toLowerCase();

        switch (provider) {
            case "dashscope":
                return callDashScope(query, documents, topK);
            case "ollama":
            default:
                return callOllama(query, documents, topK);
        }
    }

    /**
     * Ollama {@code /api/rerank}：请求体 {@code {model, query, documents, top_n}}，
     * 响应 {@code {results:[{index, relevance_score}]}}。
     */
    private List<RerankItem> callOllama(String query, List<String> documents, int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getRerank().getModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", topK);

        OllamaRerankResponse resp = webClient.post()
                .uri("/api/rerank")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OllamaRerankResponse.class)
                .timeout(Duration.ofMillis(properties.getRerank().getTimeoutMs()))
                .onErrorResume(e -> {
                    log.warn("Rerank[ollama] 调用失败，降级为原顺序: {}", e.getMessage());
                    return Mono.empty();
                })
                .block();
        return resp == null ? null : resp.getResults();
    }

    /**
     * DashScope {@code /api/v1/services/rerank/text-rerank/text-rerank}：
     * <pre>
     *   Headers: Authorization: Bearer &lt;api-key&gt;
     *   Body:    { model, input: { query, documents }, parameters: { top_n, return_documents:false } }
     *   Resp:    { output: { results: [ { index, relevance_score } ] } }
     * </pre>
     */
    private List<RerankItem> callDashScope(String query, List<String> documents, int topK) {
        String apiKey = properties.getRerank().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Rerank[dashscope] 未配置 api-key，降级为原顺序");
            return null;
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", documents);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("top_n", topK);
        parameters.put("return_documents", false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getRerank().getModel());
        body.put("input", input);
        body.put("parameters", parameters);

        DashScopeRerankResponse resp = webClient.post()
                .uri("/api/v1/services/rerank/text-rerank/text-rerank")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(DashScopeRerankResponse.class)
                .timeout(Duration.ofMillis(properties.getRerank().getTimeoutMs()))
                .onErrorResume(e -> {
                    log.warn("Rerank[dashscope] 调用失败，降级为原顺序: {}", e.getMessage());
                    return Mono.empty();
                })
                .block();
        return resp == null || resp.getOutput() == null ? null : resp.getOutput().getResults();
    }

    private List<RetrievedChunk> dedup(List<RetrievedChunk> candidates) {
        Map<String, RetrievedChunk> seen = new LinkedHashMap<>();
        for (RetrievedChunk c : candidates) {
            String key = (c.getDocumentId() == null ? "-" : c.getDocumentId())
                    + "#" + (c.getChunkIndex() == null ? -1 : c.getChunkIndex());
            seen.putIfAbsent(key, c);
        }
        return new ArrayList<>(seen.values());
    }

    // ------------------------------------------------------------
    // Provider 响应结构（宽容解析：允许字段缺失）
    // ------------------------------------------------------------

    /** Ollama {@code /api/rerank} 响应。 */
    @Data
    public static class OllamaRerankResponse {
        private List<RerankItem> results;
    }

    /** DashScope {@code text-rerank} 响应，results 嵌在 {@code output} 下。 */
    @Data
    public static class DashScopeRerankResponse {
        private DashScopeOutput output;
    }

    @Data
    public static class DashScopeOutput {
        private List<RerankItem> results;
    }

    /**
     * 统一 rerank 结果项：既覆盖 Ollama、也覆盖 DashScope。
     * 二者都是 {@code index + relevance_score}（snake_case）。
     */
    @Data
    public static class RerankItem {
        private int index;

        /**
         * 服务端返回字段是 snake_case {@code relevance_score}，
         * Jackson 默认按 camelCase 映射会丢分数，这里显式绑定。
         */
        @JsonProperty("relevance_score")
        private double relevanceScore;
    }

    // ---- 兼容旧引用（RagServiceImpl.retrieveTest 等外部单测直接引用了 RerankResponse） ----
    /** @deprecated 保留兼容名，指向 {@link OllamaRerankResponse}。 */
    @Deprecated
    public static class RerankResponse extends OllamaRerankResponse {}
}
