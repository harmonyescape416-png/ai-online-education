-- ============================================================
-- M15.15 方案 A（用户批准）：用户扩展资料表（昵称/头像）
-- 不修改原始 12 张核心表；幂等（IF NOT EXISTS）
-- ============================================================
CREATE TABLE IF NOT EXISTS user_profile_ext (
    user_id     BIGINT       NOT NULL COMMENT '用户ID（user.id，1:1）',
    nickname    VARCHAR(30)  NULL COMMENT '昵称',
    avatar_url  VARCHAR(500) NULL COMMENT '头像访问地址（本地 /avatar/{userId}）',
    nickname_updated_at DATETIME NULL COMMENT '昵称最近修改时间（7 天一次限制基准；NULL=从未修改）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户扩展资料（昵称/头像，M15.15 方案 A）';
