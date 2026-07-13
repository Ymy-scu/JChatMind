package com.kama.jchatmind.service.rag;

import com.kama.jchatmind.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 结构化 chunk 之上的 token 二次切分器。
 *
 * <p>目标：</p>
 * <ol>
 *   <li>保证任意 chunk 的估算 token 数 &le; {@code maxTokens}</li>
 *   <li>相邻同 headingPath 短片段合并到 {@code minTokens} 附近</li>
 *   <li>切分时按句子边界（中英文标点+换行）优先，仍超长则硬切</li>
 *   <li>相邻子 chunk 之间保留 {@code overlap} token 作为上下文</li>
 * </ol>
 *
 * <p>token 数用 UTF-8 字节数 / 3 近似（bge-m3 中文/英文平均比接近该值）。</p>
 */
@Slf4j
@Component
public class TokenAwareSplitter {

    /** 句子边界字符集合，中英通用 */
    private static final String SENTENCE_BREAKS = "。！？；.!?;";

    private final RagProperties properties;

    public TokenAwareSplitter(RagProperties properties) {
        this.properties = properties;
    }

    /**
     * 主入口：结构化 chunk 列表 → 最终 chunk 列表。
     *
     * @param parsed 解析器产出的原始 chunk
     * @return 满足 token 上限的最终 chunk，{@code chunkIndex} 已连续赋值
     */
    public List<Chunk> split(List<ParsedChunk> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return List.of();
        }

        int maxTokens = properties.getChunk().getMaxTokens();
        int minTokens = properties.getChunk().getMinTokens();
        int overlap = properties.getChunk().getOverlap();

        // 第 1 步：合并相邻同 heading 的短片段
        List<ParsedChunk> merged = mergeShortNeighbors(parsed, minTokens);

        // 第 2 步：按 token 上限切分
        List<Chunk> result = new ArrayList<>();
        int chunkIndex = 0;
        for (ParsedChunk pc : merged) {
            String content = pc.getContent() == null ? "" : pc.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            List<String> pieces = splitByTokenLimit(content, maxTokens, overlap);
            for (String piece : pieces) {
                if (piece.isBlank()) continue;
                result.add(Chunk.builder()
                        .content(piece)
                        .headingPath(pc.getHeadingPath())
                        .pageNumber(pc.getPageNumber())
                        .chunkIndex(chunkIndex++)
                        .tokenCount(estimateTokens(piece))
                        .build());
            }
        }
        return result;
    }

    /**
     * 估算 token 数：UTF-8 字节数 / 3。
     *
     * <p>此值是 bge-m3 中文与英文场景下的经验值，用于避免引入额外 tokenizer 依赖。
     * 精确度不足以做计费，但足以做切分决策。</p>
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 3);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private List<ParsedChunk> mergeShortNeighbors(List<ParsedChunk> parsed, int minTokens) {
        List<ParsedChunk> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String currentHeading = null;
        Integer currentPage = null;
        int bufTokens = 0;

        for (ParsedChunk pc : parsed) {
            String content = pc.getContent() == null ? "" : pc.getContent().trim();
            if (content.isEmpty()) continue;
            int tokens = estimateTokens(content);

            // heading 变化，或者当前累计已经足够，就 flush
            boolean headingChanged = !Objects.equals(currentHeading, pc.getHeadingPath());
            if (headingChanged || bufTokens >= minTokens) {
                if (buf.length() > 0) {
                    out.add(ParsedChunk.builder()
                            .content(buf.toString())
                            .headingPath(currentHeading)
                            .pageNumber(currentPage)
                            .build());
                    buf.setLength(0);
                    bufTokens = 0;
                }
                currentHeading = pc.getHeadingPath();
                currentPage = pc.getPageNumber();
            }
            if (buf.length() > 0) buf.append("\n");
            buf.append(content);
            bufTokens += tokens;
        }
        if (buf.length() > 0) {
            out.add(ParsedChunk.builder()
                    .content(buf.toString())
                    .headingPath(currentHeading)
                    .pageNumber(currentPage)
                    .build());
        }
        return out;
    }

    private List<String> splitByTokenLimit(String content, int maxTokens, int overlap) {
        List<String> out = new ArrayList<>();
        if (estimateTokens(content) <= maxTokens) {
            out.add(content);
            return out;
        }

        // 先按句子边界拆分成一组"句子"
        List<String> sentences = splitBySentences(content);

        StringBuilder buf = new StringBuilder();
        int bufTokens = 0;
        String overlapTail = "";

        for (String s : sentences) {
            int sTokens = estimateTokens(s);
            // 单句仍然超过上限，硬切
            if (sTokens > maxTokens) {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    overlapTail = takeTail(buf.toString(), overlap);
                    buf.setLength(0);
                    bufTokens = 0;
                }
                out.addAll(hardSplit(s, maxTokens));
                overlapTail = takeTail(out.get(out.size() - 1), overlap);
                continue;
            }
            if (bufTokens + sTokens > maxTokens && buf.length() > 0) {
                out.add(buf.toString());
                overlapTail = takeTail(buf.toString(), overlap);
                buf.setLength(0);
                bufTokens = 0;
                if (!overlapTail.isEmpty()) {
                    buf.append(overlapTail).append(' ');
                    bufTokens += estimateTokens(overlapTail);
                }
            }
            if (buf.length() > 0) buf.append(' ');
            buf.append(s);
            bufTokens += sTokens;
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    private List<String> splitBySentences(String content) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            cur.append(c);
            if (SENTENCE_BREAKS.indexOf(c) >= 0 || c == '\n') {
                if (cur.toString().trim().length() > 0) {
                    out.add(cur.toString().trim());
                }
                cur.setLength(0);
            }
        }
        if (cur.toString().trim().length() > 0) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    private List<String> hardSplit(String s, int maxTokens) {
        List<String> out = new ArrayList<>();
        int step = Math.max(1, maxTokens * 3); // token≈bytes/3，还原成字节步长
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int i = 0;
        while (i < bytes.length) {
            int end = Math.min(bytes.length, i + step);
            // 尽量落在合法 UTF-8 边界（中文占 3 字节）：往前退到 &lt;= 0x7F 或多字节起始位
            while (end < bytes.length && (bytes[end] & 0xC0) == 0x80) {
                end--;
            }
            out.add(new String(bytes, i, end - i, java.nio.charset.StandardCharsets.UTF_8));
            i = end;
        }
        return out;
    }

    private String takeTail(String s, int overlapTokens) {
        if (s == null || s.isEmpty() || overlapTokens <= 0) return "";
        int targetBytes = overlapTokens * 3;
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= targetBytes) return s;
        int start = bytes.length - targetBytes;
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) start++;
        return new String(bytes, start, bytes.length - start, java.nio.charset.StandardCharsets.UTF_8);
    }
}
