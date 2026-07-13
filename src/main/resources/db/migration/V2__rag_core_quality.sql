-- =============================================================================
-- V2__rag_core_quality.sql
-- 目标：修复 RAG 索引与元数据缺失
--   1. chunk_bge_m3 新增列：filename / page_number / heading_path /
--      chunk_index / token_count / content_tsv(tsvector 生成列)
--   2. 新建 GIN 索引 idx_chunk_content_tsv 支持 BM25 关键词召回
--   3. 新建 HNSW cosine 索引 idx_chunk_embedding_cosine 用于向量召回
--      （老的 ivfflat + vector_l2_ops 索引 idx_chunk_embedding 保留，直到
--       历史 chunk 全量回填完成后由后续变更删除）
--   4. chat_message 新增列 references_json（JSONB），用于持久化引用溯源
--
-- 迁移原则：新增列均可空、生成列不影响旧代码；CONCURRENTLY 建索引不阻塞写入
-- =============================================================================

-- 1) 新增元数据列
ALTER TABLE chunk_bge_m3
    ADD COLUMN IF NOT EXISTS filename       TEXT,
    ADD COLUMN IF NOT EXISTS page_number    INTEGER,
    ADD COLUMN IF NOT EXISTS heading_path   TEXT,
    ADD COLUMN IF NOT EXISTS chunk_index    INTEGER,
    ADD COLUMN IF NOT EXISTS token_count    INTEGER;

-- 2) 用于 BM25 关键词召回的 tsvector 生成列
--    先用 'simple' 分词器覆盖英文/代码/专有名词；中文分词器（zhparser）留到后续变更
ALTER TABLE chunk_bge_m3
    ADD COLUMN IF NOT EXISTS content_tsv tsvector
        GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED;

-- 3) GIN 索引（tsvector）
--    在生产环境请显式使用 CONCURRENTLY 手动执行：
--      CREATE INDEX CONCURRENTLY idx_chunk_content_tsv ON chunk_bge_m3 USING GIN(content_tsv);
--    这里为了迁移工具（Flyway/init）能一次跑通，使用普通 CREATE INDEX。
CREATE INDEX IF NOT EXISTS idx_chunk_content_tsv
    ON chunk_bge_m3 USING GIN (content_tsv);

-- 4) HNSW cosine 索引（向量召回）
--    bge-m3 官方推荐 cosine；HNSW 相比 ivfflat 无需 ANALYZE、召回率更稳
--    生产 CONCURRENTLY 手动执行推荐参数：m=16, ef_construction=64
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_cosine
    ON chunk_bge_m3 USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 5) chat_message 增加引用溯源列
ALTER TABLE chat_message
    ADD COLUMN IF NOT EXISTS references_json JSONB;

-- 6) 便于按 doc_id + chunk_index 快速定位与去重
CREATE INDEX IF NOT EXISTS idx_chunk_doc_id_chunk_index
    ON chunk_bge_m3 (doc_id, chunk_index);
