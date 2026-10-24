DROP TABLE IF EXISTS learning_plan_pause;
DROP TABLE IF EXISTS learning_plan_goal;
DROP TABLE IF EXISTS learning_plan;
DROP TABLE IF EXISTS learning_first_event;
DROP TABLE IF EXISTS learning_fact_coverage;

CREATE TABLE learning_fact_coverage (
  id BIGINT NOT NULL PRIMARY KEY,
  facts_since DATETIME NOT NULL
);
INSERT INTO learning_fact_coverage (id, facts_since) VALUES (1, '2000-01-01 00:00:00');

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

CREATE TABLE learning_plan (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  status TINYINT NOT NULL,
  joined_at DATETIME NOT NULL,
  quit_at DATETIME NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  open_flag TINYINT GENERATED ALWAYS AS (CASE WHEN status IN (1, 2) THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_plan_open (user_id, course_id, open_flag)
);

CREATE TABLE learning_plan_goal (
  id BIGINT NOT NULL PRIMARY KEY,
  plan_id BIGINT NOT NULL,
  weekly_goal INT NOT NULL,
  effective_week DATE NOT NULL,
  create_time DATETIME NOT NULL,
  UNIQUE KEY uk_plan_goal_week (plan_id, effective_week)
);

CREATE TABLE learning_plan_pause (
  id BIGINT NOT NULL PRIMARY KEY,
  plan_id BIGINT NOT NULL,
  paused_at DATETIME NOT NULL,
  resumed_at DATETIME NULL,
  create_time DATETIME NOT NULL
);
