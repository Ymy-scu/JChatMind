package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.RagProperties;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.rag.RerankService;
import com.kama.jchatmind.service.rag.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagServiceImpl#retrieve} 单元测试。
 *
 * <p>用 {@link ExchangeFunction} 桩化 Ollama {@code /api/embeddings} 响应，用 Mockito 桩化
 * {@link ChunkBgeM3Mapper}，脱离数据库与远端模型。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>{@code read.mode=new} + hybrid off：仅向量召回</li>
 *   <li>{@code read.mode=new} + hybrid on：向量 + BM25 并行召回，RRF 融合</li>
 *   <li>{@code read.mode=legacy}：仅向量，不调用 BM25/rerank</li>
 *   <li>{@code read.mode=both}：new + legacy 去重合并</li>
 *   <li>非法输入：空 query / 空 kbId 返回空</li>
 *   <li>向量召回异常：不抛出，返回空/降级</li>
 * </ul>
 */
class RagServiceImplRetrieveTest {

    private ChunkBgeM3Mapper mapper;
    private RagProperties properties;
    private ExecutorService hybridExecutor;
    private RerankService passthroughRerank;

    @BeforeEach
    void setUp() {
        mapper = mock(ChunkBgeM3Mapper.class);
        properties = new RagProperties();
        hybridExecutor = Executors.newSingleThreadExecutor();
        // 直通 rerank：不改变顺序，只截 topK
        passthroughRerank = (q, list, k) -> list.subList(0, Math.min(k, list.size()));
    }

    /**
     * 构造一个总是返回 1024 维零向量的 WebClient（够用来跑通 embed 调用）。
     */
    private WebClient.Builder stubEmbeddingBuilder() {
        ExchangeFunction ef = request -> Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(buildEmbeddingJson(8))
                .build());
        return WebClient.builder().exchangeFunction(ef);
    }

    private String buildEmbeddingJson(int dim) {
        StringBuilder sb = new StringBuilder("{\"embedding\":[");
        for (int i = 0; i < dim; i++) {
            if (i > 0) sb.append(',');
            sb.append("0.1");
        }
        sb.append("]}");
        return sb.toString();
    }

    private RagServiceImpl newService() {
        return newService(stubEmbeddingBuilder());
    }

    private RagServiceImpl newService(WebClient.Builder builder) {
        return new RagServiceImpl(
                builder,
                mapper,
                properties,
                passthroughRerank,
                hybridExecutor,
                "http://localhost:11434",
                true
        );
    }

    private ChunkBgeM3 chunk(String id, String docId, int idx, String content) {
        return ChunkBgeM3.builder()
                .id(id)
                .docId(docId)
                .chunkIndex(idx)
                .content(content)
                .build();
    }

    // ------------------------------------------------------------
    // 输入合法性
    // ------------------------------------------------------------

    @Test
    @DisplayName("空 query 或空 kbId 直接返回空列表，不调用 mapper")
    void retrieve_blankInputs_returnsEmpty() {
        RagServiceImpl svc = newService();

        assertThat(svc.retrieve("kb1", "")).isEmpty();
        assertThat(svc.retrieve("kb1", "   ")).isEmpty();
        assertThat(svc.retrieve("kb1", null)).isEmpty();
        assertThat(svc.retrieve("", "q")).isEmpty();
        assertThat(svc.retrieve(null, "q")).isEmpty();

        verify(mapper, never()).similaritySearch(anyString(), anyString(), anyInt());
        verify(mapper, never()).bm25Search(anyString(), anyString(), anyInt());
    }

    // ------------------------------------------------------------
    // new 模式
    // ------------------------------------------------------------

    @Test
    @DisplayName("new 模式 + hybrid 关闭：仅向量召回，不查 BM25")
    void retrieve_newMode_hybridDisabled_vectorOnly() {
        properties.getRead().setMode("new");
        properties.getHybrid().setEnabled(false);
        properties.getRerank().setEnabled(false);
        properties.getRerank().setTopK(5);
        properties.getHybrid().setFinalTopN(5);

        when(mapper.similaritySearch(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        chunk("c1", "d1", 0, "Java 内存模型"),
                        chunk("c2", "d2", 1, "Spring Boot")
                ));

        List<RetrievedChunk> out = newService().retrieve("kb1", "什么是 JMM");

        assertThat(out).hasSize(2);
        assertThat(out).extracting(RetrievedChunk::getDocumentId).containsExactly("d1", "d2");
        verify(mapper, times(1)).similaritySearch(anyString(), anyString(), anyInt());
        verify(mapper, never()).bm25Search(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("new 模式 + hybrid 开启：向量 + BM25 并行召回，RRF 融合")
    void retrieve_newMode_hybridEnabled_fusesBothPaths() {
        properties.getRead().setMode("new");
        properties.getHybrid().setEnabled(true);
        properties.getHybrid().setVectorTopN(5);
        properties.getHybrid().setBm25TopN(5);
        properties.getHybrid().setFinalTopN(5);
        properties.getRerank().setEnabled(false);
        properties.getRerank().setTopK(5);

        ChunkBgeM3 shared = chunk("shared", "d1", 0, "Java 内存模型");
        when(mapper.similaritySearch(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(shared, chunk("v-only", "d2", 1, "Spring")));
        when(mapper.bm25Search(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(shared, chunk("b-only", "d3", 2, "MySQL")));

        List<RetrievedChunk> out = newService().retrieve("kb1", "JMM");

        assertThat(out).extracting(RetrievedChunk::getDocumentId)
                .contains("d1", "d2", "d3");
        // 共同命中的 d1 应排最前（两路 rank 0 → 分数最高）
        assertThat(out.get(0).getDocumentId()).isEqualTo("d1");

        verify(mapper, times(1)).similaritySearch(anyString(), anyString(), anyInt());
        verify(mapper, times(1)).bm25Search(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("new 模式：两路都空返回空列表")
    void retrieve_newMode_bothPathsEmpty_returnsEmpty() {
        properties.getRead().setMode("new");
        properties.getHybrid().setEnabled(true);

        when(mapper.similaritySearch(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(mapper.bm25Search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        assertThat(newService().retrieve("kb1", "q")).isEmpty();
    }

    @Test
    @DisplayName("new 模式：rerank 开启时调用 RerankService 并截 topK")
    void retrieve_newMode_rerankEnabled_callsRerank() {
        properties.getRead().setMode("new");
        properties.getHybrid().setEnabled(false);
        properties.getRerank().setEnabled(true);
        properties.getRerank().setTopK(2);

        when(mapper.similaritySearch(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        chunk("c1", "d1", 0, "A"),
                        chunk("c2", "d2", 1, "B"),
                        chunk("c3", "d3", 2, "C"),
                        chunk("c4", "d4", 3, "D")
                ));

        AtomicInteger rerankCalls = new AtomicInteger();
        RerankService counting = (q, list, k) -> {
            rerankCalls.incrementAndGet();
            return list.subList(0, Math.min(k, list.size()));
        };

        RagServiceImpl svc = new RagServiceImpl(
                stubEmbeddingBuilder(), mapper, properties, counting,
                hybridExecutor, "http://localhost:11434", true);

        List<RetrievedChunk> out = svc.retrieve("kb1", "q");
        assertThat(out).hasSize(2);
        assertThat(rerankCalls.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------
    // legacy 模式
    // ------------------------------------------------------------

    @Test
    @DisplayName("legacy 模式：仅向量召回，不查 BM25，不 rerank")
    void retrieve_legacyMode_vectorOnly_noBm25NoRerank() {
        properties.getRead().setMode("legacy");
        // 即便 hybrid/rerank 打开，legacy 也应绕过它们
        properties.getHybrid().setEnabled(true);
        properties.getRerank().setEnabled(true);

        when(mapper.similaritySearch(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        chunk("c1", "d1", 0, "A"),
                        chunk("c2", "d2", 1, "B")
                ));

        AtomicInteger rerankCalls = new AtomicInteger();
        RerankService counting = (q, list, k) -> {
            rerankCalls.incrementAndGet();
            return list;
        };
        RagServiceImpl svc = new RagServiceImpl(
                stubEmbeddingBuilder(), mapper, properties, counting,
                hybridExecutor, "http://localhost:11434", true);

        List<RetrievedChunk> out = svc.retrieve("kb1", "q");

        assertThat(out).hasSize(2);
        verify(mapper, times(1)).similaritySearch(anyString(), anyString(), anyInt());
        verify(mapper, never()).bm25Search(anyString(), anyString(), anyInt());
        assertThat(rerankCalls.get()).isZero();
    }

    // ------------------------------------------------------------
    // both 模式
    // ------------------------------------------------------------

    @Test
    @DisplayName("both 模式：new 优先，legacy 按 (docId, chunkIndex) 去重补齐")
    void retrieve_bothMode_mergesNewAndLegacy_dedup() {
        properties.getRead().setMode("both");
        properties.getHybrid().setEnabled(false);
        properties.getRerank().setEnabled(false);
        properties.getRerank().setTopK(10);
        properties.getHybrid().setFinalTopN(10);

        // 第一次调用（new 路径）返回 d1；第二次调用（legacy 路径）返回 d1 + d2
        when(mapper.similaritySearch(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(chunk("c1", "d1", 0, "A")))
                .thenReturn(List.of(
                        chunk("c1", "d1", 0, "A"),
                        chunk("c2", "d2", 1, "B")
                ));

        List<RetrievedChunk> out = newService().retrieve("kb1", "q");

        // d1 只保留一次，d2 从 legacy 补齐
        assertThat(out).extracting(RetrievedChunk::getDocumentId)
                .containsExactly("d1", "d2");
        verify(mapper, times(2)).similaritySearch(anyString(), anyString(), anyInt());
    }

    // ------------------------------------------------------------
    // 异常降级
    // ------------------------------------------------------------

    @Test
    @DisplayName("向量召回抛异常：不向上抛，new 模式退化为 BM25 结果")
    void retrieve_vectorFails_gracefulDegrade() {
        properties.getRead().setMode("new");
        properties.getHybrid().setEnabled(true);
        properties.getRerank().setEnabled(false);
        properties.getRerank().setTopK(5);
        properties.getHybrid().setFinalTopN(5);

        when(mapper.similaritySearch(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("db down"));
        when(mapper.bm25Search(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(chunk("c1", "d1", 0, "A")));

        List<RetrievedChunk> out = newService().retrieve("kb1", "q");

        assertThat(out).extracting(RetrievedChunk::getDocumentId).containsExactly("d1");
    }
}
