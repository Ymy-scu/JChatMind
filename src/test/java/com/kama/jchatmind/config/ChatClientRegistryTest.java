package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatClientRegistryTest {

    @Test
    void shouldReturnChatClientByKey() {
        ChatClient deepseek = mock(ChatClient.class);
        ChatClient qwen = mock(ChatClient.class);
        Map<String, ChatClient> clients = Map.of(
                "deepseek-chat", deepseek,
                "qwen-plus", qwen
        );
        ChatClientRegistry registry = new ChatClientRegistry(clients);

        assertSame(deepseek, registry.get("deepseek-chat"));
        assertSame(qwen, registry.get("qwen-plus"));
    }

    @Test
    void shouldReturnNullForUnknownKey() {
        ChatClientRegistry registry = new ChatClientRegistry(Map.of());
        assertNull(registry.get("unknown-model"));
    }

    @Test
    void shouldReturnNullForNullKey() {
        ChatClient mockClient = mock(ChatClient.class);
        java.util.HashMap<String, ChatClient> map = new java.util.HashMap<>();
        map.put("model", mockClient);
        ChatClientRegistry registry = new ChatClientRegistry(map);
        assertNull(registry.get(null));
    }
}
