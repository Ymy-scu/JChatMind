package com.kama.jchatmind.service;

import java.util.List;

public interface QueryExpansionService {

    /**
     * 对用户查询进行扩写和改写，生成多个语义相关的查询
     * 用于提高 RAG 检索的召回率
     *
     * @param originalQuery 原始用户查询
     * @return 扩写后的查询列表（包含原始查询）
     */
    List<String> expandQuery(String originalQuery);
}
