-- 交易服务集成测试库结构（H2，MySQL 兼容模式）
-- 与生产 order / order_detail 字段完全一致（19 / 18 字段），另加 M4 幂等扩展表
CREATE TABLE IF NOT EXISTS `order` (
  `id` BIGINT NOT NULL,
  `pay_order_no` BIGINT NULL,
  `user_id` BIGINT NOT NULL,
  `status` TINYINT NOT NULL,
  `message` VARCHAR(255) NULL,
  `total_amount` INT NOT NULL,
  `real_amount` INT NOT NULL,
  `discount_amount` INT NOT NULL DEFAULT 0,
  `pay_channel` VARCHAR(20) NULL,
  `coupon_ids` VARCHAR(255) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `pay_time` DATETIME NULL,
  `close_time` DATETIME NULL,
  `finish_time` DATETIME NULL,
  `refund_time` DATETIME NULL,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `creater` BIGINT NULL,
  `updater` BIGINT NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `order_detail` (
  `id` BIGINT NOT NULL,
  `order_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `course_id` BIGINT NOT NULL,
  `price` INT NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `cover_url` VARCHAR(255) NULL,
  `valid_duration` INT NULL,
  `course_expire_time` DATETIME NULL,
  `discount_amount` INT NOT NULL DEFAULT 0,
  `real_pay_amount` INT NOT NULL,
  `status` TINYINT NOT NULL,
  `refund_status` TINYINT NULL,
  `pay_channel` VARCHAR(20) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `creater` BIGINT NULL,
  `updater` BIGINT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `order_request_idempotent` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `request_id` VARCHAR(64) NOT NULL,
  `request_fingerprint` VARCHAR(64) NOT NULL,
  `order_id` BIGINT NULL,
  `status` TINYINT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_request` (`user_id`, `request_id`)
);

CREATE TABLE IF NOT EXISTS `trade_payment` (
  `id` BIGINT NOT NULL,
  `payment_no` VARCHAR(64) NOT NULL,
  `order_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `channel` VARCHAR(32) NOT NULL,
  `amount` INT NOT NULL,
  `currency` CHAR(3) NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `merchant_id` VARCHAR(64) NOT NULL,
  `channel_trade_no` VARCHAR(64) NULL,
  `expire_time` DATETIME NOT NULL,
  `success_time` DATETIME NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `open_flag` TINYINT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_no` (`payment_no`),
  UNIQUE KEY `uk_payment_open` (`order_id`, `open_flag`)
);

CREATE TABLE IF NOT EXISTS `trade_refund` (
  `id` BIGINT NOT NULL,
  `refund_no` VARCHAR(64) NOT NULL,
  `request_id` VARCHAR(64) NOT NULL,
  `order_id` BIGINT NOT NULL,
  `detail_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `amount` INT NOT NULL,
  `reason` VARCHAR(200) NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `reject_reason` VARCHAR(200) NULL,
  `reviewer` BIGINT NULL,
  `review_time` DATETIME NULL,
  `channel_refund_no` VARCHAR(64) NULL,
  `fail_reason` VARCHAR(200) NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `open_flag` TINYINT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  UNIQUE KEY `uk_refund_request` (`user_id`, `detail_id`, `request_id`),
  UNIQUE KEY `uk_refund_open` (`detail_id`, `open_flag`)
);

CREATE TABLE IF NOT EXISTS enroll_compensation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL,
  last_error VARCHAR(500) NULL,
  next_retry_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_course (order_id, course_id)
);

CREATE TABLE IF NOT EXISTS mkt_coupon_template (
  id BIGINT NOT NULL,
  name VARCHAR(80) NOT NULL,
  coupon_type VARCHAR(20) NOT NULL,
  threshold_amount INT NOT NULL,
  reduce_amount INT NOT NULL DEFAULT 0,
  pay_ratio INT NOT NULL DEFAULT 10000,
  total_stock INT NOT NULL,
  claimed_count INT NOT NULL DEFAULT 0,
  used_count INT NOT NULL DEFAULT 0,
  per_user_limit INT NOT NULL,
  claim_start DATETIME NOT NULL,
  claim_end DATETIME NOT NULL,
  valid_start DATETIME NOT NULL,
  valid_end DATETIME NOT NULL,
  course_scope VARCHAR(20) NOT NULL,
  redeem_code VARCHAR(32) NULL,
  status VARCHAR(20) NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_redeem (redeem_code)
);
CREATE TABLE IF NOT EXISTS mkt_coupon_scope (
  id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_scope (template_id, course_id)
);
CREATE TABLE IF NOT EXISTS mkt_coupon_user (
  id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  slot_no INT NOT NULL,
  source VARCHAR(20) NOT NULL,
  code_claim TINYINT NULL,
  name VARCHAR(80) NOT NULL,
  coupon_type VARCHAR(20) NOT NULL,
  threshold_amount INT NOT NULL,
  reduce_amount INT NOT NULL,
  pay_ratio INT NOT NULL,
  course_scope VARCHAR(20) NOT NULL,
  course_ids VARCHAR(1000) NOT NULL,
  valid_start DATETIME NOT NULL,
  valid_end DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL,
  lock_order_id BIGINT NULL,
  used_order_id BIGINT NULL,
  claim_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_user_slot (template_id, user_id, slot_no),
  UNIQUE KEY uk_coupon_code_once (template_id, user_id, code_claim)
);
CREATE TABLE IF NOT EXISTS mkt_order_coupon (
  id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  user_coupon_id BIGINT NOT NULL,
  discount_amount INT NOT NULL,
  allocation_json VARCHAR(2000) NOT NULL,
  status VARCHAR(20) NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_coupon (order_id)
);
CREATE TABLE IF NOT EXISTS mkt_coupon_compensate (
  id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  user_coupon_id BIGINT NOT NULL,
  action VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_compensate (order_id, action)
);
