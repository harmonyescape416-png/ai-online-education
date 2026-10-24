-- 签到、积分流水、累计积分、月榜结算。不改学习进度原表。

CREATE TABLE IF NOT EXISTS `point_rule` (
  `source` VARCHAR(20) NOT NULL COMMENT 'CHECKIN STREAK LEARN QUESTION',
  `points` INT NOT NULL,
  `daily_cap` INT NOT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='积分规则';

INSERT INTO `point_rule` (`source`, `points`, `daily_cap`, `enabled`, `update_time`)
SELECT 'CHECKIN', 5, 5, 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `point_rule` WHERE `source` = 'CHECKIN');
INSERT INTO `point_rule` (`source`, `points`, `daily_cap`, `enabled`, `update_time`)
SELECT 'STREAK', 10, 10, 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `point_rule` WHERE `source` = 'STREAK');
INSERT INTO `point_rule` (`source`, `points`, `daily_cap`, `enabled`, `update_time`)
SELECT 'LEARN', 2, 10, 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `point_rule` WHERE `source` = 'LEARN');
INSERT INTO `point_rule` (`source`, `points`, `daily_cap`, `enabled`, `update_time`)
SELECT 'QUESTION', 3, 9, 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `point_rule` WHERE `source` = 'QUESTION');

CREATE TABLE IF NOT EXISTS `point_account` (
  `user_id` BIGINT NOT NULL,
  `total_points` INT NOT NULL DEFAULT 0,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='累计积分';

CREATE TABLE IF NOT EXISTS `point_ledger` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `source` VARCHAR(20) NOT NULL,
  `event_key` VARCHAR(80) NOT NULL,
  `points` INT NOT NULL,
  `biz_date` DATE NOT NULL,
  `period_key` CHAR(7) NOT NULL,
  `reason` VARCHAR(200) NULL,
  `operator_id` BIGINT NULL,
  `create_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_point_event` (`event_key`),
  KEY `idx_point_user_day` (`user_id`, `source`, `biz_date`),
  KEY `idx_point_period` (`period_key`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='积分流水';

CREATE TABLE IF NOT EXISTS `checkin_record` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `biz_date` DATE NOT NULL,
  `streak` INT NOT NULL,
  `create_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_checkin_day` (`user_id`, `biz_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='每日签到';

CREATE TABLE IF NOT EXISTS `rank_period` (
  `period_key` CHAR(7) NOT NULL,
  `status` VARCHAR(20) NOT NULL COMMENT 'OPEN SETTLED',
  `settle_time` DATETIME NULL,
  PRIMARY KEY (`period_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='排行周期';

CREATE TABLE IF NOT EXISTS `rank_entry` (
  `id` BIGINT NOT NULL,
  `period_key` CHAR(7) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `points` INT NOT NULL,
  `rank_no` INT NOT NULL,
  `display_name` VARCHAR(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rank_user` (`period_key`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='已结算榜单';
