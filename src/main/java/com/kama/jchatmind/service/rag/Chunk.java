package com.kama.jchatmind.service.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已切分完成的最终 chunk。
 *
 * <p>由 {@link TokenAwareSplitter} 从解析器输出的 {@link ParsedChunk} 生成，
 * 直接对应 {@code chunk_bge_m3} 表的一行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

    /** 文档在库中的最终正文（用于生成 embedding、返回给 LLM、构造 snippet） */
    private String content;

    /** heading 面包屑，如 "第一章 / 1.2 权限" */
    private String headingPath;

    /** 页码，仅 PDF 等按页解析的格式会填充 */
    private Integer pageNumber;

    /** 同一 documentId 下的顺序号，从 0 开始 */
    private int chunkIndex;

    /** 估算的 token 数（bge-m3 tokenizer 近似） */
    private int tokenCount;
}
