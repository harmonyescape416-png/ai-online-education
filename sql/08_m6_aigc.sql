-- =============================================================================
-- M6 AI 会话能力 数据库增量迁移（幂等）
-- 数据库: course_stack_ai (MySQL 8)
--
-- 重要：M6 严格复用原始 12 张核心表中的 chat_session（8 字段零增删）：
--   id, session_id, user_id, title, create_time, update_time, creater, updater
-- 消息正文不落此表（仅存 Redis List: CHAT:{userId}_{sessionId}），
-- 因此本文件不包含任何 ALTER TABLE chat_session 的字段变更。
--
-- 本文件只做两件事：
--   1) 校验 chat_session 字段集合与原始设计一致（防误改）；
--   2) 幂等补齐历史列表查询所需索引。
-- =============================================================================

-- 1) 字段集合校验（8 个原始字段，不多不少）
SELECT
    CASE
        WHEN (
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = 'course_stack_ai' AND table_name = 'chat_session'
              AND column_name IN (
                'id','session_id','user_id','title',
                'create_time','update_time','creater','updater')
        ) = 8
        THEN 'OK: chat_session 字段集合与原始设计一致'
        ELSE 'ERROR: chat_session 字段集合发生变化'
    END AS check_columns;

-- 2) 幂等创建 session_id 唯一索引
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = 'course_stack_ai'
      AND table_name = 'chat_session'
      AND index_name = 'uk_session_id'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE chat_session ADD UNIQUE INDEX uk_session_id (session_id)',
    'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 幂等创建「按用户 + 更新时间」历史列表索引
SET @idx2_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = 'course_stack_ai'
      AND table_name = 'chat_session'
      AND index_name = 'idx_user_time'
);
SET @sql2 := IF(@idx2_exists = 0,
    'ALTER TABLE chat_session ADD INDEX idx_user_time (user_id, update_time)',
    'DO 0');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
