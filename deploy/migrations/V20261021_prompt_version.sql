-- ============================================================
-- M15.19-D：Prompt 模板版本持久化（草稿/历史/回滚；用户批准增量迁移）
-- 独立扩展表，不修改 Nacos 配置与 12 核心表；幂等
-- ============================================================
CREATE TABLE IF NOT EXISTS prompt_version (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '版本ID',
    agent_type  VARCHAR(30)  NOT NULL COMMENT '模板类型（五类之一）',
    content     TEXT         NOT NULL COMMENT '模板内容',
    version_no  INT          NOT NULL COMMENT '该类型内递增版本号',
    action      VARCHAR(20)  NOT NULL COMMENT '动作：PUBLISH 发布 / ROLLBACK 回滚 / DRAFT 草稿',
    operator    BIGINT       NOT NULL COMMENT '操作人 userId',
    result_ok   TINYINT      NOT NULL DEFAULT 1 COMMENT '发布结果（1 成功/0 失败）',
    result_msg  VARCHAR(500) NULL COMMENT '发布结果说明（Nacos 校验信息）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_type (agent_type, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Prompt 模板版本历史（M15.19-D）';
