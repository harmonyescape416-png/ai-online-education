-- =============================================================
-- M3 课程管理增量迁移（不修改 01/02/03/04，原始 12 张核心表零改动）
-- 1) course_audit    ：课程审核记录（草稿/待审核/驳回/强制下架），不重定义 course.status 原文枚举
-- 2) course_resource ：文件元数据与课程/章节资料关联（原表无文件、视频地址、课时等载体字段）
-- 执行：mysql --socket=/tmp/mysql.sock -uroot course_stack_ai < sql/05_m3_course_manage.sql
-- =============================================================

CREATE TABLE IF NOT EXISTS `course_audit` (
  `id` BIGINT NOT NULL COMMENT '审核记录ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID，关联 course.id',
  `audit_status` TINYINT NOT NULL COMMENT '审核状态：1待审核，2通过，3驳回，4强制下架',
  `reason` VARCHAR(255) NULL DEFAULT NULL COMMENT '审核意见：驳回原因 / 下架原因',
  `auditor_id` BIGINT NULL DEFAULT NULL COMMENT '审核人ID',
  `auditor_name` VARCHAR(32) NULL DEFAULT NULL COMMENT '审核人账号',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`),
  KEY `idx_status` (`audit_status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程审核记录（M3 扩展表，course 表零改动）';

CREATE TABLE IF NOT EXISTS `course_resource` (
  `id` BIGINT NOT NULL COMMENT '资源ID',
  `course_id` BIGINT NULL DEFAULT NULL COMMENT '所属课程ID，未绑定时为空',
  `chapter_id` BIGINT NULL DEFAULT NULL COMMENT '所属章ID，课程级资料为空',
  `kind` TINYINT NOT NULL COMMENT '资源类型：1封面，2教学资料，3视频',
  `file_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
  `file_url` VARCHAR(255) NOT NULL COMMENT '受控访问路径 /files/{id}/download（不暴露本地绝对路径）',
  `file_path` VARCHAR(255) NOT NULL COMMENT '服务端存储相对路径（不对外可见）',
  `file_size` BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，字节',
  `content_type` VARCHAR(100) NULL DEFAULT NULL COMMENT 'MIME 类型',
  `uploader` BIGINT NULL DEFAULT NULL COMMENT '上传人',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`, `kind`, `deleted`),
  KEY `idx_chapter` (`chapter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程文件与资料（M3 扩展表）';
