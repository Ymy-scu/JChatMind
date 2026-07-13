package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.RedisChatMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 会话记忆管理服务实现类
 * 使用 Redis List 存储会话消息（JSON 字符串），支持 30 分钟过期时间
 */
@Slf4j
@Service
public class RedisChatMemoryServiceImpl implements RedisChatMemoryService {

    private static final String KEY_PREFIX = "chat:session:";
    private static final String MESSAGES_SUFFIX = ":messages";
    private static final long EXPIRE_MINUTES = 30;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private String getKey(String sessionId) {
        return KEY_PREFIX + sessionId + MESSAGES_SUFFIX;
    }

    @Override
    public void addMessage(String sessionId, ChatMessageDTO message) {
        String key = getKey(sessionId);
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().rightPush(key, json);
            refreshExpiration(sessionId);
            log.debug("Added message to Redis session: {}", sessionId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message for session: {}", sessionId, e);
            throw new RuntimeException("Failed to serialize message", e);
        }
    }

    @Override
    public void addMessages(String sessionId, List<ChatMessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String key = getKey(sessionId);
        try {
            List<String> jsonList = new ArrayList<>();
            for (ChatMessageDTO message : messages) {
                jsonList.add(objectMapper.writeValueAsString(message));
            }
            redisTemplate.opsForList().rightPushAll(key, jsonList);
            refreshExpiration(sessionId);
            log.debug("Added {} messages to Redis session: {}", messages.size(), sessionId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize messages for session: {}", sessionId, e);
            throw new RuntimeException("Failed to serialize messages", e);
        }
    }

    @Override
    public List<ChatMessageDTO> getRecentMessages(String sessionId, int limit) {
        String key = getKey(sessionId);
        Long size = redisTemplate.opsForList().size(key);
        if (size == null || size == 0) {
            return Collections.emptyList();
        }

        long start = Math.max(0, size - limit);
        long end = size - 1;

        List<String> jsonList = redisTemplate.opsForList().range(key, start, end);
        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChatMessageDTO> messages = new ArrayList<>();
        for (String json : jsonList) {
            try {
                ChatMessageDTO message = objectMapper.readValue(json, ChatMessageDTO.class);
                messages.add(message);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize message from Redis", e);
            }
        }
        return messages;
    }

    @Override
    public List<ChatMessageDTO> getAllMessages(String sessionId) {
        String key = getKey(sessionId);
        List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChatMessageDTO> messages = new ArrayList<>();
        for (String json : jsonList) {
            try {
                ChatMessageDTO message = objectMapper.readValue(json, ChatMessageDTO.class);
                messages.add(message);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize message from Redis", e);
            }
        }
        return messages;
    }

    @Override
    public long getMessageCount(String sessionId) {
        String key = getKey(sessionId);
        Long size = redisTemplate.opsForList().size(key);
        return size != null ? size : 0;
    }

    @Override
    public void removeMessages(String sessionId, long count) {
        if (count <= 0) {
            return;
        }
        String key = getKey(sessionId);
        for (long i = 0; i < count; i++) {
            redisTemplate.opsForList().leftPop(key);
        }
        log.debug("Removed {} messages from Redis session: {}", count, sessionId);
    }

    @Override
    public void clearSession(String sessionId) {
        String key = getKey(sessionId);
        redisTemplate.delete(key);
        log.debug("Cleared Redis session: {}", sessionId);
    }

    @Override
    public boolean hasSession(String sessionId) {
        String key = getKey(sessionId);
        Boolean exists = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void refreshExpiration(String sessionId) {
        String key = getKey(sessionId);
        redisTemplate.expire(key, EXPIRE_MINUTES, TimeUnit.MINUTES);
        log.debug("Refreshed expiration for Redis session: {}", sessionId);
    }
}
