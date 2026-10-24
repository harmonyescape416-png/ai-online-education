-- M15.21：采纳按 userId+replyId 唯一记录（幂等标记/撤销；不使用总计数加减）
CREATE TABLE IF NOT EXISTS interaction_reply_helpful (
    reply_id    BIGINT   NOT NULL COMMENT '回复ID',
    user_id     BIGINT   NOT NULL COMMENT '学员ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reply_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 回复采纳记录（M15.21）';
