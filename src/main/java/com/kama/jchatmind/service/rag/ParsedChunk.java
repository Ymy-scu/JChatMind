package com.kama.jchatmind.service.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 解析器输出的原始 chunk。
 *
 * <p>由 Markdown / PDF / Word 解析器产出，尚未经过 token 上限校验与合并。
 * 后续会送入 {@link TokenAwareSplitter} 生成最终写入 DB 的 {@link Chunk}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedChunk {

    /** 章节正文（不含标题） */
    private String content;

    /** heading 面包屑，如 "第一章 / 1.2 权限" */
    private String headingPath;

    /** 该 chunk 起始所在页（PDF 有值，其它类型可为 null） */
    private Integer pageNumber;
}
