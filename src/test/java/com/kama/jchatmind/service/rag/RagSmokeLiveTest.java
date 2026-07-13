package com.kama.jchatmind.service.rag;

import com.kama.jchatmind.config.RagProperties;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * End-to-end RAG smoke test (requires real PostgreSQL + Ollama + DashScope).
 * Enable via: mvnw -Drag.smoke=1 -Dtest=RagSmokeLiveTest test
 */
@SpringBootTest
class RagSmokeLiveTest {

    private static final String KB_ENTERPRISE = "8e72adc5-22dc-4ac1-b9cc-421459e673ab";

    @Autowired
    private RagService ragService;

    @Autowired
    private ChunkBgeM3Mapper chunkMapper;

    @Autowired
    private RagProperties ragProperties;

    @Test
    @EnabledIfSystemProperty(named = "rag.smoke", matches = "1")
    @DisplayName("KB current state + one real retrieval")
    void inspect() {
        long total = chunkMapper.countAll();
        System.out.println("======================================================================");
        System.out.println("chunk_bge_m3 total rows: " + total);
        System.out.println("read.mode = " + ragProperties.getRead().getMode()
                + ", hybrid.enabled = " + ragProperties.getHybrid().isEnabled()
                + ", rerank.enabled = " + ragProperties.getRerank().isEnabled()
                + ", rerank.provider = " + ragProperties.getRerank().getProvider());
        System.out.println("======================================================================");

        String[] questions = {
                "xun jia cai gou de jin e shang xian shi duo shao (inquiry procurement amount limit)",
                "IT service P1 SLA response requirement",
                "employee annual leave days calculation",
                "finance receipts retention years"
        };

        for (String q : questions) {
            long t0 = System.currentTimeMillis();
            var hits = ragService.retrieve(KB_ENTERPRISE, q);
            long ms = System.currentTimeMillis() - t0;

            System.out.println("\n[Q] " + q + "  (" + ms + " ms, " + hits.size() + " hits)");
            for (int i = 0; i < hits.size(); i++) {
                var h = hits.get(i);
                String preview = h.getContent() == null ? "" : h.getContent().replace('\n', ' ');
                if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
                System.out.printf("  #%d  score=%.3f  file=%s  heading=%s  page=%s%n     %s%n",
                        i + 1, h.getScore(),
                        h.getFilename(), h.getHeadingPath(), h.getPageNumber(),
                        preview);
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "rag.smoke", matches = "1")
    @DisplayName("First 5 chunk metadata sample")
    void listSampleChunks() {
        List<com.kama.jchatmind.model.entity.ChunkBgeM3> sample = chunkMapper.selectPageAfter(null, 5);
        System.out.println("\n======================================================================");
        System.out.println("Sample chunks (first 5 rows across all KBs):");
        for (com.kama.jchatmind.model.entity.ChunkBgeM3 c : sample) {
            String contentPreview = c.getContent() == null ? "(null)" : c.getContent();
            if (contentPreview.length() > 60) contentPreview = contentPreview.substring(0, 60) + "...";
            System.out.printf("  id=%s  kb=%s  filename=%s  heading=%s  page=%s  tokens=%s%n     content=%s%n",
                    c.getId(), c.getKbId(),
                    c.getFilename(), c.getHeadingPath(), c.getPageNumber(), c.getTokenCount(),
                    contentPreview);
        }
    }
}
