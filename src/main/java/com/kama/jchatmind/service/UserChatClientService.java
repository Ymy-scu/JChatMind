package com.kama.jchatmind.service;

/**
 * 用户级别的 ChatClient 服务
 * 支持根据用户的 API Key 和模型配置动态创建 ChatClient
 */
public interface UserChatClientService {

    /**
     * 获取用户的 ChatClient
     *
     * @param userId 用户 ID
     * @param modelKey 模型标识（如 "deepseek-chat", "glm-4.7-flash" 等）
     * @return ChatClient 实例
     */
    org.springframework.ai.chat.client.ChatClient getChatClient(String userId, String modelKey);

    /**
     * 获取用户的默认 ChatClient
     *
     * @param userId 用户 ID
     * @return ChatClient 实例
     */
    org.springframework.ai.chat.client.ChatClient getDefaultChatClient(String userId);
}
