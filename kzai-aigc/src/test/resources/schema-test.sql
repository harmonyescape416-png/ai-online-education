-- aigc 集成测试用 H2 表结构（与 MySQL 字段逐一对应）
-- M6：chat_session（8 字段零增删）
DROP TABLE IF EXISTS chat_session;
CREATE TABLE chat_session (
    id          BIGINT       NOT NULL PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    title       VARCHAR(100) DEFAULT NULL,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    creater     BIGINT       DEFAULT NULL,
    updater     BIGINT       DEFAULT NULL
);
CREATE UNIQUE INDEX uk_session_id ON chat_session (session_id);
CREATE INDEX idx_user_time ON chat_session (user_id, update_time);

-- M9：知识库文档与索引任务
DROP TABLE IF EXISTS ai_knowledge_document;
CREATE TABLE ai_knowledge_document (
    id            BIGINT       NOT NULL PRIMARY KEY,
    title         VARCHAR(200) NOT NULL,
    doc_type      VARCHAR(20)  NOT NULL,
    course_id     BIGINT       DEFAULT NULL,
    file_name     VARCHAR(255) NOT NULL,
    file_path     VARCHAR(500) NOT NULL,
    file_size     BIGINT       NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    char_count    INT          NOT NULL DEFAULT 0,
    chunk_count   INT          NOT NULL DEFAULT 0,
    index_version INT          DEFAULT NULL,
    index_status  TINYINT      NOT NULL DEFAULT 0,
    fail_reason   VARCHAR(500) DEFAULT NULL,
    access_level  VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    review_status TINYINT       NOT NULL DEFAULT 0,
    publish_generation INT     NOT NULL DEFAULT 0,
    reviewer      BIGINT       DEFAULT NULL,
    review_time   TIMESTAMP    DEFAULT NULL,
    publisher     BIGINT       DEFAULT NULL,
    source_resource_id BIGINT  DEFAULT NULL,
    source_version VARCHAR(64) DEFAULT NULL,
    create_time   TIMESTAMP    NOT NULL,
    update_time   TIMESTAMP    NOT NULL,
    deleted       TINYINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_source_resource ON ai_knowledge_document (source_resource_id, source_version);

DROP TABLE IF EXISTS ai_knowledge_index_job;
CREATE TABLE ai_knowledge_index_job (
    id          BIGINT      NOT NULL PRIMARY KEY,
    doc_id      BIGINT      NOT NULL,
    action      VARCHAR(20) NOT NULL,
    status      TINYINT     NOT NULL DEFAULT 0,
    attempt     INT         NOT NULL DEFAULT 0,
    error       VARCHAR(500) DEFAULT NULL,
    operator    BIGINT      DEFAULT NULL,
    publish_generation INT  NOT NULL DEFAULT 0,
    create_time TIMESTAMP   NOT NULL,
    update_time TIMESTAMP   NOT NULL
);
