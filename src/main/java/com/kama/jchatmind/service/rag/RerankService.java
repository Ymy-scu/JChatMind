package com.kama.jchatmind.service.rag;

import java.util.List;

/**
 * Cross-Encoder 重排服务。
 *
 * <p>输入 query 与候选 chunk 列表，返回按相关度重新排序后的 Top-K 结果。
 * 失败时应由实现自身降级（返回原始顺序前 topK），不得抛出到调用方。</p>
 */
public interface RerankService {

    /**
     * 对 {@code candidates} 按 {@code query} 相关度重排，返回前 {@code topK}。
     *
     * @param query      用户查询
     * @param candidates 候选列表（已经过 hybrid 融合）
     * @param topK       期望返回的数量
     * @return 重排后的 Top-K 列表；调用方无需再做长度截断
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
