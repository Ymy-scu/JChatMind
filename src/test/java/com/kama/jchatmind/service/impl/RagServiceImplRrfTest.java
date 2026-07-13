package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.RagProperties;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.service.rag.RerankService;
import com.kama.jchatmind.service.rag.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link RagServiceImpl#reciprocalRankFusion} 单元测试。
 *
 * <p>只覆盖融合排序算法本身，构造 mock 的 mapper / webClient / rerank 以脱离 IO。</p>
 */
class RagServiceImplRrfTest {

    private RagServiceImpl service;

    @BeforeEach
    void setUp() {
        ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
        RerankService rerank = (q, list, k) -> list.subList(0, Math.min(k, list.size()));
        service = new RagServiceImpl(
                WebClient.builder(),
                mapper,
                new RagProperties(),
                rerank,
                Executors.newSingleThreadExecutor(),
                "http://localhost:11434",
                false
        );
    }

    private RetrievedChunk c(String docId, int idx) {
        return RetrievedChunk.builder()
                .documentId(docId)
                .chunkIndex(idx)
                .content("doc=" + docId + " idx=" + idx)
                .build();
    }

    @Test
    @DisplayName("两路命中相同 chunk 时 RRF 分数相加，排名靠前")
    void rrf_bothListsHitSameChunk_ranksFirst() {
        RetrievedChunk shared = c("d1", 0);
        RetrievedChunk vOnly = c("d2", 1);
        RetrievedChunk bOnly = c("d3", 2);

        List<RetrievedChunk> vector = List.of(shared, vOnly);
        List<RetrievedChunk> bm25 = List.of(shared, bOnly);

        List<RetrievedChunk> fused = service.reciprocalRankFusion(
                List.of(vector, bm25), 60, 10);

        assertThat(fused).hasSize(3);
        // shared 在两路都是 rank 0 → score = 2/61；其它单路 rank 0 → 1/61
        assertThat(fused.get(0).getDocumentId()).isEqualTo("d1");
        assertThat(fused.get(0).getScore()).isGreaterThan(fused.get(1).getScore());
    }

    @Test
    @DisplayName("单路命中时排名与原顺序一致")
    void rrf_singleList_preservesOrder() {
        List<RetrievedChunk> vector = List.of(c("d1", 0), c("d2", 1), c("d3", 2));

        List<RetrievedChunk> fused = service.reciprocalRankFusion(
                List.of(vector, List.of()), 60, 10);

        assertThat(fused).extracting(RetrievedChunk::getDocumentId)
                .containsExactly("d1", "d2", "d3");
        // rank 越靠前分数越高
        assertThat(fused.get(0).getScore()).isGreaterThan(fused.get(1).getScore());
        assertThat(fused.get(1).getScore()).isGreaterThan(fused.get(2).getScore());
    }

    @Test
    @DisplayName("limit 参数生效")
    void rrf_limitApplied() {
        List<RetrievedChunk> list = List.of(c("d1", 0), c("d2", 1), c("d3", 2), c("d4", 3));

        List<RetrievedChunk> fused = service.reciprocalRankFusion(
                List.of(list), 60, 2);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).getDocumentId()).isEqualTo("d1");
        assertThat(fused.get(1).getDocumentId()).isEqualTo("d2");
    }

    @Test
    @DisplayName("空列表 / null 元素被安全跳过")
    void rrf_emptyAndNullLists_areSafe() {
        List<RetrievedChunk> fused = service.reciprocalRankFusion(
                java.util.Arrays.asList(null, List.of(), List.of(c("d1", 0))),
                60, 10);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).getDocumentId()).isEqualTo("d1");
    }

    @Test
    @DisplayName("全部为空返回空列表")
    void rrf_allEmpty_returnsEmpty() {
        List<RetrievedChunk> fused = service.reciprocalRankFusion(
                List.of(List.of(), List.of()), 60, 10);

        assertThat(fused).isEmpty();
    }

    @Test
    @DisplayName("(docId, chunkIndex) 相同视为同一 chunk 参与合并")
    void rrf_sameDocSameIndex_isDedup() {
        RetrievedChunk a1 = c("d1", 5);
        RetrievedChunk a2 = c("d1", 5); // 相同 key
        RetrievedChunk b = c("d2", 5);

        List<RetrievedChunk> fused = service.reciprocalRankFusion(
                List.of(List.of(a1), List.of(a2, b)),
                60, 10);

        assertThat(fused).extracting(RetrievedChunk::getDocumentId)
                .containsExactly("d1", "d2");
    }
}
