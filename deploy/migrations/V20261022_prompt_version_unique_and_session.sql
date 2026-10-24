-- ============================================================
-- M15.20-A：Prompt 版本唯一约束 + 会话模板版本绑定（幂等迁移，按序执行）
-- 前置检查：agent_type+version_no 重复记录（有重复时先人工归档，不得静默删除）
-- ============================================================
-- ① 唯一约束（重复时本语句失败——迁移前必须先核对上方检查结果）
ALTER TABLE prompt_version ADD UNIQUE INDEX uk_agent_version (agent_type, version_no);
-- ② 会话模板版本绑定（aigc 业务表；12 核心表零改动；存量 NULL=使用当前生效模板）
ALTER TABLE chat_session ADD COLUMN prompt_version_map TEXT NULL
  COMMENT '创建时各类型已发布版本号 JSON（如 {"CONSULT":3}）；NULL=存量会话用当前生效';
