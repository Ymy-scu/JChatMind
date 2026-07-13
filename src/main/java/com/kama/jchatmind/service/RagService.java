package com.kama.jchatmind.service;

import com.kama.jchatmind.service.rag.RetrievedChunk;

import java.util.List;

/**
 * RAG 检索服务。
 *
 * <p>提供三层能力：</p>
 * <ol>
 *   <li>基础 embedding 生成（{@link #embed(String)}）</li>
 *   <li>多查询相似度检索（{@link #multiQuerySimilaritySearch}）——兼容旧用法</li>
 *   <li>一站式 hybrid + rerank 检索（{@link #retrieve}）——新链路推荐入口</li>
 * </ol>
 */
public interface RagService {

    /** 生成文本 embedding（bge-m3，1024 维） */
    float[] embed(String text);

    /**
     * 兼容旧签名：单查询检索，返回命中 chunk 的正文列表。
     */
    List<String> similaritySearch(String kbId, String query);

    /**
     * 多查询相似度检索
     * 对多个查询分别做 embedding，然后合并去重结果
     *
     * @param kbId    知识库 ID
     * @param queries 查询列表
     * @param topK    每个查询返回的结果数
     * @return 合并去重后的结果
     */
    List<String> multiQuerySimilaritySearch(String kbId, List<String> queries, int topK);

    /**
     * 一站式检索：向量召回 → BM25 召回（可选） → RRF 融合 → rerank（可选） → Top-K。
     *
     * <p>阈值、Top-N、Top-K、是否启用 hybrid/rerank 全部由 {@code RagProperties} 控制。</p>
     *
     * @param kbId  知识库 ID
     * @param query 用户查询
     * @return 带元数据的检索结果（可直接透出到 SSE references 事件）
     */
    List<RetrievedChunk> retrieve(String kbId, String query);
}
