package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ChatMessageSummary;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 针对表【chat_message_summary】的数据库操作Mapper
 * 存储压缩后的会话摘要
 */
@Mapper
public interface ChatMessageSummaryMapper {
    
    /**
     * 插入压缩摘要
     */
    int insert(ChatMessageSummary summary);

    /**
     * 根据ID查询
     */
    ChatMessageSummary selectById(String id);

    /**
     * 根据会话ID查询所有摘要（按创建时间升序）
     */
    List<ChatMessageSummary> selectBySessionId(String sessionId);

    /**
     * 根据会话ID查询最新的摘要
     */
    ChatMessageSummary selectLatestBySessionId(String sessionId);

    /**
     * 根据会话ID删除所有摘要
     */
    int deleteBySessionId(String sessionId);

    /**
     * 根据ID删除
     */
    int deleteById(String id);
}
