-- 已有库升级：新增学习笔记。不改问答、课程、订单和已有积分流水。

CREATE TABLE IF NOT EXISTS `learning_note` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `course_id` BIGINT NOT NULL,
  `chapter_id` BIGINT NOT NULL,
  `title` VARCHAR(80) NOT NULL,
  `content` VARCHAR(4000) NOT NULL,
  `video_position_sec` INT NULL,
  `visibility` VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
  `deleted` TINYINT NOT NULL DEFAULT 0,
  `like_count` INT NOT NULL DEFAULT 0,
  `comment_count` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_note_owner` (`user_id`, `deleted`, `update_time`),
  KEY `idx_note_public` (`course_id`, `visibility`, `deleted`, `update_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学习笔记';

CREATE TABLE IF NOT EXISTS `learning_note_like` (
  `id` BIGINT NOT NULL,
  `note_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `create_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_note_like` (`note_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='笔记点赞';

CREATE TABLE IF NOT EXISTS `learning_note_comment` (
  `id` BIGINT NOT NULL,
  `note_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `content` VARCHAR(500) NOT NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_note_comment` (`note_id`, `deleted`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='笔记评论';

INSERT INTO `point_rule` (`source`, `points`, `daily_cap`, `enabled`, `update_time`)
SELECT 'NOTE_LIKE', 1, 5, 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `point_rule` WHERE `source` = 'NOTE_LIKE');
INSERT INTO `point_rule` (`source`, `points`, `daily_cap`, `enabled`, `update_time`)
SELECT 'NOTE_COMMENT', 1, 3, 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `point_rule` WHERE `source` = 'NOTE_COMMENT');
