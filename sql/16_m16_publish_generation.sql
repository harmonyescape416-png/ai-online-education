-- 审核轮次。旧索引任务只允许发布自己记下的 publish_generation。
-- 新环境在 sql/15_m16_stats_and_kb_source.sql 之后执行。
-- 已有库执行 deploy/migrations/V20261024_publish_generation.sql。重复执行不改已有轮次值。

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
