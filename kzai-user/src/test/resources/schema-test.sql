-- 集成测试专用 H2 脚本：字段与《数据库设计说明书》3.3.1 user 表保持一致（10 个字段）
CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT NOT NULL,
  `username` VARCHAR(32) NOT NULL,
  `cell_phone` VARCHAR(20) NULL,
  `password` VARCHAR(64) NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `type` TINYINT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `creater` BIGINT NULL,
  `updater` BIGINT NULL,
  PRIMARY KEY (`id`)
);

-- M2 扩展表（与 sql/03_m2_user_manage.sql 字段一致，仅用于测试）
CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `role_code` VARCHAR(32) NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `creater` BIGINT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `sys_operation_log` (
  `id` BIGINT NOT NULL,
  `operator_id` BIGINT NOT NULL,
  `operator_name` VARCHAR(32) NULL,
  `module` VARCHAR(32) NOT NULL,
  `operation` VARCHAR(32) NOT NULL,
  `target_type` VARCHAR(32) NULL,
  `target_id` BIGINT NULL,
  `detail` VARCHAR(512) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- M2 遗留修复②：token 版本号持久化表（与 sql/04_m2_token_version.sql 字段一致，仅用于测试）
CREATE TABLE IF NOT EXISTS `sys_token_version` (
  `user_id` BIGINT NOT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
);

-- M11：站内通知（H2 测试库镜像生产结构）
CREATE TABLE IF NOT EXISTS `sys_notification` (
  `id` BIGINT NOT NULL PRIMARY KEY,
  `recipient_id` BIGINT NOT NULL,
  `event_key` VARCHAR(128) NOT NULL,
  `type` VARCHAR(32) NOT NULL,
  `title` VARCHAR(128) NOT NULL,
  `content` VARCHAR(500) NOT NULL,
  `biz_type` VARCHAR(32) NOT NULL,
  `biz_id` VARCHAR(64) NOT NULL,
  `read_status` TINYINT NOT NULL DEFAULT 0,
  `read_time` TIMESTAMP NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  `create_time` TIMESTAMP NOT NULL,
  `update_time` TIMESTAMP NOT NULL,
  CONSTRAINT uk_recipient_event UNIQUE (recipient_id, event_key)
);
