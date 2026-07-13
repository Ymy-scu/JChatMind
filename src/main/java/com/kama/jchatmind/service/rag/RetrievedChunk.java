package com.kama.jchatmind.service.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索结果 chunk（用于回填给 Agent 与前端引用溯源）。
 *
 * <p>相比数据库实体 {@code ChunkBgeM3}，只保留检索链路真正需要的字段，
 * 并追加 {@link #score} 便于观测调优。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {

    /** 数据库主键（可选，主要用于日志与去重） */
    private String id;

    /** 文档 ID（前端可用于跳转） */
    private String documentId;

    /** 源文件名 */
    private String filename;

    /** 页码 */
    private Integer pageNumber;

    /** heading 面包屑，"第一章 / 1.2 权限" 形式 */
    private String headingPath;

    /** 同一 docId 下的顺序号 */
    private Integer chunkIndex;

    /** 用于回答的正文（LLM 直接看到） */
    private String content;

    /** 相似度或融合分数（越大越相关，仅用于观测） */
    private double score;
}
