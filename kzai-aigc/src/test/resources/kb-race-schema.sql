DROP TABLE IF EXISTS ai_eval_run;
CREATE TABLE ai_eval_run (
  id BIGINT NOT NULL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  exec_mode VARCHAR(10) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  error VARCHAR(500) NULL,
  end_time DATETIME NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL
);

DROP TABLE IF EXISTS sys_config_param;
CREATE TABLE sys_config_param (
  id BIGINT NOT NULL PRIMARY KEY,
  param_key VARCHAR(64) NOT NULL,
  param_name VARCHAR(64) NOT NULL,
  description VARCHAR(255) NOT NULL,
  effective_value VARCHAR(128) NOT NULL,
  draft_value VARCHAR(128) NULL,
  value_type VARCHAR(16) NOT NULL,
  min_value VARCHAR(32) NOT NULL,
  max_value VARCHAR(32) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  draft_updated_by BIGINT NULL,
  draft_update_time DATETIME NULL,
  last_published_by BIGINT NULL,
  publish_time DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_scp_key (param_key)
);

DROP TABLE IF EXISTS ai_knowledge_index_job;
DROP TABLE IF EXISTS ai_knowledge_document;

CREATE TABLE ai_knowledge_document (
  id BIGINT NOT NULL PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  doc_type VARCHAR(20) NOT NULL,
  course_id BIGINT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_path VARCHAR(500) NOT NULL,
  file_size BIGINT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  char_count INT NOT NULL DEFAULT 0,
  chunk_count INT NOT NULL DEFAULT 0,
  index_version INT NULL,
  index_status TINYINT NOT NULL DEFAULT 0,
  fail_reason VARCHAR(500) NULL,
  access_level VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
  review_status TINYINT NOT NULL DEFAULT 0,
  publish_generation INT NOT NULL DEFAULT 0,
  reviewer BIGINT NULL,
  review_time DATETIME NULL,
  publisher BIGINT NULL,
  source_resource_id BIGINT NULL,
  source_version VARCHAR(64) NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_source_resource (source_resource_id, source_version)
);

CREATE TABLE ai_knowledge_index_job (
  id BIGINT NOT NULL PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  action VARCHAR(20) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  attempt INT NOT NULL DEFAULT 0,
  error VARCHAR(500) NULL,
  operator BIGINT NULL,
  publish_generation INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  active_flag TINYINT GENERATED ALWAYS AS (IF(status IN (0,1), 1, NULL)) STORED,
  UNIQUE KEY uk_doc_active (doc_id, active_flag)
);
