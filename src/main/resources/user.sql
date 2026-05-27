-- 用户表
CREATE TABLE IF NOT EXISTS "user" (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(50),
    avatar_url VARCHAR(500),
    status INTEGER DEFAULT 1,  -- 1: 启用, 0: 禁用
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 用户配置表（存储用户的 API Key 和模型配置）
CREATE TABLE IF NOT EXISTS user_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,          -- 模型提供商：deepseek, zhipuai, openai, custom
    api_key VARCHAR(500) NOT NULL,          -- API Key（加密存储）
    base_url VARCHAR(500),                  -- 自定义 API 地址
    model_name VARCHAR(100) NOT NULL,       -- 模型名称
    is_default BOOLEAN DEFAULT FALSE,       -- 是否为默认模型
    config JSONB,                           -- 其他配置（温度、top_p 等）
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, provider, model_name)
);

-- 给 agent 表添加 user_id 字段（关联用户）
ALTER TABLE agent ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES "user"(id) ON DELETE CASCADE;

-- 给 knowledge_base 表添加 user_id 字段（关联用户）
ALTER TABLE knowledge_base ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES "user"(id) ON DELETE CASCADE;

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_user_config_user_id ON user_config(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_user_id ON agent(user_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_base_user_id ON knowledge_base(user_id);
