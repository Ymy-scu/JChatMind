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

            Map<Integer, String> pageContents = new LinkedHashMap<>();
            Map<Integer, List<String[]>> pageTables = new LinkedHashMap<>();

            for (int i = 0; i < document.getNumberOfPages(); i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(document);

                pageText = filterHeaderFooter(pageText, document, i);

                pageContents.put(i, pageText);
            }

            String fullText = String.join("\n\n", pageContents.values());

            sections = parseSections(fullText);

            if (sections.isEmpty()) {
                sections.add(new DocumentSection("全文内容", fullText, 1));
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

    private List<DocumentSection> parseSections(String text) {
        List<DocumentSection> sections = new ArrayList<>();
        String[] lines = text.split("\n");

        StringBuilder currentTitle = new StringBuilder();
        StringBuilder currentContent = new StringBuilder();
        int currentLevel = 1;

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
                            currentLevel
                    ));
                }

                currentTitle = new StringBuilder(trimmedLine);
                currentContent = new StringBuilder();
                currentLevel = headingLevel;
            } else {
                if (currentContent.length() > 0) {
                    currentContent.append("\n");
                }
                currentContent.append(trimmedLine);
            }
        }

        if (currentTitle.length() > 0) {
            sections.add(new DocumentSection(
                    currentTitle.toString().trim(),
                    currentContent.toString().trim(),
                    currentLevel
            ));
        }

        return sections;
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
