package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @author charon
 * @description 针对表【chat_message】的数据库操作Mapper
 * @createDate 2025-12-02 15:40:13
 * @Entity com.kama.jchatmind.model.entity.ChatMessage
 */
@Mapper
public interface ChatMessageMapper {
    int insert(ChatMessage chatMessage);

    ChatMessage selectById(String id);

    List<ChatMessage> selectBySessionId(String sessionId);

    List<ChatMessage> selectBySessionIdRecently(String sessionId, int limit);

    int deleteById(String id);

    int updateById(ChatMessage chatMessage);

    /**
     * 删除会话中除最近 {@code keepRecent} 条之外的所有历史消息。
     *
     * <p>用于压缩摘要生成成功后清理已压缩的老消息，防止下次
     * {@link #selectBySessionIdRecently(String, int)} 重新把这些消息
     * 拉回上下文，与已存在的摘要形成重复。</p>
     *
     * @param sessionId 会话 ID
     * @param keepRecent 保留的最新消息条数（按 created_at DESC）
     * @return 被删除的行数
     */
    int deleteOldestExcludingRecent(String sessionId, int keepRecent);
}
