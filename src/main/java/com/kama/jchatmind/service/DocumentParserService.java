package com.kama.jchatmind.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.InputStream;
import java.util.List;

/**
 * 统一文档解析服务接口
 * 支持 Markdown、PDF、Word 等多种文档格式
 */
public interface DocumentParserService {

    /**
     * 解析文档，提取章节内容
     *
     * @param inputStream 文档输入流
     * @param fileType    文件类型（md, pdf, docx 等）
     * @return 章节列表
     */
    List<DocumentSection> parseDocument(InputStream inputStream, String fileType);

    /**
     * 检查是否支持指定的文件类型
     *
     * @param fileType 文件类型
     * @return 是否支持
     */
    boolean supports(String fileType);

    /**
     * 文档章节数据类
     *
     * <p>字段说明：</p>
     * <ul>
     *   <li>{@code title} —— 章节标题（原文）</li>
     *   <li>{@code content} —— 章节正文</li>
     *   <li>{@code headingLevel} —— 章节层级（1 表示 H1）</li>
     *   <li>{@code headingPath} —— 面包屑，形如 {@code "第一章 / 1.2 权限"}，用于向 LLM 传递位置信息（可为空）</li>
     *   <li>{@code pageNumber} —— 章节起始页码（PDF 专用，其它解析器为空）</li>
     * </ul>
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @ToString
    class DocumentSection {
        private String title;
        private String content;
        private Integer headingLevel;
        /** 章节面包屑，格式 {@code "父级 / 子级"} */
        private String headingPath;
        /** PDF 章节起始页码；非 PDF 场景为空 */
        private Integer pageNumber;

        /** 兼容旧签名的三参构造：headingPath / pageNumber 置空 */
        public DocumentSection(String title, String content, Integer headingLevel) {
            this(title, content, headingLevel, null, null);
        }
    }
}
