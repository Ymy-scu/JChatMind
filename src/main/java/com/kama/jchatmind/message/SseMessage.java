package com.kama.jchatmind.message;

import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.rag.RetrievedChunk;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
@Builder
public class SseMessage {

    private Type type;
    private Payload payload;
    private Metadata metadata;

    @Data
    @AllArgsConstructor
    @Builder
    public static class Payload {
        private ChatMessageVO message;
        private String contentDelta;
        private String statusText;
        private Boolean done;
        /** 引用溯源结果，仅在 {@link Type#AI_REFERENCES} 事件中携带 */
        private List<RetrievedChunk> references;
    }

    @Data
    @AllArgsConstructor
    @Builder
    public static class Metadata {
        private String chatMessageId;
    }

    // 自定义消息类型
    // 1. AI 生成
    // 2. AI 规划中
    // 3. AI 思考中
    // 4. AI 执行中
    // 5. AI 完成
    // 6. AI 引用溯源（RAG 命中的 chunk 元数据）
    public enum Type {
        AI_GENERATED_CONTENT,
        AI_CONTENT_DELTA,
        AI_PLANNING,
        AI_THINKING,
        AI_EXECUTING,
        AI_DONE,
        AI_REFERENCES,
    }
}
