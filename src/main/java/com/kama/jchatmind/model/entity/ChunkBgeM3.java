package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;
import java.util.Arrays;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @TableName chunk_bge_m3
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkBgeM3 {
    private String id;

    private String kbId;

    private String docId;

    private String content;

    private String metadata;

    private float[] embedding;

    /** 源文件名（冗余，便于展示与引用溯源） */
    private String filename;

    /** 页码（PDF 有值，Markdown/Word 可为 null） */
    private Integer pageNumber;

    /** heading 面包屑，形如 "第一章 / 1.2 权限" */
    private String headingPath;

    /** 同一 docId 下的顺序号，从 0 开始 */
    private Integer chunkIndex;

    /** 估算的 token 数 */
    private Integer tokenCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 查询期派生字段：{@code similaritySearch} 通过 {@code embedding <=> :q AS distance}
     * 返回的余弦距离（{@code distance ∈ [0, 2]}），由 {@code SimilarityResultMap}
     * 映射至此。持久化列中不存在此字段，equals/hashCode 不参与。
     */
    private Double score;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        ChunkBgeM3 other = (ChunkBgeM3) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getKbId() == null ? other.getKbId() == null : this.getKbId().equals(other.getKbId()))
            && (this.getDocId() == null ? other.getDocId() == null : this.getDocId().equals(other.getDocId()))
            && (this.getContent() == null ? other.getContent() == null : this.getContent().equals(other.getContent()))
            && (this.getMetadata() == null ? other.getMetadata() == null : this.getMetadata().equals(other.getMetadata()))
            && (this.getEmbedding() == null ? other.getEmbedding() == null : Arrays.equals(this.getEmbedding(), other.getEmbedding()))
            && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
            && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getKbId() == null) ? 0 : getKbId().hashCode());
        result = prime * result + ((getDocId() == null) ? 0 : getDocId().hashCode());
        result = prime * result + ((getContent() == null) ? 0 : getContent().hashCode());
        result = prime * result + ((getMetadata() == null) ? 0 : getMetadata().hashCode());
        result = prime * result + ((getEmbedding() == null) ? 0 : Arrays.hashCode(getEmbedding()));
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        result = prime * result + ((getUpdatedAt() == null) ? 0 : getUpdatedAt().hashCode());
        return result;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() +
                " [" +
                "Hash = " + hashCode() +
                ", id=" + id +
                ", kbId=" + kbId +
                ", docId=" + docId +
                ", content=" + content +
                ", metadata=" + metadata +
                ", filename=" + filename +
                ", pageNumber=" + pageNumber +
                ", headingPath=" + headingPath +
                ", chunkIndex=" + chunkIndex +
                ", tokenCount=" + tokenCount +
                ", embedding=" + Arrays.toString(embedding) +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                "]";
    }
}
