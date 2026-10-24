-- =============================================================
-- 课栈智伴 —— 原始核心表建表脚本（12 张表）
-- 来源：《数据库设计说明书》KZ-DBD-2026-001 v1.1 第 5.2 节
--       建表语句逐字还原，未自行增删核心表字段
-- 数据库：course_stack_ai  引擎：InnoDB  字符集：utf8mb4 / utf8mb4_general_ci
-- 主键：BIGINT，由 MyBatis-Plus 雪花算法生成，不使用 AUTO_INCREMENT
-- =============================================================

SET NAMES utf8mb4;

-- 1. user（用户表，10 字段）  说明：5.2.1
CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT NOT NULL COMMENT '用户ID，主键',
  `username` VARCHAR(32) NOT NULL COMMENT '登录账号',
  `cell_phone` VARCHAR(20) NULL DEFAULT NULL COMMENT '手机号',
  `password` VARCHAR(64) NOT NULL COMMENT '密码哈希值',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用，1正常',
  `type` TINYINT NOT NULL COMMENT '类型：1员工，2学员，3老师',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_cell_phone (cell_phone),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户表';

-- 2. course_category（课程分类表，8 字段）  说明：5.2.2
CREATE TABLE IF NOT EXISTS `course_category` (
  `id` BIGINT NOT NULL COMMENT '分类ID，主键',
  `name` VARCHAR(50) NOT NULL COMMENT '分类名称',
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父分类ID，0为一级',
  `level` TINYINT NOT NULL COMMENT '层级：1/2/3',
  `sort` INT NOT NULL DEFAULT 0 COMMENT '排序值',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用，1启用',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_parent (parent_id),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程分类表';

-- 3. course（课程表，20 字段）  说明：5.2.3
CREATE TABLE IF NOT EXISTS `course` (
  `id` BIGINT NOT NULL COMMENT '课程ID，主键',
  `name` VARCHAR(100) NOT NULL COMMENT '课程名称',
  `course_type` TINYINT NOT NULL COMMENT '类型：1直播，2录播',
  `cover_url` VARCHAR(255) NULL DEFAULT NULL COMMENT '封面链接',
  `first_cate_id` BIGINT NULL DEFAULT NULL COMMENT '一级分类ID',
  `second_cate_id` BIGINT NULL DEFAULT NULL COMMENT '二级分类ID',
  `third_cate_id` BIGINT NULL DEFAULT NULL COMMENT '三级分类ID',
  `free` TINYINT NOT NULL DEFAULT 0 COMMENT '0付费，1免费',
  `price` INT NOT NULL DEFAULT 0 COMMENT '课程价格，单位分',
  `status` TINYINT NOT NULL COMMENT '1待上架，2已上架，3下架，4已完结',
  `purchase_start_time` DATETIME NULL DEFAULT NULL COMMENT '购买有效期开始',
  `purchase_end_time` DATETIME NULL DEFAULT NULL COMMENT '购买有效期结束',
  `valid_duration` INT NULL DEFAULT NULL COMMENT '有效期，单位月',
  `section_num` INT NOT NULL DEFAULT 0 COMMENT '课程总节数',
  `score` INT NOT NULL DEFAULT 0 COMMENT '评分，45代表4.5星',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
  KEY idx_cate (first_cate_id, second_cate_id, third_cate_id),
  KEY idx_status (status),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程表';

-- 4. course_content（课程内容表，10 字段）  说明：5.2.4
CREATE TABLE IF NOT EXISTS `course_content` (
  `id` BIGINT NOT NULL COMMENT '课程内容ID，与course.id一致',
  `course_introduce` TEXT NULL DEFAULT NULL COMMENT '课程介绍',
  `use_people` VARCHAR(255) NULL DEFAULT NULL COMMENT '适用人群',
  `course_detail` LONGTEXT NULL DEFAULT NULL COMMENT '课程详情',
  `dep_id` BIGINT NULL DEFAULT NULL COMMENT '部门ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程内容表';

-- 5. course_chapter（课程章表，8 字段）  说明：5.2.5
CREATE TABLE IF NOT EXISTS `course_chapter` (
  `id` BIGINT NOT NULL COMMENT '章ID，主键',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `name` VARCHAR(100) NOT NULL COMMENT '章名称',
  `chapter_order` INT NOT NULL COMMENT '章序号',
  `description` VARCHAR(255) NULL DEFAULT NULL COMMENT '章描述',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_course (course_id, chapter_order),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程章表';

-- 6. order（订单表，19 字段）  说明：5.2.6
CREATE TABLE IF NOT EXISTS `order` (
  `id` BIGINT NOT NULL COMMENT '订单ID，主键',
  `pay_order_no` BIGINT NULL DEFAULT NULL COMMENT '支付交易流水号',
  `user_id` BIGINT NOT NULL COMMENT '下单用户ID',
  `status` TINYINT NOT NULL COMMENT '1待支付，2已支付，3已关闭，4已完成，5已报名，6已申请退款',
  `message` VARCHAR(255) NULL DEFAULT NULL COMMENT '状态备注',
  `total_amount` INT NOT NULL COMMENT '订单总金额，单位分',
  `real_amount` INT NOT NULL COMMENT '实付金额，单位分',
  `discount_amount` INT NOT NULL DEFAULT 0 COMMENT '优惠金额，单位分',
  `pay_channel` VARCHAR(20) NULL DEFAULT NULL COMMENT '支付渠道',
  `coupon_ids` JSON NULL DEFAULT NULL COMMENT '优惠券ID集合',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `pay_time` DATETIME NULL DEFAULT NULL COMMENT '支付时间',
  `close_time` DATETIME NULL DEFAULT NULL COMMENT '关闭时间',
  `finish_time` DATETIME NULL DEFAULT NULL COMMENT '完成时间',
  `refund_time` DATETIME NULL DEFAULT NULL COMMENT '退款时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_user_time (user_id, create_time),
  KEY idx_status (status),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单表';

-- 7. order_detail（订单明细表，18 字段）  说明：5.2.7
CREATE TABLE IF NOT EXISTS `order_detail` (
  `id` BIGINT NOT NULL COMMENT '明细ID，主键',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `price` INT NOT NULL COMMENT '课程价格，单位分',
  `name` VARCHAR(100) NOT NULL COMMENT '课程名称快照',
  `cover_url` VARCHAR(255) NULL DEFAULT NULL COMMENT '封面快照',
  `valid_duration` INT NULL DEFAULT NULL COMMENT '有效期，单位月',
  `course_expire_time` DATETIME NULL DEFAULT NULL COMMENT '课程过期时间',
  `discount_amount` INT NOT NULL DEFAULT 0 COMMENT '折扣金额，单位分',
  `real_pay_amount` INT NOT NULL COMMENT '实付金额，单位分',
  `status` TINYINT NOT NULL COMMENT '1待支付，2已支付，3已关闭，4已完成，5已报名',
  `refund_status` TINYINT NULL DEFAULT NULL COMMENT '退款状态',
  `pay_channel` VARCHAR(20) NULL DEFAULT NULL COMMENT '支付渠道',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  KEY idx_order (order_id),
  KEY idx_course (course_id),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单明细表';

-- 8. interaction_question（互动提问表，14 字段）  说明：5.2.8
CREATE TABLE IF NOT EXISTS `interaction_question` (
  `id` BIGINT NOT NULL COMMENT '问题ID，主键',
  `title` VARCHAR(255) NOT NULL COMMENT '问题标题',
  `description` TEXT NULL DEFAULT NULL COMMENT '问题描述',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `chapter_id` BIGINT NULL DEFAULT NULL COMMENT '章ID',
  `section_id` BIGINT NULL DEFAULT NULL COMMENT '节ID',
  `user_id` BIGINT NOT NULL COMMENT '提问学员ID',
  `latest_answer_id` BIGINT NULL DEFAULT NULL COMMENT '最新回答ID',
  `answer_times` INT NOT NULL DEFAULT 0 COMMENT '回答数量',
  `anonymity` TINYINT NOT NULL DEFAULT 0 COMMENT '是否匿名',
  `hidden` TINYINT NOT NULL DEFAULT 0 COMMENT '是否隐藏',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未查看，1已查看',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_course_time (course_id, create_time),
  KEY idx_user (user_id),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='互动提问表';

-- 9. interaction_reply（互动回答表，14 字段）  说明：5.2.9
CREATE TABLE IF NOT EXISTS `interaction_reply` (
  `id` BIGINT NOT NULL COMMENT '回答ID，主键',
  `question_id` BIGINT NOT NULL COMMENT '问题ID',
  `answer_id` BIGINT NULL DEFAULT NULL COMMENT '上级回答ID',
  `user_id` BIGINT NOT NULL COMMENT '回答者ID',
  `content` TEXT NOT NULL COMMENT '回答内容',
  `target_user_id` BIGINT NULL DEFAULT NULL COMMENT '目标用户ID',
  `target_reply_id` BIGINT NULL DEFAULT NULL COMMENT '目标回答ID',
  `reply_times` INT NOT NULL DEFAULT 0 COMMENT '评论数',
  `liked_times` INT NOT NULL DEFAULT 0 COMMENT '点赞数',
  `hidden` TINYINT NOT NULL DEFAULT 0 COMMENT '是否隐藏',
  `anonymity` TINYINT NOT NULL DEFAULT 0 COMMENT '是否匿名',
  `is_student` TINYINT NOT NULL DEFAULT 1 COMMENT '是否学员：0AI/系统，1学员',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_question_time (question_id, create_time),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='互动回答表';

-- 10. chat_session（AI会话表，8 字段）  说明：5.2.10
CREATE TABLE IF NOT EXISTS `chat_session` (
  `id` BIGINT NOT NULL COMMENT '主键',
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID（UUID）',
  `user_id` BIGINT NOT NULL COMMENT '归属用户ID',
  `title` VARCHAR(100) NULL DEFAULT NULL COMMENT '会话标题',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  UNIQUE KEY uk_session_id (session_id),
  KEY idx_user_time (user_id, update_time),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI会话表';

-- 11. ai_eval_case（AI评测用例表，11 字段）  说明：5.2.11
CREATE TABLE IF NOT EXISTS `ai_eval_case` (
  `id` BIGINT NOT NULL COMMENT '用例ID，主键',
  `question` VARCHAR(500) NOT NULL COMMENT '评测问题',
  `category` VARCHAR(50) NOT NULL COMMENT '类别：recommend/consult/buy/knowledge/greeting/out_of_scope',
  `expected_intent` VARCHAR(50) NULL DEFAULT NULL COMMENT '期望意图',
  `expected_doc_id` VARCHAR(64) NULL DEFAULT NULL COMMENT '期望召回文档/课程ID',
  `actual_intent` VARCHAR(50) NULL DEFAULT NULL COMMENT '实际意图',
  `actual_doc_ids` VARCHAR(500) NULL DEFAULT NULL COMMENT '实际召回ID集合',
  `latency_ms` INT NULL DEFAULT NULL COMMENT '端到端耗时，毫秒',
  `token_usage` INT NULL DEFAULT NULL COMMENT 'token 用量',
  `passed` TINYINT NOT NULL DEFAULT 0 COMMENT '是否通过：0否，1是',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
  KEY idx_category (category),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI评测用例表';

-- 12. learning_lesson（学习记录表，10 字段）  说明：5.2.12
CREATE TABLE IF NOT EXISTS `learning_lesson` (
  `id` BIGINT NOT NULL COMMENT '学习记录ID，主键',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `chapter_id` BIGINT NULL DEFAULT NULL COMMENT '章ID',
  `section_id` BIGINT NULL DEFAULT NULL COMMENT '节ID',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未开始，1学习中，2已完成',
  `progress` INT NOT NULL DEFAULT 0 COMMENT '进度百分比',
  `last_study_time` DATETIME NULL DEFAULT NULL COMMENT '最近学习时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_user_course_section (user_id, course_id, section_id),
  KEY idx_course (course_id),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学习记录表';
