-- M22: 积分发放补偿（outbox）。发分失败时由 PointService.award 以独立事务写入本表，
-- PointSettlementJob.retryPendingAwards 每分钟重试，成功置 DONE，连续失败 5 次置 FAILED 转人工。
-- 背景：章节完成事务与发分共用事务，award 曾静默吞异常导致积分丢失（见 2026-10-23 排查）。

CREATE TABLE IF NOT EXISTS `point_pending_award` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `source` VARCHAR(20) NOT NULL,
  `event_key` VARCHAR(80) NOT NULL,
  `biz_date` DATE NOT NULL,
  `reason` VARCHAR(200) NULL,
  `status` VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING DONE FAILED',
  `retry_count` INT NOT NULL DEFAULT 0,
  `last_error` VARCHAR(200) NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pending_event` (`event_key`),
  KEY `idx_pending_status` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='积分发放补偿队列';
