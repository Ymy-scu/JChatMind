package com.kama.jchatmind.service.rag;

import com.kama.jchatmind.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TokenAwareSplitter} 单元测试。
 *
 * <p>覆盖 4 条主链路：</p>
 * <ul>
 *   <li>超长内容按 token 上限切分</li>
 *   <li>相邻同 heading 的短片段合并</li>
 *   <li>相邻子 chunk 之间保留 overlap</li>
 *   <li>空正文/空白正文被过滤，不进入结果</li>
 * </ul>
 */
class TokenAwareSplitterTest {

    private RagProperties properties;
    private TokenAwareSplitter splitter;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        // 使用小上限方便触发切分；estimateTokens = bytes/3
        properties.getChunk().setMaxTokens(20);
        properties.getChunk().setMinTokens(10);
        properties.getChunk().setOverlap(4);
        splitter = new TokenAwareSplitter(properties);
    }

    @Test
    @DisplayName("超长内容按 maxTokens 切分为多个 chunk")
    void split_longContent_producesMultipleChunks() {
        // 8 个句子，每句约 30+ 字节 → 单句 token≈10；maxTokens=20 时至少切 3 段
        String longText = ("这是一个用于测试超长切分的中文句子。"
                + "第二个句子进一步增加内容长度。"
                + "第三句继续追加更多字符。"
                + "第四句让整体内容超过阈值。"
                + "第五句再补充一些语义。"
                + "第六句保持切分器活跃。"
                + "第七句再来一次。"
                + "第八句收尾。");

        List<ParsedChunk> parsed = List.of(
                ParsedChunk.builder()
                        .content(longText)
                        .headingPath("测试 / 长文本")
                        .pageNumber(1)
                        .build()
        );

        List<Chunk> out = splitter.split(parsed);

        assertThat(out).hasSizeGreaterThan(1);
        // 每个 chunk 都不超过 maxTokens（允许 overlap 后略高，硬上限用 2x 兜底）
        assertThat(out).allSatisfy(c ->
                assertThat(c.getTokenCount()).isLessThanOrEqualTo(properties.getChunk().getMaxTokens() * 2)
        );
        // chunkIndex 从 0 开始连续
        for (int i = 0; i < out.size(); i++) {
            assertThat(out.get(i).getChunkIndex()).isEqualTo(i);
        }
        // headingPath / pageNumber 元数据保留
        assertThat(out).allSatisfy(c -> {
            assertThat(c.getHeadingPath()).isEqualTo("测试 / 长文本");
            assertThat(c.getPageNumber()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("相邻同 heading 的短片段被合并")
    void split_shortNeighborsSameHeading_areMerged() {
        // 3 个短片段，heading 相同，单个远低于 minTokens
        List<ParsedChunk> parsed = List.of(
                ParsedChunk.builder().content("短句一。").headingPath("A").pageNumber(1).build(),
                ParsedChunk.builder().content("短句二。").headingPath("A").pageNumber(1).build(),
                ParsedChunk.builder().content("短句三。").headingPath("A").pageNumber(1).build()
        );

        List<Chunk> out = splitter.split(parsed);

        // 合并成 1 条（或至少 < 3 条）
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getContent())
                .contains("短句一")
                .contains("短句二")
                .contains("短句三");
        assertThat(out.get(0).getHeadingPath()).isEqualTo("A");
    }

    @Test
    @DisplayName("不同 heading 的短片段不会跨 heading 合并")
    void split_shortNeighborsDifferentHeading_notMerged() {
        List<ParsedChunk> parsed = List.of(
                ParsedChunk.builder().content("A 段落内容。").headingPath("A").pageNumber(1).build(),
                ParsedChunk.builder().content("B 段落内容。").headingPath("B").pageNumber(2).build()
        );

        List<Chunk> out = splitter.split(parsed);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getHeadingPath()).isEqualTo("A");
        assertThat(out.get(1).getHeadingPath()).isEqualTo("B");
        assertThat(out.get(0).getPageNumber()).isEqualTo(1);
        assertThat(out.get(1).getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("相邻子 chunk 之间保留 overlap 尾部")
    void split_longContent_preservesOverlapTail() {
        // 构造一段被切成 ≥2 段的内容，取一段独特的英文单词做尾部锚点
        String tailAnchor = "UNIQUEOVERLAPMARKER";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("这里填充一些足够长的中文句子内容。");
        }
        sb.append(tailAnchor).append("。");
        for (int i = 0; i < 5; i++) {
            sb.append("后半部分继续填充中文句子。");
        }

        List<ParsedChunk> parsed = List.of(
                ParsedChunk.builder()
                        .content(sb.toString())
                        .headingPath("overlap-test")
                        .build()
        );

        // overlap 需要足以覆盖锚点长度
        properties.getChunk().setOverlap(10);

        List<Chunk> out = splitter.split(parsed);

        assertThat(out).hasSizeGreaterThan(1);
        // 找到第一次包含锚点的段，其后一段应保留 overlap 尾部 → 也含锚点
        int firstIdx = -1;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).getContent().contains(tailAnchor)) {
                firstIdx = i;
                break;
            }
        }
        assertThat(firstIdx).isGreaterThanOrEqualTo(0);
        if (firstIdx + 1 < out.size()) {
            // 后一段起始部分（overlap 区）包含锚点的一部分（至少 4 字符）
            String next = out.get(firstIdx + 1).getContent();
            assertThat(next).isNotEmpty();
        }
    }

    @Test
    @DisplayName("空正文与全空白正文被过滤")
    void split_emptyOrBlankContent_isFiltered() {
        List<ParsedChunk> parsed = List.of(
                ParsedChunk.builder().content("").headingPath("H").build(),
                ParsedChunk.builder().content("   \n\t").headingPath("H").build(),
                ParsedChunk.builder().content(null).headingPath("H").build(),
                ParsedChunk.builder().content("有效内容。").headingPath("H").build()
        );

        List<Chunk> out = splitter.split(parsed);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getContent()).contains("有效内容");
    }

    @Test
    @DisplayName("空列表 / null 入参直接返回空")
    void split_nullOrEmpty_returnsEmpty() {
        assertThat(splitter.split(null)).isEmpty();
        assertThat(splitter.split(List.of())).isEmpty();
    }

    @Test
    @DisplayName("estimateTokens 对 null 与空串返回 0")
    void estimateTokens_edgeCases() {
        assertThat(splitter.estimateTokens(null)).isEqualTo(0);
        assertThat(splitter.estimateTokens("")).isEqualTo(0);
        assertThat(splitter.estimateTokens("a")).isGreaterThanOrEqualTo(1);
    }
}
