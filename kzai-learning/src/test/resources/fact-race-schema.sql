DROP TABLE IF EXISTS learning_activity_fact;
DROP TABLE IF EXISTS learning_first_event;
DROP TABLE IF EXISTS learning_fact_coverage;

CREATE TABLE learning_fact_coverage (
  id BIGINT NOT NULL PRIMARY KEY,
  facts_since DATETIME NOT NULL
);
INSERT INTO learning_fact_coverage (id, facts_since) VALUES (1, '2000-01-01 00:00:00');

CREATE TABLE learning_activity_fact (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  chapter_id BIGINT NOT NULL,
  biz_date DATE NOT NULL,
  study_seconds BIGINT NOT NULL DEFAULT 0,
  chapter_first_finished TINYINT NOT NULL DEFAULT 0,
  course_first_finished TINYINT NOT NULL DEFAULT 0,
  activity_time DATETIME NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  UNIQUE KEY uk_user_chapter_day (user_id, course_id, chapter_id, biz_date)
);

CREATE TABLE learning_lesson_progress (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  chapter_id BIGINT NOT NULL,
  study_seconds BIGINT NOT NULL DEFAULT 0,
  video_position_sec INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL
);

CREATE TABLE learning_first_event (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  chapter_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  biz_date DATE NOT NULL,
  create_time DATETIME NOT NULL,
  UNIQUE KEY uk_first_event (user_id, course_id, chapter_id, event_type)
);
