-- AI 课程推荐独立任务和偏好；不修改核心课程表，不读写教师知识库索引。
CREATE TABLE IF NOT EXISTS ai_course_recommend_sync (
  namespace VARCHAR(96) NOT NULL PRIMARY KEY,
  cursor_id BIGINT NOT NULL DEFAULT 0,
  cycle_no BIGINT NOT NULL DEFAULT 1,
  update_time DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_course_recommend_job (
  namespace VARCHAR(96) NOT NULL,
  course_id BIGINT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  fingerprint CHAR(64) NOT NULL,
  payload LONGTEXT NOT NULL,
  last_seen_cycle BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt INT NOT NULL DEFAULT 0,
  lease_owner VARCHAR(64) NULL,
  lease_until DATETIME(3) NULL,
  next_retry_time DATETIME(3) NOT NULL,
  last_error VARCHAR(500) NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY(namespace, course_id),
  KEY idx_recommend_retry(namespace, status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_course_recommend_profile (
  user_id BIGINT NOT NULL,
  session_id VARCHAR(100) NOT NULL,
  preferences TEXT NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY(user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
