-- M4 迁移：订单预下单幂等表
-- 背景：原始 order(19 字段) / order_detail(18 字段) 均无 requestId 字段，
-- 无法表达"userId + requestId 幂等"关系，故新增最小扩展表，不修改原始表结构。
-- 执行：mysql --socket=/tmp/mysql.sock -uroot course_stack_ai < sql/06_m4_trade.sql

CREATE TABLE IF NOT EXISTS `order_request_idempotent` (
  `id` BIGINT NOT NULL COMMENT '主键，雪花ID',
  `user_id` BIGINT NOT NULL COMMENT '下单用户ID',
  `request_id` VARCHAR(64) NOT NULL COMMENT '客户端幂等请求号',
  `request_fingerprint` VARCHAR(64) NOT NULL COMMENT '请求内容摘要：课程ID升序拼接后 SHA-256',
  `order_id` BIGINT DEFAULT NULL COMMENT '成功创建的订单ID，处理中为 NULL',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0处理中，1已创建订单',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_request` (`user_id`, `request_id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单预下单幂等表（M4 扩展，非原始表）';
