-- M15.20-B：AI 回复待检查状态 + 采纳（有帮助）机制（幂等；interaction_reply 为核心表不加列，走扩展表）
ALTER TABLE interaction_reply_extra ADD COLUMN ai_checked TINYINT NOT NULL DEFAULT 0
  COMMENT 'AI 回复教师检查状态（0 待检查/1 已检查）';
ALTER TABLE interaction_reply_extra ADD COLUMN helpful TINYINT NOT NULL DEFAULT 0
  COMMENT '学员采纳标记（1=有帮助；按回复唯一）';
ALTER TABLE interaction_reply_extra ADD INDEX idx_ai_checked (ai_checked);
