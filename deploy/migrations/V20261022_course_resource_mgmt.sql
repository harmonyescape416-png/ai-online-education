-- M15.20-E：资料管理（替换/历史/知识库提交状态；course_resource 非核心表，增量兼容）
ALTER TABLE course_resource ADD COLUMN replaced_by BIGINT NULL COMMENT '被新版本资源ID替换（保留历史链）';
ALTER TABLE course_resource ADD COLUMN kb_status TINYINT NOT NULL DEFAULT 0 COMMENT '知识库提交状态（0 未提交/1 已提交待管理员审核）';
ALTER TABLE course_resource ADD COLUMN replace_time DATETIME NULL COMMENT '替换时间';
