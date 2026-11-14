-- B2 文档版本管理 DDL 参考（实际由 Hibernate ddl-auto: update 等价生成）
-- 版本号顺延：V2（V1 为原项目初始结构，由 JPA 自动建表，无脚本）

CREATE TABLE IF NOT EXISTS kb_document_version (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT       NOT NULL,
    version_no   INT          NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    file_size    BIGINT,
    file_type    VARCHAR(50),
    content_hash CHAR(64)     NOT NULL,
    storage_key  VARCHAR(500),
    change_note  VARCHAR(500),
    created_by   BIGINT,
    create_time  TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_doc_version UNIQUE (document_id, version_no)
);

CREATE INDEX IF NOT EXISTS idx_doc_version_document ON kb_document_version (document_id);

ALTER TABLE kb_text_chunk ADD COLUMN IF NOT EXISTS document_version_id BIGINT;
ALTER TABLE kb_text_chunk ADD COLUMN IF NOT EXISTS char_offset INT;
