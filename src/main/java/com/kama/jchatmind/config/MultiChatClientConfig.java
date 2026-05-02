package com.kama.jchatmind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MultiChatClientConfig {
    // deepseek
    @Bean("deepseek-chat")
    public ChatClient deepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.create(deepSeekChatModel);
    }

    // zhipuai
    @Bean("glm-4.7-flash")
    public ChatClient zhiPuAiChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
        return ChatClient.create(zhiPuAiChatModel);
    }

    @Bean("qwen-plus")
    public ChatClient OpenAiChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.create(openAiChatModel);
    }

}
