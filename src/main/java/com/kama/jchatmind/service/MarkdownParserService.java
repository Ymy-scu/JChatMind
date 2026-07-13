package com.kama.jchatmind.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.InputStream;
import java.util.List;

/**
 * Markdown 解析服务接口
 */
public interface MarkdownParserService {
    /**
     * 解析 Markdown 文件，提取标题和对应的内容
     *
     * @param inputStream Markdown 文件输入流
     * @return 标题和内容的列表，每个元素包含标题和该标题下的内容
     */
    List<MarkdownSection> parseMarkdown(InputStream inputStream);

    /**
     * Markdown 章节数据类。
     *
     * <p>{@code headingPath} 表示当前 heading 与其所有祖先 heading 的
     * 面包屑路径，用 {@code " / "} 拼接，如
     * {@code "开发指南 / 快速上手 / 环境准备"}。可为空。</p>
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @ToString
    class MarkdownSection {
        private String title;
        private String content;
        private Integer headingLevel;
        /** 章节面包屑，父子层级用 {@code " / "} 拼接 */
        private String headingPath;

        /** 兼容旧签名的三参构造：headingPath 置空 */
        public MarkdownSection(String title, String content, Integer headingLevel) {
            this(title, content, headingLevel, null);
        }
    }
}
