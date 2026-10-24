-- 新环境在 sql/14_m13_management.sql 之后、sql/15_m16 之前执行。
-- 这些结构原先只在 deploy/migrations 的增量脚本里，空库只跑 sql/ 时会缺列。
-- 幂等：已有列或表时跳过。不改写已有行，也不设置 facts_since。

CREATE TABLE IF NOT EXISTS user_profile_ext (
    user_id     BIGINT       NOT NULL,
    nickname    VARCHAR(30)  NULL,
    avatar_url  VARCHAR(500) NULL,
    nickname_updated_at DATETIME NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户扩展资料';

CREATE TABLE IF NOT EXISTS prompt_version (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    agent_type  VARCHAR(30)  NOT NULL,
    content     TEXT         NOT NULL,
    version_no  INT          NOT NULL,
    action      VARCHAR(20)  NOT NULL,
    operator    BIGINT       NOT NULL,
    result_ok   TINYINT      NOT NULL DEFAULT 1,
    result_msg  VARCHAR(500) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_type (agent_type, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Prompt 模板版本';

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE prompt_version ADD UNIQUE INDEX uk_agent_version (agent_type, version_no)', 'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'prompt_version' AND index_name = 'uk_agent_version');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE chat_session ADD COLUMN prompt_version_map TEXT NULL COMMENT ''创建时各模板版本''', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'chat_session' AND column_name = 'prompt_version_map');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE interaction_reply_extra ADD COLUMN ai_checked TINYINT NOT NULL DEFAULT 0', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'interaction_reply_extra' AND column_name = 'ai_checked');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE interaction_reply_extra ADD COLUMN helpful TINYINT NOT NULL DEFAULT 0', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'interaction_reply_extra' AND column_name = 'helpful');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS enroll_compensation (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_id      BIGINT       NOT NULL,
    course_id     BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    last_error    VARCHAR(500) NULL,
    next_retry_at DATETIME     NULL,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_course (order_id, course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开课补偿';

CREATE TABLE IF NOT EXISTS interaction_reply_helpful (
    reply_id    BIGINT   NOT NULL,
    user_id     BIGINT   NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reply_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 回复有帮助记录';

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE course_resource ADD COLUMN replaced_by BIGINT NULL', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'course_resource' AND column_name = 'replaced_by');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE course_resource ADD COLUMN kb_status TINYINT NOT NULL DEFAULT 0', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'course_resource' AND column_name = 'kb_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE course_resource ADD COLUMN replace_time DATETIME NULL', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'course_resource' AND column_name = 'replace_time');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
