package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ChatMessageDTO;

import java.util.List;

/**
 * Redis 会话记忆管理服务
 * 负责管理 Redis 中的会话消息，支持消息存储、查询、删除和过期管理
 */
public interface RedisChatMemoryService {

    /**
     * 添加消息到 Redis 会话列表
     * 同时刷新过期时间（30分钟）
     *
     * @param sessionId 会话ID
     * @param message 消息对象
     */
    void addMessage(String sessionId, ChatMessageDTO message);

    /**
     * 批量添加消息到 Redis 会话列表
     *
     * @param sessionId 会话ID
     * @param messages 消息列表
     */
    void addMessages(String sessionId, List<ChatMessageDTO> messages);

    /**
     * 获取会话的最近 N 条消息
     *
     * @param sessionId 会话ID
     * @param limit 消息数量限制
     * @return 消息列表（按时间正序）
     */
    List<ChatMessageDTO> getRecentMessages(String sessionId, int limit);

    /**
     * 获取会话的所有消息
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    List<ChatMessageDTO> getAllMessages(String sessionId);

    /**
     * 获取会话消息总数
     *
     * @param sessionId 会话ID
     * @return 消息数量
     */
    long getMessageCount(String sessionId);

    /**
     * 删除会话的指定范围消息
     * 用于压缩后清理已压缩的消息
     *
     * @param sessionId 会话ID
     * @param count 要删除的消息数量（从左侧开始）
     */
    void removeMessages(String sessionId, long count);

    /**
     * 清空会话的所有消息
     *
     * @param sessionId 会话ID
     */
    void clearSession(String sessionId);

    /**
     * 检查会话是否存在
     *
     * @param sessionId 会话ID
     * @return 是否存在
     */
    boolean hasSession(String sessionId);

    /**
     * 刷新会话过期时间
     *
     * @param sessionId 会话ID
     */
    void refreshExpiration(String sessionId);
}
