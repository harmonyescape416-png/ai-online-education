-- =============================================================
-- M9 知识库扩展表（用户已确认：原方案 + 审核状态语义调整）
-- 原始 12 张核心表零改动；幂等执行
-- ai_knowledge_document：知识文档元数据（审核状态：0待审核 1已发布 2已撤回；
--   仅「已发布」文档可被 RAG 检索，检索时以 MySQL 实时状态为权威）
-- ai_knowledge_index_job：索引任务（状态机：0待处理 1执行中 2成功 3失败；
--   失败可重试；服务重启时执行中任务重置为待处理补偿）
-- 切片正文存 Elasticsearch（docId/chunkIndex 可追溯），MySQL 仅存计数
-- =============================================================

CREATE TABLE IF NOT EXISTS `ai_knowledge_document` (
  `id`            BIGINT       NOT NULL COMMENT '主键（雪花ID）',
  `title`         VARCHAR(200) NOT NULL COMMENT '文档名称',
  `doc_type`      VARCHAR(20)  NOT NULL COMMENT '文档类型：PDF/MARKDOWN',
  `course_id`     BIGINT       DEFAULT NULL COMMENT '关联课程ID（可空；PROTECTED 级必填）',
  `file_name`     VARCHAR(255) NOT NULL COMMENT '原始文件名（规范化后）',
  `file_path`     VARCHAR(500) NOT NULL COMMENT '受控存储目录下的相对路径（服务端生成，不拼接用户输入）',
  `file_size`     BIGINT       NOT NULL COMMENT '文件大小（字节）',
  `sha256`        CHAR(64)     NOT NULL COMMENT '文件 SHA-256（重复上传检测）',
  `char_count`    INT          NOT NULL DEFAULT 0 COMMENT '解析后正文字符数（码点）',
  `chunk_count`   INT          NOT NULL DEFAULT 0 COMMENT '切片数量',
  `index_version` INT          DEFAULT NULL COMMENT '当前生效的 ES 索引版本号',
  `index_status`  TINYINT      NOT NULL DEFAULT 0 COMMENT '索引状态：0待处理 1解析中 2已切片 3索引中 4索引成功 5失败',
  `fail_reason`   VARCHAR(500) DEFAULT NULL COMMENT '解析或索引失败原因',
  `access_level`  VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC' COMMENT '访问级别：PUBLIC/PROTECTED/INTERNAL',
  `review_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '审核状态：0待审核 1已发布 2已撤回（仅 1 可被检索）',
  `reviewer`      BIGINT       DEFAULT NULL COMMENT '审核操作人',
  `review_time`   DATETIME     DEFAULT NULL COMMENT '审核时间',
  `publisher`     BIGINT       DEFAULT NULL COMMENT '上传人',
  `create_time`   DATETIME     NOT NULL,
  `update_time`   DATETIME     NOT NULL,
  `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sha256` (`sha256`),
  KEY `idx_course` (`course_id`),
  KEY `idx_status` (`index_status`, `review_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识库文档（M9）';

CREATE TABLE IF NOT EXISTS `ai_knowledge_index_job` (
  `id`          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
  `doc_id`      BIGINT       NOT NULL COMMENT '文档ID',
  `action`      VARCHAR(20)  NOT NULL COMMENT '任务动作：REPARSE/REINDEX/DELETE',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0待处理 1执行中 2成功 3失败',
  `attempt`     INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数（重试上限 5）',
  `error`       VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
  `operator`    BIGINT       DEFAULT NULL COMMENT '操作人',
  `create_time` DATETIME     NOT NULL,
  `update_time` DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_doc_status` (`doc_id`, `status`),
  KEY `idx_status_pending` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识库索引任务（M9）';
