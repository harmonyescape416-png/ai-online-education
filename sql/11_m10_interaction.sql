-- ============================================================
-- M10 课程问答与 AI 异步回复（2026-10-20，经用户批准执行）
-- 原始 interaction_question / interaction_reply（14 字段）零改动；
-- 本脚本仅新增两张扩展表，并为 M9 任务表补并发唯一索引（用户批准的顺带修复）。
-- ============================================================

-- 1) 异步 AI 回复任务（learning 库）
-- 状态机：0 PENDING → 1 RUNNING → 2 SUCCESS / 3 FAILED（attempt≤5，next_retry_time 退避重试）
-- 租约：worker_id + lease_until；领取=原子条件更新；超时恢复验证租约过期才可被重新领取
CREATE TABLE IF NOT EXISTS `ai_question_reply_task` (
  `id`             BIGINT       NOT NULL COMMENT '主键（雪花ID）',
  `question_id`    BIGINT       NOT NULL COMMENT '问题ID（interaction_question.id）',
  `action`         VARCHAR(20)  NOT NULL COMMENT '动作：GENERATE首次生成/REGENERATE教师重生成',
  `reply_id`       BIGINT       DEFAULT NULL COMMENT '成功后关联的回复ID（interaction_reply.id）',
  `status`         TINYINT      NOT NULL DEFAULT '0' COMMENT '状态：0待处理 1执行中 2成功 3失败',
  `attempt`        INT          NOT NULL DEFAULT '0' COMMENT '已尝试次数（上限5）',
  `error`          VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
  `operator`       BIGINT       DEFAULT NULL COMMENT '触发人（重生成=教师；首次=学员）',
  `worker_id`      VARCHAR(64)  DEFAULT NULL COMMENT '领取任务的 Worker 实例标识（租约所有权）',
  `lease_until`    DATETIME     DEFAULT NULL COMMENT '租约到期时间（超时未完成可被安全接管）',
  `next_retry_time` DATETIME    DEFAULT NULL COMMENT '下次可重试时间（指数退避）',
  `started_at`     DATETIME     DEFAULT NULL COMMENT '首次开始执行时间',
  `finished_at`    DATETIME     DEFAULT NULL COMMENT '终态时间（成功/最终失败）',
  `create_time`    DATETIME     NOT NULL,
  `update_time`    DATETIME     NOT NULL,
  `active_flag`    TINYINT GENERATED ALWAYS AS (IF(`status` IN (0,1), 1, NULL)) STORED
                                COMMENT '进行中标记（生成列）：进行中为1否则NULL',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_question_active` (`question_id`, `active_flag`),
  KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='课程问答异步 AI 回复任务（M10）';

-- 2) 回复扩展元数据（与 interaction_reply 1:1；来源/修正/精华可追溯）
CREATE TABLE IF NOT EXISTS `interaction_reply_extra` (
  `reply_id`         BIGINT       NOT NULL COMMENT '回复ID（interaction_reply.id，1:1）',
  `question_id`      BIGINT       NOT NULL COMMENT '问题ID（冗余，便于按问题查询来源）',
  `source`           VARCHAR(20)  NOT NULL COMMENT '来源：SYSTEM_AI/TEACHER/STUDENT',
  `ai_task_id`       BIGINT       DEFAULT NULL COMMENT '来源异步任务ID（AI 回复时必填）',
  `model_code`       VARCHAR(30)  DEFAULT NULL COMMENT '生成模型标识（MOCK/DASHSCOPE；真实/MOCK 可追溯）',
  `corrected`        TINYINT      NOT NULL DEFAULT '0' COMMENT '是否经教师人工修正：0否 1是',
  `corrector`        BIGINT       DEFAULT NULL COMMENT '修正教师',
  `correct_time`     DATETIME     DEFAULT NULL COMMENT '修正时间',
  `prev_content`     MEDIUMTEXT   COMMENT '最近一次修正前的完整内容（完整历史见 sys_operation_log 时间线）',
  `featured`         TINYINT      NOT NULL DEFAULT '0' COMMENT '精华标记：0否 1是（≠AI采纳事件）',
  `feature_time`     DATETIME     DEFAULT NULL,
  `feature_operator` BIGINT       DEFAULT NULL,
  PRIMARY KEY (`reply_id`),
  KEY `idx_question` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='互动回复扩展元数据（M10，来源/修正审计/精华）';

-- 3) M9 顺带修复（用户批准）：同一文档同时仅允许一个进行中任务
--    生成列 + 唯一索引彻底消除 REPARSE/REINDEX/DELETE 并发窗口
--    （MySQL 8 不支持 ADD COLUMN IF NOT EXISTS / ADD INDEX IF NOT EXISTS，
--      用 information_schema 判断 + 动态 SQL 保证幂等）
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_index_job'
    AND COLUMN_NAME = 'active_flag');
SET @stmt := IF(@has_col = 0,
  'ALTER TABLE `ai_knowledge_index_job` ADD COLUMN `active_flag` TINYINT GENERATED ALWAYS AS (IF(`status` IN (0,1), 1, NULL)) STORED COMMENT ''进行中标记（生成列）'' AFTER `update_time`',
  'SELECT 1');
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ai_knowledge_index_job` ADD UNIQUE KEY `uk_doc_active` (`doc_id`, `active_flag`)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_index_job'
    AND INDEX_NAME = 'uk_doc_active');
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
