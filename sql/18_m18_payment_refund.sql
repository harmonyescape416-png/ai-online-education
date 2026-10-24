-- M18：支付单与退款单。不改已有订单金额，不回写历史订单状态。
-- 空库在 sql/17 之后执行。已有库执行 deploy/migrations/V20261026_payment_refund.sql。

CREATE TABLE IF NOT EXISTS `trade_payment` (
  `id` BIGINT NOT NULL,
  `payment_no` VARCHAR(64) NOT NULL,
  `order_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `channel` VARCHAR(32) NOT NULL COMMENT 'MOCK_WECHAT/MOCK_ALIPAY/ALIPAY_SANDBOX',
  `amount` INT NOT NULL COMMENT '申请支付金额，单位分，等于订单实付',
  `currency` CHAR(3) NOT NULL DEFAULT 'CNY',
  `status` VARCHAR(20) NOT NULL COMMENT 'WAIT_PAY/SUCCESS/CLOSED/IGNORED',
  `merchant_id` VARCHAR(64) NOT NULL,
  `channel_trade_no` VARCHAR(64) NULL,
  `expire_time` DATETIME NOT NULL,
  `success_time` DATETIME NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `open_flag` TINYINT NULL COMMENT '1=待支付，其余为 NULL',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_no` (`payment_no`),
  UNIQUE KEY `uk_payment_open` (`order_id`, `open_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付单';

CREATE TABLE IF NOT EXISTS `trade_refund` (
  `id` BIGINT NOT NULL,
  `refund_no` VARCHAR(64) NOT NULL,
  `request_id` VARCHAR(64) NOT NULL,
  `order_id` BIGINT NOT NULL,
  `detail_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `amount` INT NOT NULL COMMENT '申请退款金额，单位分，按明细实付分摊',
  `reason` VARCHAR(200) NOT NULL,
  `status` VARCHAR(20) NOT NULL COMMENT 'APPLYING/REJECTED/APPROVED/REFUNDING/SUCCESS/FAILED',
  `reject_reason` VARCHAR(200) NULL,
  `reviewer` BIGINT NULL,
  `review_time` DATETIME NULL,
  `channel_refund_no` VARCHAR(64) NULL,
  `fail_reason` VARCHAR(200) NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `open_flag` TINYINT NULL COMMENT '1=申请中、已批准或退款中',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  UNIQUE KEY `uk_refund_request` (`user_id`, `detail_id`, `request_id`),
  UNIQUE KEY `uk_refund_open` (`detail_id`, `open_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程明细退款单';
