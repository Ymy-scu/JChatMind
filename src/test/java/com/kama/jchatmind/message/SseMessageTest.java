package com.kama.jchatmind.message;

import com.kama.jchatmind.model.vo.ChatMessageVO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SseMessageTest {

    @Test
    void shouldBuildFullSseMessage() {
        ChatMessageVO msg = ChatMessageVO.builder()
                .id("msg-1")
                .content("hello")
                .build();

        SseMessage message = SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder()
                        .message(msg)
                        .contentDelta("hello")
                        .done(false)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId("msg-1")
                        .build())
                .build();

        assertEquals(SseMessage.Type.AI_GENERATED_CONTENT, message.getType());
        assertEquals("msg-1", message.getMetadata().getChatMessageId());
        assertEquals("hello", message.getPayload().getContentDelta());
        assertFalse(message.getPayload().getDone());
    }

    @Test
    void shouldBuildContentDeltaMessage() {
        SseMessage delta = SseMessage.builder()
                .type(SseMessage.Type.AI_CONTENT_DELTA)
                .payload(SseMessage.Payload.builder()
                        .contentDelta("逐字")
                        .build())
                .build();

        assertEquals(SseMessage.Type.AI_CONTENT_DELTA, delta.getType());
        assertEquals("逐字", delta.getPayload().getContentDelta());
    }

    @Test
    void shouldBuildDoneMessageWithoutPayload() {
        SseMessage done = SseMessage.builder()
                .type(SseMessage.Type.AI_DONE)
                .payload(SseMessage.Payload.builder().build())
                .build();

        assertEquals(SseMessage.Type.AI_DONE, done.getType());
        assertNull(done.getPayload().getDone());
    }

    @Test
    void shouldHaveAllEventTypes() {
        // AI_REFERENCES 由 improve-rag-core-quality 变更引入，用于引用溯源
        assertEquals(7, SseMessage.Type.values().length);
        assertNotNull(SseMessage.Type.valueOf("AI_GENERATED_CONTENT"));
        assertNotNull(SseMessage.Type.valueOf("AI_CONTENT_DELTA"));
        assertNotNull(SseMessage.Type.valueOf("AI_PLANNING"));
        assertNotNull(SseMessage.Type.valueOf("AI_THINKING"));
        assertNotNull(SseMessage.Type.valueOf("AI_EXECUTING"));
        assertNotNull(SseMessage.Type.valueOf("AI_DONE"));
        assertNotNull(SseMessage.Type.valueOf("AI_REFERENCES"));
    }
}
