-- 学习服务集成测试库结构（H2，MySQL 兼容模式）
-- learning_lesson 为原始 10 字段，learning_lesson_progress 为 M5 扩展表（与 sql/07_m5_learning.sql 一致）
CREATE TABLE IF NOT EXISTS `learning_lesson` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `course_id` BIGINT NOT NULL,
  `chapter_id` BIGINT NULL,
  `section_id` BIGINT NULL,
  `status` TINYINT NOT NULL DEFAULT 0,
  `progress` INT NOT NULL DEFAULT 0,
  `last_study_time` DATETIME NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_course_section` (`user_id`, `course_id`, `section_id`)
);

CREATE TABLE IF NOT EXISTS `learning_fact_coverage` (
  `id` BIGINT NOT NULL,
  `facts_since` DATETIME NOT NULL,
  PRIMARY KEY (`id`)
);
INSERT INTO `learning_fact_coverage` (`id`, `facts_since`)
SELECT 1, '2000-01-01 00:00:00' WHERE NOT EXISTS (SELECT 1 FROM `learning_fact_coverage` WHERE `id` = 1);

CREATE TABLE IF NOT EXISTS `learning_activity_fact` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `course_id` BIGINT NOT NULL,
  `chapter_id` BIGINT NOT NULL,
  `biz_date` DATE NOT NULL,
  `study_seconds` BIGINT NOT NULL DEFAULT 0,
  `chapter_first_finished` TINYINT NOT NULL DEFAULT 0,
  `course_first_finished` TINYINT NOT NULL DEFAULT 0,
  `activity_time` DATETIME NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_chapter_day` (`user_id`, `course_id`, `chapter_id`, `biz_date`)
);

CREATE TABLE IF NOT EXISTS `learning_first_event` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `course_id` BIGINT NOT NULL,
  `chapter_id` BIGINT NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `biz_date` DATE NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_first_event` (`user_id`, `course_id`, `chapter_id`, `event_type`)
);

CREATE TABLE IF NOT EXISTS `learning_lesson_progress` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `course_id` BIGINT NOT NULL,
  `chapter_id` BIGINT NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0,
  `progress` INT NOT NULL DEFAULT 0,
  `video_position_sec` INT NOT NULL DEFAULT 0,
  `video_duration_sec` INT NULL,
  `study_seconds` BIGINT NOT NULL DEFAULT 0,
  `finish_time` DATETIME NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_chapter` (`user_id`, `course_id`, `chapter_id`)
);

-- M10：课程问答（原始 14 字段两表 + 两张扩展表）
CREATE TABLE IF NOT EXISTS `interaction_question` (
  `id` BIGINT NOT NULL,
  `title` VARCHAR(100) NOT NULL,
  `description` VARCHAR(2000) NOT NULL,
  `course_id` BIGINT NOT NULL,
  `chapter_id` BIGINT NULL,
  `section_id` BIGINT NULL,
  `user_id` BIGINT NOT NULL,
  `latest_answer_id` BIGINT NULL,
  `answer_times` INT NOT NULL DEFAULT 0,
  `anonymity` TINYINT NOT NULL DEFAULT 0,
  `hidden` TINYINT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`)
);

CREATE TABLE IF NOT EXISTS `interaction_reply` (
  `id` BIGINT NOT NULL,
  `question_id` BIGINT NOT NULL,
  `answer_id` BIGINT NULL,
  `user_id` BIGINT NOT NULL,
  `content` VARCHAR(2000) NOT NULL,
  `target_user_id` BIGINT NULL,
  `target_reply_id` BIGINT NULL,
  `reply_times` INT NOT NULL DEFAULT 0,
  `liked_times` INT NOT NULL DEFAULT 0,
  `hidden` TINYINT NOT NULL DEFAULT 0,
  `anonymity` TINYINT NOT NULL DEFAULT 0,
  `is_student` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_question` (`question_id`)
);

CREATE TABLE IF NOT EXISTS `ai_question_reply_task` (
  `id` BIGINT NOT NULL,
  `question_id` BIGINT NOT NULL,
  `action` VARCHAR(20) NOT NULL,
  `reply_id` BIGINT NULL,
  `status` TINYINT NOT NULL DEFAULT 0,
  `attempt` INT NOT NULL DEFAULT 0,
  `error` VARCHAR(500) NULL,
  `operator` BIGINT NULL,
  `worker_id` VARCHAR(64) NULL,
  `lease_until` DATETIME NULL,
  `next_retry_time` DATETIME NULL,
  `started_at` DATETIME NULL,
  `finished_at` DATETIME NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `active_flag` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` IN (0,1) THEN 1 ELSE NULL END),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_question_active` (`question_id`, `active_flag`),
  KEY `idx_status_retry` (`status`, `next_retry_time`)
);

CREATE TABLE IF NOT EXISTS `interaction_reply_extra` (
  `reply_id` BIGINT NOT NULL,
  `question_id` BIGINT NOT NULL,
  `source` VARCHAR(20) NOT NULL,
  `ai_task_id` BIGINT NULL,
  `model_code` VARCHAR(30) NULL,
  `corrected` TINYINT NOT NULL DEFAULT 0,
  `corrector` BIGINT NULL,
  `correct_time` DATETIME NULL,
  `prev_content` CLOB NULL,
  `featured` TINYINT NOT NULL DEFAULT 0,
  `feature_time` DATETIME NULL,
  `feature_operator` BIGINT NULL,
  `ai_checked` TINYINT NOT NULL DEFAULT 0,
  `helpful` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`reply_id`)
);

-- M11：通知事件 Outbox（H2 测试库镜像生产结构）
CREATE TABLE IF NOT EXISTS `sys_event_outbox` (
  `id` BIGINT NOT NULL PRIMARY KEY,
  `event_key` VARCHAR(128) NOT NULL,
  `biz_type` VARCHAR(32) NOT NULL,
  `biz_id` VARCHAR(64) NOT NULL,
  `recipient_id` BIGINT NOT NULL,
  `type` VARCHAR(32) NOT NULL,
  `title` VARCHAR(128) NOT NULL,
  `content` VARCHAR(500) NOT NULL,
  `payload_json` VARCHAR(1000) NULL,
  `status` TINYINT NOT NULL DEFAULT 0,
  `attempt` INT NOT NULL DEFAULT 0,
  `next_retry_time` TIMESTAMP NOT NULL,
  `worker_id` VARCHAR(64) NULL,
  `lease_until` TIMESTAMP NULL,
  `error` VARCHAR(500) NULL,
  `request_id` VARCHAR(64) NULL,
  `create_time` TIMESTAMP NOT NULL,
  `update_time` TIMESTAMP NOT NULL,
  CONSTRAINT uk_event_key UNIQUE (event_key)
);

-- M15.21：AI 回复采纳记录（userId+replyId 唯一；H2 测试库镜像）
CREATE TABLE IF NOT EXISTS `interaction_reply_helpful` (
  `reply_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`reply_id`, `user_id`)
);

CREATE TABLE IF NOT EXISTS point_rule (
  source VARCHAR(20) NOT NULL,
  points INT NOT NULL,
  daily_cap INT NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  update_time TIMESTAMP NOT NULL,
  PRIMARY KEY (source)
);
CREATE TABLE IF NOT EXISTS point_account (
  user_id BIGINT NOT NULL,
  total_points INT NOT NULL DEFAULT 0,
  update_time TIMESTAMP NOT NULL,
  PRIMARY KEY (user_id)
);
CREATE TABLE IF NOT EXISTS point_ledger (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  source VARCHAR(20) NOT NULL,
  event_key VARCHAR(80) NOT NULL,
  points INT NOT NULL,
  biz_date DATE NOT NULL,
  period_key CHAR(7) NOT NULL,
  reason VARCHAR(200) NULL,
  operator_id BIGINT NULL,
  create_time TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (event_key)
);
CREATE TABLE IF NOT EXISTS point_pending_award (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  source VARCHAR(20) NOT NULL,
  event_key VARCHAR(80) NOT NULL,
  biz_date DATE NOT NULL,
  reason VARCHAR(200) NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(200) NULL,
  create_time TIMESTAMP NOT NULL,
  update_time TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (event_key)
);
CREATE TABLE IF NOT EXISTS checkin_record (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  biz_date DATE NOT NULL,
  streak INT NOT NULL,
  create_time TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (user_id, biz_date)
);
CREATE TABLE IF NOT EXISTS rank_period (
  period_key CHAR(7) NOT NULL,
  status VARCHAR(20) NOT NULL,
  settle_time TIMESTAMP NULL,
  PRIMARY KEY (period_key)
);
CREATE TABLE IF NOT EXISTS learning_note (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  chapter_id BIGINT NOT NULL,
  title VARCHAR(80) NOT NULL,
  content VARCHAR(4000) NOT NULL,
  video_position_sec INT NULL,
  visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
  deleted TINYINT NOT NULL DEFAULT 0,
  like_count INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL,
  update_time TIMESTAMP NOT NULL,
  PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS learning_note_like (
  id BIGINT NOT NULL,
  note_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  create_time TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (note_id, user_id)
);
CREATE TABLE IF NOT EXISTS learning_note_comment (
  id BIGINT NOT NULL,
  note_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  content VARCHAR(500) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS rank_entry (
  id BIGINT NOT NULL,
  period_key CHAR(7) NOT NULL,
  user_id BIGINT NOT NULL,
  points INT NOT NULL,
  rank_no INT NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (period_key, user_id)
);
