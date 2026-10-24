-- M5 迁移：学习管理（学习权益 / 进度 / 断点续学 / 教师学情）
-- 背景：
--   1) 原始 learning_lesson（10 字段）只适合表达"课程级最近学习状态"，
--      无法承载"章节独立进度 / 视频断点位置 / 累计有效学习时长 / 已完成章节统计"；
--   2) 原始 course_chapter（8 字段）没有任何字段可以表达"本章可试看"；
--   3) 原始 12 张核心表一律不改，故新增两张最小扩展表。
-- 执行：mysql --socket=/tmp/mysql.sock -uroot course_stack_ai < sql/07_m5_learning.sql

-- 1. learning_lesson_progress（章节级学习进度明细，M5 扩展，非原始表）
--    数据归属：一行 = 一个用户在某门课程下的某一个章节的学习状态。
--    并发更新：以 uk_user_chapter 唯一索引兜底，更新走"条件更新 + 回读"，
--              学习时长只按视频位置前进量累计（回放/重复上报不产生重复累计）。
CREATE TABLE IF NOT EXISTS `learning_lesson_progress` (
  `id` BIGINT NOT NULL COMMENT '主键，雪花ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `chapter_id` BIGINT NOT NULL COMMENT '章ID（course_chapter.id）',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未开始，1学习中，2已完成（沿用原始枚举语义）',
  `progress` INT NOT NULL DEFAULT 0 COMMENT '章节内进度百分比 0~100',
  `video_position_sec` INT NOT NULL DEFAULT 0 COMMENT '视频断点位置（秒），服务端持久化，用于跨设备续学',
  `video_duration_sec` INT NULL DEFAULT NULL COMMENT '视频总时长（秒），用于服务端校验进度与结束判定',
  `study_seconds` BIGINT NOT NULL DEFAULT 0 COMMENT '累计有效学习时长（秒），按视频位置真实前进量累计',
  `finish_time` DATETIME NULL DEFAULT NULL COMMENT '章节完成时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_chapter` (`user_id`, `course_id`, `chapter_id`),
  KEY `idx_course` (`course_id`),
  KEY `idx_user_course` (`user_id`, `course_id`),
  KEY `idx_last_study` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='章节级学习进度明细（M5 扩展，非原始表）';

-- 2. course_chapter_extra（章节扩展属性，M5 扩展，非原始表）
--    仅承载原始 course_chapter 无法表达的"试看"标识与视频时长，不改动原表 8 字段。
CREATE TABLE IF NOT EXISTS `course_chapter_extra` (
  `id` BIGINT NOT NULL COMMENT '主键，雪花ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID（冗余，便于按课程检索）',
  `chapter_id` BIGINT NOT NULL COMMENT '章ID',
  `free_preview` TINYINT NOT NULL DEFAULT 0 COMMENT '是否可试看：0否，1是（未购学员可访问本章）',
  `video_duration_sec` INT NULL DEFAULT NULL COMMENT '视频总时长（秒）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chapter` (`chapter_id`),
  KEY `idx_course` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='章节扩展属性（M5 扩展，非原始表）';
