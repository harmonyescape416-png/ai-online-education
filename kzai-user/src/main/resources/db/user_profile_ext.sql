CREATE TABLE IF NOT EXISTS user_profile_ext (
    user_id     BIGINT       NOT NULL COMMENT '用户ID（user.id，1:1）',
    nickname    VARCHAR(30)  NULL COMMENT '昵称',
    avatar_url  VARCHAR(500) NULL COMMENT '头像访问地址（本地 /users/me/avatar）',
    nickname_updated_at DATETIME NULL COMMENT '昵称最近修改时间',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户扩展资料（昵称/头像）';
