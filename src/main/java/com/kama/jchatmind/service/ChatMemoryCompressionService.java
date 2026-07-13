package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ChatMessageDTO;

import java.util.List;

/**
 * 会话记忆压缩服务
 * 负责将历史对话消息压缩成摘要，减少上下文长度
 */
public interface ChatMemoryCompressionService {

    /**
     * 检查是否需要压缩
     * 每 5 轮对话（10条消息）触发一次压缩
     *
     * @param sessionId 会话ID
     * @return 是否需要压缩
     */
    boolean shouldCompress(String sessionId);

    /**
     * 执行压缩操作
     * 1. 从 Redis 获取需要压缩的消息
     * 2. 调用 LLM 生成摘要
     * 3. 保存摘要到数据库
     * 4. 从 Redis 中删除已压缩的消息
     *
     * @param sessionId 会话ID
     * @param chatClient 用于压缩的 ChatClient
     * @return 压缩后的摘要内容
     */
    String compress(String sessionId, org.springframework.ai.chat.client.ChatClient chatClient);

    /**
     * 获取会话的所有压缩摘要
     *
     * @param sessionId 会话ID
     * @return 摘要列表
     */
    List<String> getSummaries(String sessionId);

    /**
     * 获取会话的最新压缩摘要
     *
     * @param sessionId 会话ID
     * @return 最新的摘要内容，如果没有则返回 null
     */
    String getLatestSummary(String sessionId);
}
