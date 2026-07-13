package com.kama.jchatmind.service.rag;

import com.kama.jchatmind.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BgeRerankerServiceImpl} 单元测试。
 *
 * <p>通过自定义 {@link ExchangeFunction} 桩化 WebClient 的 HTTP 层，覆盖：</p>
 * <ol>
 *   <li>Rerank 关闭 → 直接返回前 topK</li>
 *   <li>Rerank 成功 → 按服务端 index 顺序返回</li>
 *   <li>Rerank 超时 → 降级为原顺序前 topK</li>
 *   <li>Rerank 5xx 异常 → 降级为原顺序前 topK</li>
 *   <li>候选去重：(docId, chunkIndex) 相同只保留一份</li>
 * </ol>
 */
class BgeRerankerServiceImplTest {

    private RagProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        properties.getRerank().setModel("bge-reranker-v2-m3");
        properties.getRerank().setBaseUrl("http://localhost:11434");
        properties.getRerank().setTimeoutMs(200);
        properties.getRerank().setTopK(3);
    }

    private BgeRerankerServiceImpl buildService(ExchangeFunction ef) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(ef);
        return new BgeRerankerServiceImpl(properties, builder);
    }

    private RetrievedChunk c(String docId, int idx, String content) {
        return RetrievedChunk.builder()
                .documentId(docId)
                .chunkIndex(idx)
                .content(content)
                .build();
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(org.springframework.http.HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Test
    @DisplayName("Rerank 关闭：不调用远端，直接返回前 topK")
    void rerank_disabled_returnsTopK() {
        properties.getRerank().setEnabled(false);
        // 若真的发起请求会返回 500 使测试失败
        BgeRerankerServiceImpl svc = buildService(req -> Mono.error(new RuntimeException("should not be called")));

        List<RetrievedChunk> out = svc.rerank("q",
                List.of(c("d1", 0, "a"), c("d2", 1, "b"), c("d3", 2, "c"), c("d4", 3, "d")),
                2);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(RetrievedChunk::getDocumentId).containsExactly("d1", "d2");
    }

    @Test
    @DisplayName("Rerank 成功：按服务端返回的 index 顺序重排")
    void rerank_success_reordersByIndex() {
        // 服务端返回：把原第 2 个排到最前
        String body = "{\"results\":["
                + "{\"index\":2,\"relevance_score\":0.9},"
                + "{\"index\":0,\"relevance_score\":0.5},"
                + "{\"index\":1,\"relevance_score\":0.1}"
                + "]}";
        BgeRerankerServiceImpl svc = buildService(req -> Mono.just(jsonResponse(body)));

        List<RetrievedChunk> out = svc.rerank("q",
                List.of(c("d1", 0, "a"), c("d2", 1, "b"), c("d3", 2, "c")),
                3);

        assertThat(out).hasSize(3);
        assertThat(out).extracting(RetrievedChunk::getDocumentId)
                .containsExactly("d3", "d1", "d2");
        assertThat(out.get(0).getScore()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("Rerank 超时：降级为原顺序前 topK")
    void rerank_timeout_fallsBackToOriginal() {
        // 响应延迟远大于 timeoutMs
        BgeRerankerServiceImpl svc = buildService(req -> Mono.just(jsonResponse("{\"results\":[]}"))
                .delayElement(Duration.ofMillis(2000)));

        List<RetrievedChunk> out = svc.rerank("q",
                List.of(c("d1", 0, "a"), c("d2", 1, "b"), c("d3", 2, "c")),
                2);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(RetrievedChunk::getDocumentId).containsExactly("d1", "d2");
    }

    @Test
    @DisplayName("Rerank 5xx：降级为原顺序前 topK")
    void rerank_serverError_fallsBackToOriginal() {
        ClientResponse err = ClientResponse.create(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"error\":\"boom\"}")
                .build();
        BgeRerankerServiceImpl svc = buildService(req -> Mono.just(err));

        List<RetrievedChunk> out = svc.rerank("q",
                List.of(c("d1", 0, "a"), c("d2", 1, "b")),
                2);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(RetrievedChunk::getDocumentId).containsExactly("d1", "d2");
    }

    @Test
    @DisplayName("Rerank 空响应体：降级为原顺序前 topK")
    void rerank_emptyResults_fallsBackToOriginal() {
        BgeRerankerServiceImpl svc = buildService(req -> Mono.just(jsonResponse("{\"results\":[]}")));

        List<RetrievedChunk> out = svc.rerank("q",
                List.of(c("d1", 0, "a"), c("d2", 1, "b")),
                2);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(RetrievedChunk::getDocumentId).containsExactly("d1", "d2");
    }

    @Test
    @DisplayName("空候选返回空列表")
    void rerank_emptyCandidates_returnsEmpty() {
        BgeRerankerServiceImpl svc = buildService(req -> Mono.error(new RuntimeException("should not be called")));

        assertThat(svc.rerank("q", List.of(), 3)).isEmpty();
        assertThat(svc.rerank("q", null, 3)).isEmpty();
    }

    @Test
    @DisplayName("候选 (docId, chunkIndex) 去重后再送 rerank")
    void rerank_dedupCandidates() {
        properties.getRerank().setEnabled(false); // 走去重 + 直返分支
        BgeRerankerServiceImpl svc = buildService(req -> Mono.error(new RuntimeException("should not be called")));

        List<RetrievedChunk> out = svc.rerank("q",
                List.of(c("d1", 0, "a"), c("d1", 0, "a-dup"), c("d2", 1, "b")),
                10);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(RetrievedChunk::getDocumentId).containsExactly("d1", "d2");
    }

    @Test
    @DisplayName("index 越界的 rerank 结果会被跳过，仍返回有效项")
    void rerank_outOfRangeIndex_skipped() {
        String body = "{\"results\":["
                + "{\"index\":99,\"relevance_score\":0.9},"
                + "{\"index\":0,\"relevance_score\":0.5}"
                + "]}";
        BgeRerankerServiceImpl svc = buildService(req -> Mono.just(jsonResponse(body)));

        List<RetrievedChunk> out = svc.rerank("q",
                List.of(c("d1", 0, "a"), c("d2", 1, "b")),
                2);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getDocumentId()).isEqualTo("d1");
    }
}
