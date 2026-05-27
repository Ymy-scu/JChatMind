package com.kama.jchatmind.service;

import lombok.AllArgsConstructor;
import lombok.Data;
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
     */
    @Data
    @AllArgsConstructor
    @ToString
    class DocumentSection {
        private String title;
        private String content;
        private Integer headingLevel;
    }
}
