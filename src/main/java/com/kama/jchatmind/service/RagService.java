package com.kama.jchatmind.service;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    List<String> similaritySearch(String kbId, String title);

    /**
     * 多查询相似度检索
     * 对多个查询分别做 embedding，然后合并去重结果
     *
     * @param kbId 知识库 ID
     * @param queries 查询列表
     * @param topK 每个查询返回的结果数
     * @return 合并去重后的结果
     */
    List<String> multiQuerySimilaritySearch(String kbId, List<String> queries, int topK);
}
