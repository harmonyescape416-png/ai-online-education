-- 集成测试专用 H2 脚本：字段与《数据库设计说明书》3.3.3 / 3.3.4 / 3.3.5 保持一致
CREATE TABLE IF NOT EXISTS `course` (
  `id` BIGINT NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `course_type` TINYINT NOT NULL,
  `cover_url` VARCHAR(255) NULL,
  `first_cate_id` BIGINT NULL,
  `second_cate_id` BIGINT NULL,
  `third_cate_id` BIGINT NULL,
  `free` TINYINT NOT NULL DEFAULT 0,
  `price` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL,
  `purchase_start_time` DATETIME NULL,
  `purchase_end_time` DATETIME NULL,
  `valid_duration` INT NULL,
  `section_num` INT NOT NULL DEFAULT 0,
  `score` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `creater` BIGINT NULL,
  `updater` BIGINT NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `course_content` (
  `id` BIGINT NOT NULL,
  `course_introduce` TEXT NULL,
  `use_people` VARCHAR(255) NULL,
  `course_detail` LONGTEXT NULL,
  `dep_id` BIGINT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `creater` BIGINT NULL,
  `updater` BIGINT NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `course_chapter` (
  `id` BIGINT NOT NULL,
  `course_id` BIGINT NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `chapter_order` INT NOT NULL,
  `description` VARCHAR(255) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
);

-- M5 扩展表（与 sql/07_m5_learning.sql 字段一致，仅用于测试）：章节试看与视频时长
CREATE TABLE IF NOT EXISTS `course_chapter_extra` (
  `id` BIGINT NOT NULL,
  `course_id` BIGINT NOT NULL,
  `chapter_id` BIGINT NOT NULL,
  `free_preview` TINYINT NOT NULL DEFAULT 0,
  `video_duration_sec` INT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chapter` (`chapter_id`)
);

-- M3 扩展表（与 sql/05_m3_course_manage.sql 字段一致，仅用于测试）
CREATE TABLE IF NOT EXISTS `course_audit` (
  `id` BIGINT NOT NULL,
  `course_id` BIGINT NOT NULL,
  `audit_status` TINYINT NOT NULL,
  `reason` VARCHAR(255) NULL,
  `auditor_id` BIGINT NULL,
  `auditor_name` VARCHAR(32) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `course_resource` (
  `id` BIGINT NOT NULL,
  `course_id` BIGINT NULL,
  `chapter_id` BIGINT NULL,
  `kind` TINYINT NOT NULL,
  `file_name` VARCHAR(255) NOT NULL,
  `file_url` VARCHAR(255) NOT NULL,
  `file_path` VARCHAR(255) NOT NULL,
  `file_size` BIGINT NOT NULL DEFAULT 0,
  `content_type` VARCHAR(100) NULL,
  `uploader` BIGINT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  `replaced_by` BIGINT NULL,
  `kb_status` TINYINT NOT NULL DEFAULT 0,
  `kb_doc_id` BIGINT NULL,
  `kb_review_status` TINYINT NULL,
  `kb_index_status` TINYINT NULL,
  `kb_fail_reason` VARCHAR(500) NULL,
  `kb_version` VARCHAR(64) NULL,
  `replace_time` DATETIME NULL,
  PRIMARY KEY (`id`)
);

-- M3：分类表（与原始 12 表 course_category 字段一致，仅用于测试）
CREATE TABLE IF NOT EXISTS `course_category` (
  `id` BIGINT NOT NULL,
  `name` VARCHAR(30) NOT NULL,
  `parent_id` BIGINT NOT NULL DEFAULT 0,
  `level` TINYINT NOT NULL DEFAULT 1,
  `sort` INT NOT NULL DEFAULT 100,
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

DELETE FROM `course_category`;
INSERT INTO `course_category` (id, name, parent_id, level, sort, status) VALUES
 (101, '后端开发', 0, 1, 1, 1),
 (102, '职业素养', 0, 1, 2, 1),
 (201, 'Java', 101, 2, 1, 1),
 (202, '人工智能', 101, 2, 2, 1),
 (203, '沟通表达', 102, 2, 1, 1);

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
