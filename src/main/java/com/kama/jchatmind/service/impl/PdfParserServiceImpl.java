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

            // 兜底：全篇没有识别到任何 heading，或只识别到一个"全文内容"包裹时，
            // 按段落（连续空行）二次切分，避免把整份文档塞成一个 section。
            // 典型场景：简历、说明书、无编号标题的短文档。
            if (sections.isEmpty()
                    || (sections.size() == 1 && "全文内容".equals(sections.get(0).getTitle()))) {
                sections = fallbackSplitByParagraph(pageContents);
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

    /**
     * 兜底切分：按段落（连续空行）+ 段落 token 上限拆分。
     *
     * <p>用于简历/说明书这种无编号标题的短文档：</p>
     * <ul>
     *   <li>先按 "\n\n+"（连续空行）拆段落，天然对应 Word/PDF 的视觉段落</li>
     *   <li>过短段落（&lt; minChars=20）与相邻段合并，避免只有"姓名: 张三"这种碎片</li>
     *   <li>过长段落（&gt; softMaxChars=1200）就地拆分，交给 TokenAwareSplitter 二次切</li>
     *   <li>heading 使用段落首行作为提示（截断到 30 字符），页码取该段所在页</li>
     * </ul>
     */
    private List<DocumentSection> fallbackSplitByParagraph(Map<Integer, String> pageContents) {
        final int minChars = 20;
        final int softMaxChars = 1200;

        List<DocumentSection> sections = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Integer bufPage = null;

        for (Map.Entry<Integer, String> e : pageContents.entrySet()) {
            int page = e.getKey();
            String[] paragraphs = e.getValue().split("\\r?\\n\\s*\\r?\\n+");

            for (String raw : paragraphs) {
                String p = raw.strip();
                if (p.isEmpty()) continue;

                if (buf.length() > 0 && buf.length() + p.length() > softMaxChars) {
                    sections.add(toParagraphSection(buf.toString(), bufPage));
                    buf.setLength(0);
                    bufPage = null;
                }

                if (bufPage == null) bufPage = page;
                if (buf.length() > 0) buf.append("\n\n");
                buf.append(p);

                // 超过软上限，直接 flush；避免继续累积
                if (buf.length() >= softMaxChars) {
                    sections.add(toParagraphSection(buf.toString(), bufPage));
                    buf.setLength(0);
                    bufPage = null;
                    continue;
                }

                // 累积到 minChars 之上 才允许作为独立段 flush（下一次循环判定合并）
                if (buf.length() >= minChars) {
                    // 保留在 buf 中，等下一段判断是否需要合并（下一段进来时若不超软上限就会拼进来）
                }
            }
        }
        if (buf.length() > 0) {
            sections.add(toParagraphSection(buf.toString(), bufPage == null ? 1 : bufPage));
        }
        // 极端场景：整份文档都是空白
        if (sections.isEmpty()) {
            String full = String.join("\n\n", pageContents.values()).strip();
            if (!full.isEmpty()) {
                sections.add(new DocumentSection("全文内容", full, 1, "全文内容", 1));
            }
        }
        return sections;
    }

    /** 用段落首行（截断到 30 字符）作为伪 heading，便于后续引用溯源。 */
    private DocumentSection toParagraphSection(String content, Integer page) {
        String firstLine = content.split("\\r?\\n", 2)[0].strip();
        String title = firstLine.length() > 30 ? firstLine.substring(0, 30) + "…" : firstLine;
        if (title.isEmpty()) title = "段落";
        return new DocumentSection(title, content, 1, title, page == null ? 1 : page);
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
