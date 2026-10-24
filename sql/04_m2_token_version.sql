-- =============================================================
-- M2 遗留修复②：Token 撤销持久化（增量迁移，不修改 01/02/03）
-- 背景：token 版本号此前仅存 Redis，键丢失/过期后旧 token 会恢复有效，
--       且网关在 Redis 不可用时默认放行。本表作为权威版本号来源，Redis 仅作缓存。
-- 执行：mysql --socket=/tmp/mysql.sock -uroot course_stack_ai < sql/04_m2_token_version.sql
-- =============================================================

CREATE TABLE IF NOT EXISTS `sys_token_version` (
  `user_id` BIGINT NOT NULL COMMENT '用户ID，关联 user.id',
  `version` INT NOT NULL DEFAULT 0 COMMENT 'token 版本号，自增；token 中 ver 小于当前值即失效',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户 token 版本号（持久化撤销，Redis 仅缓存）';
