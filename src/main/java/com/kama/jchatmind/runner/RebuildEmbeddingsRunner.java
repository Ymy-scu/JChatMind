package com.kama.jchatmind.runner;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.rag.TokenAwareSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史 chunk 数据回填 runner。
 *
 * <p>解决旧代码 {@code embed(title)} 引入的 embedding 与 content 不一致问题：
 * 逐条按 id 游标扫描 {@code chunk_bge_m3}，对每条 chunk 的 {@code content} 重新做
 * embedding，并顺便回填 {@code token_count}（历史行可能为空）。</p>
 *
 * <p><b>激活方式：</b></p>
 * <pre>
 * java -jar app.jar --spring.profiles.active=rebuild-embeddings \
 *      --rag.rebuild.rate=5 \
 *      --rag.rebuild.page-size=200
 * </pre>
 *
 * <p><b>特性：</b></p>
 * <ul>
 *   <li>限速：默认 5 qps，避免打爆 embedding 服务</li>
 *   <li>断点续跑：进度写入 {@code data/rag-rebuild-progress.txt}，重启后自动接着跑</li>
 *   <li>只回填 embedding 与 token_count，其余列保持原样，出错跳过并 WARN</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("rebuild-embeddings")
public class RebuildEmbeddingsRunner implements ApplicationRunner {

    /** 断点续跑：把 lastProcessedId 落地到一个文件，重启后接着跑 */
    private static final Path PROGRESS_FILE = Paths.get("data", "rag-rebuild-progress.txt");

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final RagService ragService;
    private final TokenAwareSplitter tokenAwareSplitter;

    /** 每秒最多处理多少条 chunk（限速） */
    @Value("${rag.rebuild.rate:5}")
    private int rate;

    /** 每页扫描多少条 chunk */
    @Value("${rag.rebuild.page-size:200}")
    private int pageSize;

    public RebuildEmbeddingsRunner(ChunkBgeM3Mapper chunkBgeM3Mapper,
                                   RagService ragService,
                                   TokenAwareSplitter tokenAwareSplitter) {
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.ragService = ragService;
        this.tokenAwareSplitter = tokenAwareSplitter;
    }

    @Override
    public void run(ApplicationArguments args) {
        long total = chunkBgeM3Mapper.countAll();
        log.info("开始回填 embedding，总数 = {}, rate = {}/s, pageSize = {}", total, rate, pageSize);

        String cursor = loadCursor();
        if (cursor != null && !cursor.isBlank()) {
            log.info("检测到断点，从 id > {} 继续", cursor);
        }

        long processed = 0;
        long updated = 0;
        long failed = 0;
        long intervalNanos = rate > 0 ? 1_000_000_000L / rate : 0L;

        while (true) {
            List<ChunkBgeM3> page = chunkBgeM3Mapper.selectPageAfter(cursor, pageSize);
            if (page.isEmpty()) {
                break;
            }
            for (ChunkBgeM3 chunk : page) {
                long start = System.nanoTime();
                try {
                    String content = chunk.getContent();
                    if (content == null || content.isBlank()) {
                        log.warn("跳过空正文 chunk: id={}", chunk.getId());
                    } else {
                        float[] embedding = ragService.embed(content);
                        ChunkBgeM3 patch = ChunkBgeM3.builder()
                                .id(chunk.getId())
                                .embedding(embedding)
                                .tokenCount(tokenAwareSplitter.estimateTokens(content))
                                .updatedAt(LocalDateTime.now())
                                .build();
                        chunkBgeM3Mapper.updateById(patch);
                        updated++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("回填失败: id={}, err={}", chunk.getId(), e.getMessage());
                }
                cursor = chunk.getId();
                processed++;

                if (processed % 100 == 0) {
                    log.info("进度: {} / {} (updated={}, failed={})", processed, total, updated, failed);
                    saveCursor(cursor);
                }

                // 限速：保证平均间隔 ≥ 1s / rate
                if (intervalNanos > 0) {
                    long elapsed = System.nanoTime() - start;
                    long sleep = intervalNanos - elapsed;
                    if (sleep > 0) {
                        try {
                            Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.warn("回填被中断，最后 cursor = {}", cursor);
                            saveCursor(cursor);
                            return;
                        }
                    }
                }
            }
            saveCursor(cursor);
        }

        log.info("回填完成: processed={}, updated={}, failed={}", processed, updated, failed);
        // 全量完成后清空断点，避免下次误跑
        try {
            Files.deleteIfExists(PROGRESS_FILE);
        } catch (IOException ignored) {
        }
    }

    private String loadCursor() {
        try {
            if (Files.exists(PROGRESS_FILE)) {
                return Files.readString(PROGRESS_FILE).trim();
            }
        } catch (IOException e) {
            log.warn("读取进度文件失败，从头开始: {}", e.getMessage());
        }
        return null;
    }

    private void saveCursor(String cursor) {
        if (cursor == null) return;
        try {
            Files.createDirectories(PROGRESS_FILE.getParent());
            Files.writeString(PROGRESS_FILE, cursor,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("写入进度文件失败: {}", e.getMessage());
        }
    }
}
