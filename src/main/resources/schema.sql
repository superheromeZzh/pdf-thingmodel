-- =====================================================================
-- pdf-thingmodel: GaussDB / openGauss 建表脚本（精简版）
--
-- 三张表 1:N:1 关系：
--   documents (1) ── (N) document_chunks (1) ── (1) thing_models
--
-- 时间戳约定：
--   * 所有表带 created_at（INSERT 时自动写入 now()）
--   * thing_models 是唯一会被 UPDATE 的表（upsert 走 ON CONFLICT DO UPDATE），
--     额外带 updated_at + 触发器（UPDATE 时自动 now()）
--   * documents / document_chunks 当前没有 UPDATE 路径，只 created_at
-- =====================================================================

-- 通用触发器函数：UPDATE 时自动把 updated_at 推到 now()
-- PL/pgSQL 在 openGauss / GaussDB(centralized & distributed) 都支持
CREATE OR REPLACE FUNCTION trg_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 1) documents：一份 PDF 一行
CREATE TABLE IF NOT EXISTS documents (
    document_id     BIGSERIAL    PRIMARY KEY,
    document_name   VARCHAR(512) NOT NULL,
    page_count      INTEGER      NOT NULL,
    -- 文档摘要（阶段 1 便宜 LLM 一次性产出）。一段 150-300 字叙述，给编辑期当文档语境。
    summary         TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE documents IS 'PDF 文档元信息 + 文档摘要';

-- 2) document_chunks：按 API/章节切的页段（append-only）
CREATE TABLE IF NOT EXISTS document_chunks (
    chunk_id        BIGSERIAL    PRIMARY KEY,
    document_id     BIGINT       NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    chunk_name      VARCHAR(512) NOT NULL,
    page_start      INTEGER      NOT NULL,
    page_end        INTEGER      NOT NULL,
    raw_text        TEXT,
    summary         TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_chunks_doc_name UNIQUE (document_id, chunk_name)
);
CREATE INDEX IF NOT EXISTS idx_chunks_doc   ON document_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_pages ON document_chunks(document_id, page_start, page_end);

-- 3) thing_models：每个 chunk 一个当前生效物模型
CREATE TABLE IF NOT EXISTS thing_models (
    chunk_id        BIGINT       PRIMARY KEY REFERENCES document_chunks(chunk_id) ON DELETE CASCADE,
    model           JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- JSONB 反查：找出当前所有物模型里包含某字段的（"全局批量改"用得上）
CREATE INDEX IF NOT EXISTS idx_tm_model_gin
    ON thing_models USING GIN (model jsonb_path_ops);

DROP TRIGGER IF EXISTS thing_models_set_updated_at ON thing_models;
CREATE TRIGGER thing_models_set_updated_at
    BEFORE UPDATE ON thing_models
    FOR EACH ROW EXECUTE PROCEDURE trg_set_updated_at();
