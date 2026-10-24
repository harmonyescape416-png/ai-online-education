-- =============================================================================
-- M2 最小增量迁移：管理员用户管理 / 角色权限 / 个人中心 / 操作日志
-- 说明：
--   1. 本文件为新增迁移，不修改已执行的 01_schema.sql / 02_seed.sql。
--   2. 原始 user 表字段与 user.type 枚举含义保持不变（1员工/管理员，2学员，3老师）。
--   3. 原表限制：user.type 无法区分「管理员 ADMIN」与「普通员工 STAFF」，
--      因此新增 sys_user_role 表达权限角色，user 表零改动，历史数据兼容。
--   4. 兼容性：sys_user_role 无记录时，按 user.type 推导角色
--      （1->ADMIN, 2->STUDENT, 3->TEACHER），旧账号无需补数据即可正常鉴权。
-- =============================================================================

-- 1) 用户权限角色（最小扩展表）
CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id`          BIGINT      NOT NULL COMMENT '主键',
  `user_id`     BIGINT      NOT NULL COMMENT '用户ID，关联 user.id',
  `role_code`   VARCHAR(32) NOT NULL COMMENT '角色编码：ADMIN/STAFF/TEACHER/STUDENT',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creater`     BIGINT      NULL     COMMENT '创建人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_code`),
  KEY `idx_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户权限角色（M2 扩展）';

-- 2) 重要管理操作日志（最小扩展表）
CREATE TABLE IF NOT EXISTS `sys_operation_log` (
  `id`            BIGINT       NOT NULL COMMENT '主键',
  `operator_id`   BIGINT       NOT NULL COMMENT '操作人ID',
  `operator_name` VARCHAR(32)  NULL     COMMENT '操作人账号',
  `module`        VARCHAR(32)  NOT NULL COMMENT '模块：USER/COURSE',
  `operation`     VARCHAR(32)  NOT NULL COMMENT '操作：CREATE/UPDATE/ROLE/STATUS/RESET_PWD/DELETE',
  `target_type`   VARCHAR(32)  NULL     COMMENT '对象类型：USER',
  `target_id`     BIGINT       NULL     COMMENT '对象ID',
  `detail`        VARCHAR(512) NULL     COMMENT '操作详情',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_operator` (`operator_id`),
  KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='管理操作日志（M2 扩展）';

-- 3) 为 M1 已存在的 SEED 账号补角色（幂等）
DELETE FROM `sys_user_role` WHERE `user_id` IN (1, 2, 3);
INSERT INTO `sys_user_role` (`id`, `user_id`, `role_code`, `creater`) VALUES
  (9001, 1, 'ADMIN',   1),
  (9002, 2, 'TEACHER', 1),
  (9003, 3, 'STUDENT', 1);
