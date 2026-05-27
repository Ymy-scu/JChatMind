package com.kama.jchatmind.config;

import com.kama.jchatmind.advisor.QueryExpansionAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MultiChatClientConfig {

    @Bean("deepseek-chat")
    public ChatClient deepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.builder(deepSeekChatModel)
                .defaultAdvisors(new QueryExpansionAdvisor(deepSeekChatModel))
                .build();
    }

    @Bean("glm-4.7-flash")
    public ChatClient zhiPuAiChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
        return ChatClient.builder(zhiPuAiChatModel)
                .defaultAdvisors(new QueryExpansionAdvisor(zhiPuAiChatModel))
                .build();
    }

    @Bean("qwen-plus")
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors(new QueryExpansionAdvisor(openAiChatModel))
                .build();
    }
}
