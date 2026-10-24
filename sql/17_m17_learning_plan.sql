-- M17：学员学习计划。不回填历史周完成量，不修改 facts_since 和学习进度。
-- 进行中和暂停的计划每个学员每门课只能有一条。退出或课程完成后可以重新加入。
-- 空库在 sql/16 之后执行。已有库执行 deploy/migrations/V20261025_learning_plan.sql。

CREATE TABLE IF NOT EXISTS `learning_plan` (
  `id`          BIGINT   NOT NULL,
  `user_id`     BIGINT   NOT NULL,
  `course_id`   BIGINT   NOT NULL,
  `status`      TINYINT  NOT NULL COMMENT '1进行中 2暂停 3已退出 4课程已完成',
  `joined_at`   DATETIME NOT NULL COMMENT '加入时刻，此前完成的章节不计入本计划',
  `quit_at`     DATETIME NULL COMMENT '退出时刻，之后的完成不计入该计划',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `open_flag`   TINYINT GENERATED ALWAYS AS (CASE WHEN `status` IN (1, 2) THEN 1 ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_open` (`user_id`, `course_id`, `open_flag`),
  KEY `idx_plan_user` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学员学习计划';

CREATE TABLE IF NOT EXISTS `learning_plan_goal` (
  `id`             BIGINT NOT NULL,
  `plan_id`        BIGINT NOT NULL,
  `weekly_goal`    INT    NOT NULL COMMENT '该周生效的每周完成章节数',
  `effective_week` DATE   NOT NULL COMMENT '目标开始生效的周一，Asia/Shanghai',
  `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_goal_week` (`plan_id`, `effective_week`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习计划每周目标，按周保留，不随后来的修改改写旧周';

CREATE TABLE IF NOT EXISTS `learning_plan_pause` (
  `id`          BIGINT   NOT NULL,
  `plan_id`     BIGINT   NOT NULL,
  `paused_at`   DATETIME NOT NULL,
  `resumed_at`  DATETIME NULL COMMENT '空表示仍在暂停',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_plan_pause` (`plan_id`, `paused_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习计划暂停区间，区间内的首次完成不计入当周目标';
