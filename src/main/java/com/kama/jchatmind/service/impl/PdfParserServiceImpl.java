package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.DocumentParserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class PdfParserServiceImpl implements DocumentParserService {

    private static final Set<String> SUPPORTED_TYPES = Set.of("pdf");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(第[一二三四五六七八九十百千零\\d]+[章节篇]|\\d+\\.\\d*\\.?\\d*\\s|Chapter\\s+\\d+|Section\\s+\\d+)"
    );

    private static final int HEADER_FOOTER_THRESHOLD = 50;

    @Override
    public List<DocumentSection> parseDocument(InputStream inputStream, String fileType) {
        if (!supports(fileType)) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileType);
        }

        try {
            PDDocument document = Loader.loadPDF(inputStream.readAllBytes());
            List<DocumentSection> sections = new ArrayList<>();

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            // 逐页 parse，携带 pageNumber
            Map<Integer, String> pageContents = new LinkedHashMap<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(document);
                pageText = filterHeaderFooter(pageText, document, i);
                pageContents.put(i + 1, pageText);
            }

            sections = parseSectionsWithPage(pageContents);

            if (sections.isEmpty()) {
                String fullText = String.join("\n\n", pageContents.values());
                sections.add(new DocumentSection("全文内容", fullText, 1, "全文内容", 1));
            }

            document.close();

            log.info("PDF 解析完成，共提取 {} 个章节", sections.size());
            return sections;

        } catch (IOException e) {
            log.error("PDF 解析失败", e);
            throw new RuntimeException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String fileType) {
        return fileType != null && SUPPORTED_TYPES.contains(fileType.toLowerCase());
    }

    private String filterHeaderFooter(String pageText, PDDocument document, int pageIndex) {
        String[] lines = pageText.split("\n");

        if (lines.length <= 3) {
            return pageText;
        }

        StringBuilder filtered = new StringBuilder();
        int headerEnd = 0;
        int footerStart = lines.length;

        for (int i = 0; i < Math.min(3, lines.length); i++) {
            String line = lines[i].trim();
            if (isHeaderFooterLine(line, pageIndex + 1)) {
                headerEnd = i + 1;
            } else {
                break;
            }
        }

        for (int i = lines.length - 1; i >= Math.max(lines.length - 3, headerEnd); i--) {
            String line = lines[i].trim();
            if (isHeaderFooterLine(line, pageIndex + 1)) {
                footerStart = i;
            } else {
                break;
            }
        }

        for (int i = headerEnd; i < footerStart; i++) {
            if (filtered.length() > 0) {
                filtered.append("\n");
            }
            filtered.append(lines[i]);
        }

        return filtered.toString();
    }

    private boolean isHeaderFooterLine(String line, int pageNumber) {
        if (line.isEmpty()) {
            return false;
        }

        if (line.matches("^\\d+$")) {
            return true;
        }

        if (line.matches("^第\\s*\\d+\\s*页.*") || line.matches("^Page\\s+\\d+.*")) {
            return true;
        }

        if (line.length() < HEADER_FOOTER_THRESHOLD &&
            (line.contains("©") || line.contains("版权所有") || line.contains("Confidential"))) {
            return true;
        }

        return false;
    }

    /**
     * 逐页扫描：为每个 section 记录起始 {@code pageNumber} 与 heading 面包屑。
     */
    private List<DocumentSection> parseSectionsWithPage(Map<Integer, String> pageContents) {
        List<DocumentSection> sections = new ArrayList<>();

        StringBuilder currentTitle = new StringBuilder();
        StringBuilder currentContent = new StringBuilder();
        int currentLevel = 1;
        Integer currentPage = null;
        String currentHeadingPath = null;

        java.util.Deque<int[]> levelStack = new java.util.ArrayDeque<>();
        java.util.Deque<String> titleStack = new java.util.ArrayDeque<>();

        for (Map.Entry<Integer, String> e : pageContents.entrySet()) {
            int pageNumber = e.getKey();
            String[] lines = e.getValue().split("\n");

            for (String line : lines) {
                String trimmedLine = line.trim();

                if (trimmedLine.isEmpty()) {
                    if (currentContent.length() > 0) {
                        currentContent.append("\n");
                    }
                    continue;
                }

                Integer headingLevel = detectHeadingLevel(trimmedLine);

                if (headingLevel != null) {
                    if (currentTitle.length() > 0) {
                        sections.add(new DocumentSection(
                                currentTitle.toString().trim(),
                                currentContent.toString().trim(),
                                currentLevel,
                                currentHeadingPath,
                                currentPage
                        ));
                    }

                    while (!levelStack.isEmpty() && levelStack.peek()[0] >= headingLevel) {
                        levelStack.pop();
                        titleStack.pop();
                    }
                    levelStack.push(new int[]{headingLevel});
                    titleStack.push(trimmedLine);
                    currentHeadingPath = buildHeadingPath(titleStack);

                    currentTitle = new StringBuilder(trimmedLine);
                    currentContent = new StringBuilder();
                    currentLevel = headingLevel;
                    currentPage = pageNumber;
                } else {
                    if (currentContent.length() > 0) {
                        currentContent.append("\n");
                    }
                    currentContent.append(trimmedLine);
                    // 尚未遇到任何 heading 的正文，页码取首次出现的页
                    if (currentPage == null) {
                        currentPage = pageNumber;
                    }
                }
            }
        }

        if (currentTitle.length() > 0) {
            sections.add(new DocumentSection(
                    currentTitle.toString().trim(),
                    currentContent.toString().trim(),
                    currentLevel,
                    currentHeadingPath,
                    currentPage
            ));
        } else if (currentContent.length() > 0) {
            // 全文没有任何 heading 但有正文的情形
            sections.add(new DocumentSection(
                    "全文内容",
                    currentContent.toString().trim(),
                    1,
                    "全文内容",
                    currentPage == null ? 1 : currentPage
            ));
        }

        return sections;
    }

    /** 把 titleStack（栈顶为最新 heading）按栈底 → 栈顶顺序拼成 {@code "父 / 子 / 孙"} */
    private String buildHeadingPath(java.util.Deque<String> titleStack) {
        if (titleStack.isEmpty()) return null;
        List<String> ordered = new ArrayList<>(titleStack);
        java.util.Collections.reverse(ordered);
        return String.join(" / ", ordered);
    }

    private Integer detectHeadingLevel(String line) {
        Matcher matcher = HEADING_PATTERN.matcher(line);
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

        if (line.matches("^[一二三四五六七八九十]+[、.].*") && line.length() < 30) {
            return 2;
        }

        if (line.matches("^\\(一\\)|^\\(二\\)|^\\(三\\).*") && line.length() < 30) {
            return 3;
        }

        if (line.matches("^Chapter\\s+\\d+.*") || line.matches("^CHAPTER\\s+\\d+.*")) {
            return 2;
        }

        return null;
    }
}
