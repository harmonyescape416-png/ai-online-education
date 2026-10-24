-- 优惠券：模板、适用范围、用户持券快照、订单锁券、核销补偿。
-- 不修改 order / order_detail 原有字段含义。优惠写入已有 discount_amount、real_amount、real_pay_amount。

CREATE TABLE IF NOT EXISTS `mkt_coupon_template` (
  `id` BIGINT NOT NULL,
  `name` VARCHAR(80) NOT NULL,
  `coupon_type` VARCHAR(20) NOT NULL COMMENT 'FULL_REDUCTION 满减，DISCOUNT 折扣',
  `threshold_amount` INT NOT NULL COMMENT '最低消费，分',
  `reduce_amount` INT NOT NULL DEFAULT 0 COMMENT '满减金额，分',
  `pay_ratio` INT NOT NULL DEFAULT 10000 COMMENT '折扣券支付比例，万分比，8500 表示支付 85%',
  `total_stock` INT NOT NULL,
  `claimed_count` INT NOT NULL DEFAULT 0,
  `used_count` INT NOT NULL DEFAULT 0,
  `per_user_limit` INT NOT NULL,
  `claim_start` DATETIME NOT NULL,
  `claim_end` DATETIME NOT NULL,
  `valid_start` DATETIME NOT NULL,
  `valid_end` DATETIME NOT NULL,
  `course_scope` VARCHAR(20) NOT NULL COMMENT 'ALL 或 INCLUDE',
  `redeem_code` VARCHAR(32) NULL,
  `status` VARCHAR(20) NOT NULL COMMENT 'DRAFT PUBLISHED STOPPED',
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_redeem` (`redeem_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='优惠券模板';

CREATE TABLE IF NOT EXISTS `mkt_coupon_scope` (
  `id` BIGINT NOT NULL,
  `template_id` BIGINT NOT NULL,
  `course_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_scope` (`template_id`, `course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='优惠券适用课程';

CREATE TABLE IF NOT EXISTS `mkt_coupon_user` (
  `id` BIGINT NOT NULL,
  `template_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `slot_no` INT NOT NULL,
  `source` VARCHAR(20) NOT NULL COMMENT 'PUBLIC 或 CODE',
  `code_claim` TINYINT NULL COMMENT '兑换码领取为 1，公开领取为空',
  `name` VARCHAR(80) NOT NULL,
  `coupon_type` VARCHAR(20) NOT NULL,
  `threshold_amount` INT NOT NULL,
  `reduce_amount` INT NOT NULL,
  `pay_ratio` INT NOT NULL,
  `course_scope` VARCHAR(20) NOT NULL,
  `course_ids` VARCHAR(1000) NOT NULL,
  `valid_start` DATETIME NOT NULL,
  `valid_end` DATETIME NOT NULL,
  `status` VARCHAR(20) NOT NULL COMMENT 'UNUSED LOCKED USED',
  `lock_order_id` BIGINT NULL,
  `used_order_id` BIGINT NULL,
  `claim_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_user_slot` (`template_id`, `user_id`, `slot_no`),
  UNIQUE KEY `uk_coupon_code_once` (`template_id`, `user_id`, `code_claim`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户优惠券快照';

CREATE TABLE IF NOT EXISTS `mkt_order_coupon` (
  `id` BIGINT NOT NULL,
  `order_id` BIGINT NOT NULL,
  `user_coupon_id` BIGINT NOT NULL,
  `discount_amount` INT NOT NULL,
  `allocation_json` VARCHAR(2000) NOT NULL,
  `status` VARCHAR(20) NOT NULL COMMENT 'LOCKED USED RELEASED',
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_coupon` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单使用的优惠券';

CREATE TABLE IF NOT EXISTS `mkt_coupon_compensate` (
  `id` BIGINT NOT NULL,
  `order_id` BIGINT NOT NULL,
  `user_coupon_id` BIGINT NOT NULL,
  `action` VARCHAR(20) NOT NULL,
  `status` VARCHAR(20) NOT NULL COMMENT 'PENDING DONE',
  `reason` VARCHAR(200) NOT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_compensate` (`order_id`, `action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='优惠券核销补偿';
