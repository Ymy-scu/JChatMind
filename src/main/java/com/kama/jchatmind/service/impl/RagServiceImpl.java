package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RagServiceImpl implements RagService {

    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final boolean enabled;

    public RagServiceImpl(
            WebClient.Builder builder,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            @Value("${embedding.url:http://localhost:11434}") String embeddingUrl,
            @Value("${embedding.enabled:true}") boolean enabled
    ) {
        this.webClient = builder.baseUrl(embeddingUrl).build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.enabled = enabled;
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    private float[] doEmbed(String text) {
        if (!enabled) {
            throw new RuntimeException("Embedding 服务未启用");
        }
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", "bge-m3",
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        if (resp == null || resp.getEmbedding() == null) {
            throw new RuntimeException("Embedding 返回为空");
        }
        return resp.getEmbedding();
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        return multiQuerySimilaritySearch(kbId, List.of(title), 3);
    }

    @Override
    public List<String> multiQuerySimilaritySearch(String kbId, List<String> queries, int topK) {
        Set<String> seenContent = new LinkedHashSet<>();

        for (String query : queries) {
            try {
                String queryEmbedding = toPgVector(doEmbed(query));
                List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, topK);
                for (ChunkBgeM3 chunk : chunks) {
                    seenContent.add(chunk.getContent());
                }
            } catch (Exception e) {
                log.error("查询 '{}' 检索失败", query, e);
            }
        }

        List<String> results = new ArrayList<>(seenContent);
        log.info("多查询检索完成: 查询数={}, 返回结果数={}", queries.size(), results.size());
        return results;
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
