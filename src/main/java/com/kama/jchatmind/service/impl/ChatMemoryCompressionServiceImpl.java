package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatMessageSummaryMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatMessageSummary;
import com.kama.jchatmind.service.ChatMemoryCompressionService;
import com.kama.jchatmind.service.RedisChatMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话记忆压缩服务实现类
 * 使用 LLM 将历史对话压缩成摘要
 */
@Slf4j
@Service
public class ChatMemoryCompressionServiceImpl implements ChatMemoryCompressionService {

    /**
     * 每 5 轮对话触发一次压缩（1轮 = 用户消息 + AI消息 = 2条）
     */
    private static final int COMPRESS_THRESHOLD = 10;

    /**
     * 保留最近的消息数量（不压缩）
     */
    private static final int KEEP_RECENT_MESSAGES = 10;

    /**
     * 单次压缩输入的字符上限（近似 token 上限）。
     * 超过时从尾部保留 —— 因为尾部对当前对话更相关，头部信息已经在
     * 上一轮的历史摘要里覆盖了。
     */
    private static final int MAX_COMPRESSION_CHARS = 20_000;

    private final RedisChatMemoryService redisChatMemoryService;
    private final ChatMessageSummaryMapper chatMessageSummaryMapper;
    private final ChatMessageMapper chatMessageMapper;

    public ChatMemoryCompressionServiceImpl(
            RedisChatMemoryService redisChatMemoryService,
            ChatMessageSummaryMapper chatMessageSummaryMapper,
            ChatMessageMapper chatMessageMapper
    ) {
        this.redisChatMemoryService = redisChatMemoryService;
        this.chatMessageSummaryMapper = chatMessageSummaryMapper;
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public boolean shouldCompress(String sessionId) {
        long messageCount = redisChatMemoryService.getMessageCount(sessionId);
        return messageCount > COMPRESS_THRESHOLD + KEEP_RECENT_MESSAGES;
    }

    @Override
    public String compress(String sessionId, ChatClient chatClient) {
        log.info("Starting compression for session: {}", sessionId);

        // 1. 获取所有消息
        List<ChatMessageDTO> allMessages = redisChatMemoryService.getAllMessages(sessionId);
        if (allMessages.size() <= KEEP_RECENT_MESSAGES) {
            log.info("Not enough messages to compress for session: {}", sessionId);
            return null;
        }

        // 2. 分割消息：需要压缩的部分和保留的部分
        int compressEndIndex = allMessages.size() - KEEP_RECENT_MESSAGES;
        List<ChatMessageDTO> messagesToCompress = allMessages.subList(0, compressEndIndex);
        List<ChatMessageDTO> messagesToKeep = allMessages.subList(compressEndIndex, allMessages.size());

        // 3. 构建压缩提示词
        String conversationText = formatMessagesForCompression(messagesToCompress);
        String compressionPrompt = buildCompressionPrompt(conversationText);

        // 4. 调用 LLM 生成摘要
        String summary;
        try {
            summary = chatClient.prompt()
                    .user(compressionPrompt)
                    .call()
                    .content();
            log.info("Generated summary for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to generate summary for session: {}", sessionId, e);
            throw new RuntimeException("Failed to generate summary", e);
        }

        // 5. 保存摘要到数据库
        ChatMessageSummary summaryEntity = ChatMessageSummary.builder()
                .sessionId(sessionId)
                .summaryContent(summary)
                .messageRange("1-" + messagesToCompress.size())
                .originalMessageCount(messagesToCompress.size())
                .createdAt(LocalDateTime.now())
                .build();
        chatMessageSummaryMapper.insert(summaryEntity);
        log.info("Saved summary to database for session: {}", sessionId);

        // 6. 清空 Redis 并重新加载保留的消息
        redisChatMemoryService.clearSession(sessionId);
        redisChatMemoryService.addMessages(sessionId, messagesToKeep);

        // 7. 同步清理 DB 中已压缩的老消息，避免下次 loadMemory 走 DB 分支时
        //    把老消息与摘要一起拉回上下文，形成重复放大。
        try {
            int deleted = chatMessageMapper.deleteOldestExcludingRecent(sessionId, KEEP_RECENT_MESSAGES);
            log.info("Purged {} compressed messages from DB for session: {}", deleted, sessionId);
        } catch (Exception e) {
            // 即便 DB 清理失败，摘要仍然是有效的；下次 loadMemory 会取到近期消息 + 摘要。
            // 但会存在少量老消息重复参与上下文，等待下次压缩再收敛。
            log.warn("Failed to purge compressed messages from DB for session: {}", sessionId, e);
        }

        log.info("Compressed session: {}, removed {} messages, kept {} messages",
                sessionId, messagesToCompress.size(), messagesToKeep.size());

        return summary;
    }

    /**
     * 异步压缩入口。调用方（{@link com.kama.jchatmind.agent.JChatMind}）
     * 只需 fire-and-forget，压缩耗时（3-8s LLM 调用）不阻塞 SSE 流式输出。
     *
     * <p>使用默认 {@code taskExecutor}（{@link com.kama.jchatmind.config.AsyncConfig}
     * 定义的 4-10 核心线程池）。</p>
     */
    @Async
    @Override
    public void compressAsync(String sessionId, ChatClient chatClient) {
        try {
            compress(sessionId, chatClient);
        } catch (Exception e) {
            log.error("Async compression failed for session: {}", sessionId, e);
        }
    }

    @Override
    public List<String> getSummaries(String sessionId) {
        List<ChatMessageSummary> summaries = chatMessageSummaryMapper.selectBySessionId(sessionId);
        return summaries.stream()
                .map(ChatMessageSummary::getSummaryContent)
                .collect(Collectors.toList());
    }

    @Override
    public String getLatestSummary(String sessionId) {
        ChatMessageSummary summary = chatMessageSummaryMapper.selectLatestBySessionId(sessionId);
        return summary != null ? summary.getSummaryContent() : null;
    }

    /**
     * 格式化消息为文本，用于压缩
     */
    private String formatMessagesForCompression(List<ChatMessageDTO> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessageDTO message : messages) {
            String role = message.getRole().getRole();
            String content = message.getContent();

            switch (role) {
                case "user":
                    sb.append("用户: ").append(content).append("\n");
                    break;
                case "assistant":
                    sb.append("助手: ").append(content).append("\n");
                    // 如果有工具调用，也记录下来
                    if (message.getMetadata() != null && message.getMetadata().getToolCalls() != null) {
                        List<AssistantMessage.ToolCall> toolCalls = message.getMetadata().getToolCalls();
                        if (!toolCalls.isEmpty()) {
                            sb.append("  [调用工具: ").append(
                                    toolCalls.stream()
                                            .map(AssistantMessage.ToolCall::name)
                                            .collect(Collectors.joining(", "))
                            ).append("]\n");
                        }
                    }
                    break;
                case "tool":
                    sb.append("工具结果: ").append(content).append("\n");
                    break;
                case "system":
                    sb.append("系统: ").append(content).append("\n");
                    break;
                default:
                    sb.append(role).append(": ").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 构建压缩提示词。
     *
     * <p>若 {@code conversationText} 超过 {@link #MAX_COMPRESSION_CHARS} 字符，
     * 保留尾部 —— 因为尾部对当前对话更相关，头部信息会在上一轮的历史摘要中体现，
     * 且避免撞 LLM context 上限 400。</p>
     */
    private String buildCompressionPrompt(String conversationText) {
        String text = conversationText;
        if (text != null && text.length() > MAX_COMPRESSION_CHARS) {
            int truncated = text.length() - MAX_COMPRESSION_CHARS;
            text = "……（此处省略较早的 " + truncated + " 字符）……\n"
                    + text.substring(text.length() - MAX_COMPRESSION_CHARS);
            log.info("Compression input truncated: kept last {} chars, dropped {} chars",
                    MAX_COMPRESSION_CHARS, truncated);
        }
        return """
                请将以下对话历史压缩成简洁的摘要。要求：
                1. 保留关键信息和上下文
                2. 记录用户的主要问题和需求
                3. 记录AI助手给出的重要结论和建议
                4. 如果有工具调用，记录工具的用途和关键结果
                5. 摘要应该简洁明了，便于后续对话参考

                对话历史：
                %s

                请生成压缩摘要：
                """.formatted(text);
    }
}
