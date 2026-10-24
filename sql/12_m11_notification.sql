-- ============================================================================
-- M11 站内通知系统 迁移脚本（用户 2026-10-20 批准）
--
-- 批准要点：
--   1) 用户库新增 sys_notification（通知持久化，唯一约束保证同事件对同收件人只生成一条）
--   2) learning 库、course 库各新增一张同结构 sys_event_outbox（本地 Outbox，与业务表同库同事务：
--      业务回滚则事件不产生；两服务各自 Worker 领取投递，失败退避重试，重启可恢复）
--   3) outbox 保留 payload_json(VARCHAR 1000，可空，仅放展示/跳转补充信息，不放受保护正文)
--   4) 原始 12 张核心表零改动
--
-- 全部 IF NOT EXISTS，可重复执行（幂等）
-- ============================================================================

-- ---------------------------- 表1：user 库 sys_notification ----------------------------
CREATE TABLE IF NOT EXISTS `sys_notification` (
  `id`            BIGINT       NOT NULL COMMENT '雪花ID',
  `recipient_id`  BIGINT       NOT NULL COMMENT '接收用户ID（唯一归属来源，不信任客户端传值）',
  `event_key`     VARCHAR(128) NOT NULL COMMENT '业务事件幂等标识（如 AI_REPLY:123 / COURSE_APPROVED:9）',
  `type`          VARCHAR(32)  NOT NULL COMMENT '类型：AI_REPLY / QUESTION_REPLY / COURSE_APPROVED',
  `title`         VARCHAR(128) NOT NULL COMMENT '通知标题',
  `content`       VARCHAR(500) NOT NULL COMMENT '通知摘要（不含受保护全文，XSS 安全文本展示）',
  `biz_type`      VARCHAR(32)  NOT NULL COMMENT '关联业务类型：QA / COURSE',
  `biz_id`        VARCHAR(64)  NOT NULL COMMENT '关联业务ID（字符串传输，避免 Long 精度丢失）',
  `read_status`   TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 / 1已读',
  `read_time`     DATETIME     NULL COMMENT '首次实际标记已读的时间（重复已读不覆盖）',
  `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '接收者维度逻辑删除：0正常 1已删除',
  `create_time`   DATETIME     NOT NULL COMMENT '创建时间',
  `update_time`   DATETIME     NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipient_event` (`recipient_id`, `event_key`),
  KEY `idx_recipient_read` (`recipient_id`, `read_status`, `deleted`, `create_time`),
  KEY `idx_biz` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知（M11）';

-- ------------- 表2：learning 库 sys_event_outbox（与课程问答业务同库同事务）-------------
CREATE TABLE IF NOT EXISTS `sys_event_outbox` (
  `id`               BIGINT       NOT NULL COMMENT '雪花ID',
  `event_key`        VARCHAR(128) NOT NULL COMMENT '事件幂等键（业务方生成，投递重试必须复用同键）',
  `biz_type`         VARCHAR(32)  NOT NULL COMMENT '业务类型：QA',
  `biz_id`           VARCHAR(64)  NOT NULL COMMENT '业务ID（字符串）',
  `recipient_id`     BIGINT       NOT NULL COMMENT '接收用户ID（由业务关系计算，不来自不可信输入）',
  `type`             VARCHAR(32)  NOT NULL COMMENT '通知类型：AI_REPLY / QUESTION_REPLY',
  `title`            VARCHAR(128) NOT NULL COMMENT '通知标题',
  `content`          VARCHAR(500) NOT NULL COMMENT '通知摘要',
  `payload_json`     VARCHAR(1000) NULL COMMENT '展示/跳转补充信息（不含受保护正文）',
  `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '0待发送 1发送中 2成功 3失败',
  `attempt`          INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数（上限5，超限置失败）',
  `next_retry_time`  DATETIME     NOT NULL COMMENT '可发送时间（指数退避）',
  `worker_id`        VARCHAR(64)  NULL COMMENT '领取的 Worker 实例（租约所有权）',
  `lease_until`      DATETIME     NULL COMMENT '租约到期（超时可被接管）',
  `error`            VARCHAR(500) NULL COMMENT '最近一次失败原因',
  `request_id`       VARCHAR(64)  NULL COMMENT '投递请求追踪ID',
  `create_time`      DATETIME     NOT NULL,
  `update_time`      DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_key` (`event_key`),
  KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知事件 Outbox（M11·learning）';

-- --------------- 表3：course 库 sys_event_outbox（与课程审核业务同库同事务）---------------
CREATE TABLE IF NOT EXISTS `sys_event_outbox` (
  `id`               BIGINT       NOT NULL COMMENT '雪花ID',
  `event_key`        VARCHAR(128) NOT NULL COMMENT '事件幂等键',
  `biz_type`         VARCHAR(32)  NOT NULL COMMENT '业务类型：COURSE',
  `biz_id`           VARCHAR(64)  NOT NULL COMMENT '业务ID（字符串）',
  `recipient_id`     BIGINT       NOT NULL COMMENT '接收用户ID（课程负责人）',
  `type`             VARCHAR(32)  NOT NULL COMMENT '通知类型：COURSE_APPROVED',
  `title`            VARCHAR(128) NOT NULL COMMENT '通知标题',
  `content`          VARCHAR(500) NOT NULL COMMENT '通知摘要',
  `payload_json`     VARCHAR(1000) NULL COMMENT '展示/跳转补充信息',
  `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '0待发送 1发送中 2成功 3失败',
  `attempt`          INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数（上限5）',
  `next_retry_time`  DATETIME     NOT NULL COMMENT '可发送时间（指数退避）',
  `worker_id`        VARCHAR(64)  NULL COMMENT '领取的 Worker 实例',
  `lease_until`      DATETIME     NULL COMMENT '租约到期',
  `error`            VARCHAR(500) NULL COMMENT '最近一次失败原因',
  `request_id`       VARCHAR(64)  NULL COMMENT '投递请求追踪ID',
  `create_time`      DATETIME     NOT NULL,
  `update_time`      DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_key` (`event_key`),
  KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知事件 Outbox（M11·course）';