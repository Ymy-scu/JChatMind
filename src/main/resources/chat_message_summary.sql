-- 会话压缩摘要表
CREATE TABLE IF NOT EXISTS chat_message_summary (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    
    summary_content TEXT NOT NULL,                  -- 压缩后的摘要内容
    message_range TEXT,                             -- 压缩的消息范围，如 "1-10"
    original_message_count INTEGER,                 -- 压缩的原始消息数量
    
    created_at TIMESTAMP DEFAULT NOW()
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_chat_message_summary_session_id 
ON chat_message_summary(session_id);

CREATE INDEX IF NOT EXISTS idx_chat_message_summary_created_at 
ON chat_message_summary(session_id, created_at DESC);
