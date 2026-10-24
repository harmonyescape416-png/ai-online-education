-- 升级顺序：已有库执行本文件一次（幂等）。新环境在 sql/14_m13_management.sql 之后执行 sql/15，两份脚本正文相同。
-- 不改写已有进度累计值，不回填历史日增量。
-- M16：学习事实、知识库来源关联。新环境在 sql/14_m13_management.sql 之后执行。
-- 不回填历史学习时长。facts_since 取本脚本执行时刻，更早的日期区间标记为覆盖不足。
-- 回退：DROP TABLE learning_activity_fact, learning_first_event, learning_fact_coverage；
--       ALTER 删除本次新增列并恢复 uk_sha256（仅当确认没有跨课程同内容文档时）。

CREATE TABLE IF NOT EXISTS `learning_fact_coverage` (
  `id`          BIGINT   NOT NULL COMMENT '固定为 1',
  `facts_since` DATETIME NOT NULL COMMENT '学习增量开始记账的时刻（Asia/Shanghai 解释业务日）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习事实覆盖起点';

INSERT INTO `learning_fact_coverage` (`id`, `facts_since`)
SELECT 1, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `learning_fact_coverage` WHERE `id` = 1);

CREATE TABLE IF NOT EXISTS `learning_activity_fact` (
  `id`                     BIGINT   NOT NULL COMMENT '主键',
  `user_id`                BIGINT   NOT NULL,
  `course_id`              BIGINT   NOT NULL,
  `chapter_id`             BIGINT   NOT NULL,
  `biz_date`               DATE     NOT NULL COMMENT '活动成功持久化时刻换算到 Asia/Shanghai 的业务日',
  `study_seconds`          BIGINT   NOT NULL DEFAULT 0 COMMENT '该日服务端最终认可的有效学习秒数增量',
  `chapter_first_finished` TINYINT  NOT NULL DEFAULT 0 COMMENT '1=该日首次完成此章节',
  `course_first_finished`  TINYINT  NOT NULL DEFAULT 0 COMMENT '1=该日首次完成此课程',
  `activity_time`          DATETIME NOT NULL COMMENT '本次成功持久化时间',
  `create_time`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_chapter_day` (`user_id`, `course_id`, `chapter_id`, `biz_date`),
  KEY `idx_course_day` (`course_id`, `biz_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习活动日事实（只记成功落库的增量）';

CREATE TABLE IF NOT EXISTS `learning_first_event` (
  `id`          BIGINT      NOT NULL,
  `user_id`     BIGINT      NOT NULL,
  `course_id`   BIGINT      NOT NULL,
  `chapter_id`  BIGINT      NOT NULL COMMENT '课程级事件使用 0',
  `event_type`  VARCHAR(32) NOT NULL COMMENT 'CHAPTER_FINISH / COURSE_FINISH',
  `biz_date`    DATE        NOT NULL,
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_first_event` (`user_id`, `course_id`, `chapter_id`, `event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首次完课事件（并发下只保留一条）';

-- 知识文档：同一资源版本幂等；相同内容可以属于不同课程，访问级别互不影响。
SET @sql = (
  SELECT IF(COUNT(*) = 0, 'SELECT 1', 'ALTER TABLE ai_knowledge_document DROP INDEX uk_sha256')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ai_knowledge_document' AND index_name = 'uk_sha256'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) > 0, 'SELECT 1',
    'ALTER TABLE ai_knowledge_document ADD COLUMN source_resource_id BIGINT NULL COMMENT ''课程资源ID'' AFTER publisher')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'ai_knowledge_document' AND column_name = 'source_resource_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) > 0, 'SELECT 1',
    'ALTER TABLE ai_knowledge_document ADD COLUMN source_version VARCHAR(64) NULL COMMENT ''资源不可变版本，当前等于资源ID'' AFTER source_resource_id')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'ai_knowledge_document' AND column_name = 'source_version'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) > 0, 'SELECT 1',
    'ALTER TABLE ai_knowledge_document ADD UNIQUE KEY uk_source_resource (source_resource_id, source_version)')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ai_knowledge_document' AND index_name = 'uk_source_resource'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) > 0, 'SELECT 1',
    'ALTER TABLE course_resource ADD COLUMN kb_doc_id BIGINT NULL COMMENT ''关联知识文档ID'' AFTER kb_status')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'course_resource' AND column_name = 'kb_doc_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) > 0, 'SELECT 1',
    'ALTER TABLE course_resource ADD COLUMN kb_review_status TINYINT NULL COMMENT ''0待审核 1已发布 2已撤回'' AFTER kb_doc_id')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'course_resource' AND column_name = 'kb_review_status'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) > 0, 'SELECT 1',
    'ALTER TABLE course_resource ADD COLUMN kb_index_status TINYINT NULL COMMENT ''与知识文档 index_status 一致'' AFTER kb_review_status')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'course_resource' AND column_name = 'kb_index_status'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) > 0, 'SELECT 1',
    'ALTER TABLE course_resource ADD COLUMN kb_fail_reason VARCHAR(500) NULL AFTER kb_index_status')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'course_resource' AND column_name = 'kb_fail_reason'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) > 0, 'SELECT 1',
    'ALTER TABLE course_resource ADD COLUMN kb_version VARCHAR(64) NULL AFTER kb_fail_reason')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'course_resource' AND column_name = 'kb_version'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
