package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.DocumentParserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class WordParserServiceImpl implements DocumentParserService {

    private static final Set<String> SUPPORTED_TYPES = Set.of("doc", "docx");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(第[一二三四五六七八九十百千零\\d]+[章节篇]|\\d+\\.\\d*\\.?\\d*\\s)"
    );

    @Override
    public List<DocumentSection> parseDocument(InputStream inputStream, String fileType) {
        if (!supports(fileType)) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileType);
        }

        try {
            if ("docx".equalsIgnoreCase(fileType)) {
                return parseDocx(inputStream);
            } else {
                return parseDoc(inputStream);
            }
        } catch (IOException e) {
            log.error("Word 文档解析失败", e);
            throw new RuntimeException("Word 文档解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String fileType) {
        return fileType != null && SUPPORTED_TYPES.contains(fileType.toLowerCase());
    }

    private List<DocumentSection> parseDocx(InputStream inputStream) throws IOException {
        XWPFDocument document = new XWPFDocument(inputStream);
        List<DocumentSection> sections = new ArrayList<>();

        StringBuilder currentTitle = new StringBuilder();
        StringBuilder currentContent = new StringBuilder();
        int currentLevel = 1;
        String currentHeadingPath = null;

        // 维护 heading 面包屑（level → title），弹出所有 level ≥ 当前 level 的祖先
        java.util.Deque<int[]> levelStack = new java.util.ArrayDeque<>();
        java.util.Deque<String> titleStack = new java.util.ArrayDeque<>();

        for (IBodyElement element : document.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                String text = paragraph.getText();
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }

                String styleName = paragraph.getStyle();
                int headingLevel = detectHeadingLevel(text, styleName);

                if (headingLevel > 0) {
                    if (currentTitle.length() > 0) {
                        sections.add(new DocumentSection(
                                currentTitle.toString().trim(),
                                currentContent.toString().trim(),
                                currentLevel,
                                currentHeadingPath,
                                null
                        ));
                    }

                    // 更新面包屑
                    while (!levelStack.isEmpty() && levelStack.peek()[0] >= headingLevel) {
                        levelStack.pop();
                        titleStack.pop();
                    }
                    levelStack.push(new int[]{headingLevel});
                    titleStack.push(text.trim());
                    currentHeadingPath = buildHeadingPath(titleStack);

                    currentTitle = new StringBuilder(text);
                    currentContent = new StringBuilder();
                    currentLevel = headingLevel;
                } else {
                    if (currentContent.length() > 0) {
                        currentContent.append("\n");
                    }
                    currentContent.append(text);
                }
            } else if (element instanceof XWPFTable table) {
                String tableMarkdown = convertTableToMarkdown(table);
                if (tableMarkdown != null && !tableMarkdown.isEmpty()) {
                    if (currentContent.length() > 0) {
                        currentContent.append("\n");
                    }
                    currentContent.append(tableMarkdown);
                }
            }
        }

        if (currentTitle.length() > 0) {
            sections.add(new DocumentSection(
                    currentTitle.toString().trim(),
                    currentContent.toString().trim(),
                    currentLevel,
                    currentHeadingPath,
                    null
            ));
        }

        document.close();

        log.info("Word (docx) 解析完成，共提取 {} 个章节", sections.size());
        return sections;
    }

    private List<DocumentSection> parseDoc(InputStream inputStream) throws IOException {
        HWPFDocument document = new HWPFDocument(inputStream);
        WordExtractor extractor = new WordExtractor(document);
        List<DocumentSection> sections = new ArrayList<>();

        String fullText = extractor.getText();
        String[] paragraphs = fullText.split("\n");

        StringBuilder currentTitle = new StringBuilder();
        StringBuilder currentContent = new StringBuilder();
        int currentLevel = 1;
        String currentHeadingPath = null;

        java.util.Deque<int[]> levelStack = new java.util.ArrayDeque<>();
        java.util.Deque<String> titleStack = new java.util.ArrayDeque<>();

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Integer headingLevel = detectHeadingLevel(trimmed, null);

            if (headingLevel != null) {
                if (currentTitle.length() > 0) {
                    sections.add(new DocumentSection(
                            currentTitle.toString().trim(),
                            currentContent.toString().trim(),
                            currentLevel,
                            currentHeadingPath,
                            null
                    ));
                }

                while (!levelStack.isEmpty() && levelStack.peek()[0] >= headingLevel) {
                    levelStack.pop();
                    titleStack.pop();
                }
                levelStack.push(new int[]{headingLevel});
                titleStack.push(trimmed);
                currentHeadingPath = buildHeadingPath(titleStack);

                currentTitle = new StringBuilder(trimmed);
                currentContent = new StringBuilder();
                currentLevel = headingLevel;
            } else {
                if (currentContent.length() > 0) {
                    currentContent.append("\n");
                }
                currentContent.append(trimmed);
            }
        }

        if (currentTitle.length() > 0) {
            sections.add(new DocumentSection(
                    currentTitle.toString().trim(),
                    currentContent.toString().trim(),
                    currentLevel,
                    currentHeadingPath,
                    null
            ));
        }

        extractor.close();
        document.close();

        log.info("Word (doc) 解析完成，共提取 {} 个章节", sections.size());
        return sections;
    }

    /** 把 titleStack（栈顶为最新 heading）按栈底 → 栈顶顺序拼成 {@code "父 / 子 / 孙"} */
    private String buildHeadingPath(java.util.Deque<String> titleStack) {
        if (titleStack.isEmpty()) return null;
        List<String> ordered = new ArrayList<>(titleStack);
        java.util.Collections.reverse(ordered);
        return String.join(" / ", ordered);
    }

    private int detectHeadingLevel(String text, String styleName) {
        if (styleName != null) {
            String lowerStyle = styleName.toLowerCase();
            if (lowerStyle.contains("heading") || lowerStyle.contains("标题")) {
                try {
                    String numStr = lowerStyle.replaceAll("[^\\d]", "");
                    if (!numStr.isEmpty()) {
                        return Integer.parseInt(numStr);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        Matcher matcher = HEADING_PATTERN.matcher(text);
        if (matcher.find()) {
            String prefix = matcher.group(1);

            if (prefix.matches("第[一二三四五六七八九十百千零\\d]+[篇].*")) {
                return 1;
            }
            if (prefix.matches("第[一二三四五六七八九十百千零\\d]+[章].*")) {
                return 2;
            }
            if (prefix.matches("第[一二三四五六七八九十百千零\\d]+[节].*")) {
                return 3;
            }

            int dotCount = prefix.chars().filter(c -> c == '.').map(c -> 1).sum();
            if (dotCount == 0) {
                return 2;
            }
            if (dotCount == 1) {
                return 3;
            }
            return 4;
        }

        if (text.matches("^[一二三四五六七八九十]+[、.].*") && text.length() < 30) {
            return 2;
        }

        return 0;
    }

    private String convertTableToMarkdown(XWPFTable table) {
        if (table == null || table.getRows().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < table.getRows().size(); i++) {
            XWPFTableRow row = table.getRows().get(i);
            sb.append("|");

            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText();
                if (cellText == null) {
                    cellText = "";
                }
                cellText = cellText.replace("\n", " ").replace("|", "\\|");
                sb.append(" ").append(cellText).append(" |");
            }

            sb.append("\n");

            if (i == 0) {
                sb.append("|");
                for (int j = 0; j < row.getTableCells().size(); j++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
