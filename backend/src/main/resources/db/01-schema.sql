-- ==========================================================
-- AI 电商客服 Agent —— 数据库初始化
-- 容器首次启动时自动执行（docker-entrypoint-initdb.d）
-- ==========================================================

CREATE EXTENSION IF NOT EXISTS vector;
-- 三元组相似度，用于关键词检索通道（word_similarity）。
-- 选它而不是 PG 全文检索，是因为 tsvector 对中文依赖分词器，
-- 而三元组是纯字符切分，开箱即用。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ----------------------------------------------------------
-- 1. 管理员
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL UNIQUE,
    password   VARCHAR(128) NOT NULL,
    nickname   VARCHAR(64),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE sys_user IS '后台管理员账号';

-- ----------------------------------------------------------
-- 2. 知识文档
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_document (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    file_type   VARCHAR(16),
    file_size   BIGINT       NOT NULL DEFAULT 0,
    chunk_count INT          NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PARSING',
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE kb_document IS '知识库文档';
COMMENT ON COLUMN kb_document.status IS 'PARSING / READY / FAILED';

-- ----------------------------------------------------------
-- 3. 知识切片（含 pgvector 向量列）
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_chunk (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT       NOT NULL,
    content     TEXT         NOT NULL,
    embedding   vector(1024),
    chunk_index INT          NOT NULL DEFAULT 0,
    token_count INT          NOT NULL DEFAULT 0
);
COMMENT ON TABLE kb_chunk IS '知识库切片，embedding 为 bge-m3 生成的 1024 维向量';

CREATE INDEX IF NOT EXISTS idx_kb_chunk_doc ON kb_chunk (doc_id);

-- 数据量增长到万级以上时启用向量索引（演示阶段数据量小，先不建以加快写入）：
-- CREATE INDEX idx_kb_chunk_embedding ON kb_chunk USING hnsw (embedding vector_cosine_ops);

-- ----------------------------------------------------------
-- 4. 会话
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_session (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE chat_session IS '对话会话';

-- ----------------------------------------------------------
-- 5. 消息
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_message (
    id                BIGSERIAL PRIMARY KEY,
    session_id        BIGINT      NOT NULL,
    role              VARCHAR(16) NOT NULL,
    content           TEXT,
    trace             JSONB,
    citations         JSONB,
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    first_token_ms    INT         NOT NULL DEFAULT 0,
    total_ms          INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE chat_message IS '对话消息，trace 存完整的 Agent 决策轨迹';
COMMENT ON COLUMN chat_message.role IS 'USER / ASSISTANT';

CREATE INDEX IF NOT EXISTS idx_msg_session ON chat_message (session_id);

-- ----------------------------------------------------------
-- 6. 模拟订单（供工具调用）
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS mock_order (
    id               BIGSERIAL PRIMARY KEY,
    order_no         VARCHAR(32)  NOT NULL UNIQUE,
    user_phone       VARCHAR(20),
    product_name     VARCHAR(128),
    amount           NUMERIC(10, 2),
    status           VARCHAR(16),
    receiver_address VARCHAR(255),
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE mock_order IS '模拟订单数据，供 query_order / apply_refund / update_address 工具调用';

CREATE INDEX IF NOT EXISTS idx_order_phone ON mock_order (user_phone);

-- ----------------------------------------------------------
-- 7. 模拟物流
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS mock_logistics (
    id        BIGSERIAL PRIMARY KEY,
    order_no  VARCHAR(32) NOT NULL,
    node_time TIMESTAMP,
    node_desc VARCHAR(255),
    operator  VARCHAR(64)
);
COMMENT ON TABLE mock_logistics IS '模拟物流轨迹，供 query_logistics 工具调用';

CREATE INDEX IF NOT EXISTS idx_logistics_order ON mock_logistics (order_no);
