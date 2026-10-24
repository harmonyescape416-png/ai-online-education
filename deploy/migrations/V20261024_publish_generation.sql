-- 已有环境升级。不要重跑 sql/15，以免依赖当时的库状态。
-- 本文件只增加审核轮次列，不更新已有 publish_generation（默认 0），不碰 facts_since。
-- 前置：V20261023_learning_fact_and_kb_source.sql 已执行。
-- 空库不要单独跑本文件，按 sql/ 字典序执行到 sql/16。

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ai_knowledge_document ADD COLUMN publish_generation INT NOT NULL DEFAULT 0 COMMENT ''审核轮次，发布或撤回时加一''',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'ai_knowledge_document' AND column_name = 'publish_generation');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ai_knowledge_index_job ADD COLUMN publish_generation INT NOT NULL DEFAULT 0 COMMENT ''任务所属审核轮次''',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'ai_knowledge_index_job' AND column_name = 'publish_generation');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
