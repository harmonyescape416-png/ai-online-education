-- ============================================================
-- 13_m12_evaluation.sql —— M12 AI 批量评测扩展表（用户已批准）
-- 幂等执行；不覆盖历史迁移；原始 12 张核心表与 ai_eval_case（11 字段）零改动。
-- 设计决策登记 docs/decisions.md D12-1：
--   1) ai_eval_run   评测批次（含配置快照/状态机/防并发唯一运行批次）
--   2) ai_eval_result 单条结果（只插入不覆盖，重试=新 attempt 行，历史可追溯）
--   3) ai_eval_case_meta 标注扩展（原始 11 字段无标注载体：应拒答/需引用/标注理由等）
-- ============================================================


-- ------------------------------------------------------------
-- 1) ai_eval_run 评测批次
--    active_flag 生成列：仅「运行中」批次为 1 → UNIQUE 保证全局至多一个运行中批次
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_eval_run` (
  `id` BIGINT NOT NULL COMMENT '批次ID（雪花）',
  `name` VARCHAR(100) NOT NULL COMMENT '批次名称',
  `exec_mode` VARCHAR(10) NOT NULL COMMENT '执行模式：MOCK=协议验证 / REAL=真实模型',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0运行中 1成功 2失败 3部分成功 4已终止 5不可执行(REAL未配置)',
  `provider` VARCHAR(20) NULL COMMENT '模型提供方：MOCK/DASHSCOPE',
  `model_code` VARCHAR(50) NULL COMMENT '模型编码（MOCK 为 MOCK；真实如 qwen-plus）',
  `embedding_provider` VARCHAR(20) NULL COMMENT 'Embedding 提供方：MOCK/DASHSCOPE',
  `kb_physical_index` VARCHAR(64) NULL COMMENT '知识库别名当前物理索引（如 kzai-knowledge-v9001）',
  `prompt_snapshot` VARCHAR(2000) NULL COMMENT 'Prompt 生效版本摘要（版本号 + 五类模板 SHA-256）',
  `case_ids` VARCHAR(2000) NULL COMMENT '本批选定的用例ID快照（JSON 数组）',
  `total_cases` INT NOT NULL DEFAULT 0 COMMENT '选定用例数',
  `success_count` INT NOT NULL DEFAULT 0 COMMENT '执行完成且判定通过',
  `failed_count` INT NOT NULL DEFAULT 0 COMMENT '执行完成但判定失败',
  `error_count` INT NOT NULL DEFAULT 0 COMMENT '执行失败/超时（未产生有效判定）',
  `skipped_count` INT NOT NULL DEFAULT 0 COMMENT '跳过（超 deadline 等）',
  `executor_id` BIGINT NOT NULL COMMENT '执行人（管理员用户ID）',
  `error` VARCHAR(500) NULL COMMENT '批次级错误信息',
  `create_time` DATETIME NOT NULL,
  `start_time` DATETIME NULL,
  `end_time` DATETIME NULL,
  `update_time` DATETIME NOT NULL,
  `active_flag` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` = 0 THEN 1 ELSE NULL END) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active_run` (`active_flag`),
  KEY `idx_run_status_create` (`status`, `create_time`),
  KEY `idx_run_executor` (`executor_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 评测批次（M12）';

-- ------------------------------------------------------------
-- 2) ai_eval_result 单条结果（每行=某批次下某用例某次尝试）
--    UNIQUE(run_id, case_id, attempt)：重试=新 attempt 新行，历史结果永不覆盖
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_eval_result` (
  `id` BIGINT NOT NULL COMMENT '结果ID（雪花）',
  `run_id` BIGINT NOT NULL COMMENT '所属批次',
  `case_id` BIGINT NOT NULL COMMENT '评测用例ID',
  `attempt` INT NOT NULL DEFAULT 1 COMMENT '尝试次数（重试产生新行 attempt+1）',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待执行 1完成(判定有效) 2执行失败 3超时 4跳过',
  `category` VARCHAR(50) NULL COMMENT '用例类别快照（批次内稳定）',
  `expected_intent` VARCHAR(50) NULL COMMENT '期望意图快照',
  `expected_doc_id` VARCHAR(64) NULL COMMENT '期望文档/课程ID 快照',
  `actual_intent` VARCHAR(50) NULL COMMENT '实际路由意图（RECOMMEND/CONSULT/BUY/KNOWLEDGE/OTHER）',
  `route_latency_ms` INT NULL COMMENT '路由耗时（真实测量）',
  `actual_doc_ids` VARCHAR(1000) NULL COMMENT '实际召回文档ID（JSON 数组，TopK6 内）',
  `actual_doc_versions` VARCHAR(1000) NULL COMMENT '实际召回切片版本（JSON，审计用）',
  `rag_hit` TINYINT NULL COMMENT 'Hit@6 单例判定：1=期望文档在召回前6 0=未命中 NULL=不适用/执行失败',
  `rag_empty_recall` TINYINT NULL COMMENT '1=空召回（与执行失败区分）',
  `rag_latency_ms` INT NULL COMMENT 'RAG 检索耗时（真实测量）',
  `answer` TEXT NULL COMMENT '回答全文（评测留存）',
  `answer_latency_ms` INT NULL COMMENT '回答生成耗时（真实测量）',
  `first_token_ms` INT NULL COMMENT '首字延迟（真实测量；未流式为 NULL）',
  `latency_ms` INT NULL COMMENT '端到端耗时（真实测量）',
  `prompt_tokens` INT NULL COMMENT '真实 provider usage；无则 NULL',
  `completion_tokens` INT NULL COMMENT '同上',
  `total_tokens` INT NULL COMMENT '同上',
  `token_source` VARCHAR(20) NULL COMMENT 'provider/unavailable/estimated/mock_na',
  `refusal` TINYINT NULL COMMENT '模型是否拒答（自动规则判定，规则见 EvalRefusalRules）',
  `refusal_rule` VARCHAR(200) NULL COMMENT '拒答判定命中的规则（可审计）',
  `has_citation` TINYINT NULL COMMENT '回答是否引用了召回文档来源（自动规则）',
  `leak_suspected` TINYINT NULL DEFAULT NULL COMMENT '疑似越权泄露（自动标记，待人工复核）',
  `write_tool_attempt` TINYINT NULL COMMENT '尝试调用写工具次数（REAL Dry-run 记录；MOCK 为 NULL）',
  `passed` TINYINT NOT NULL DEFAULT 0 COMMENT '判定通过：0否 1是',
  `verdict_rule` VARCHAR(100) NULL COMMENT '判定规则（ROUTE_INTENT/RAG_HIT_CITATION/REFUSAL 等）',
  `fail_reason` VARCHAR(500) NULL COMMENT '判定失败原因（区分执行失败）',
  `provider` VARCHAR(20) NULL COMMENT '本条执行的实际提供方',
  `model_code` VARCHAR(50) NULL COMMENT '本条执行的实际模型',
  `error` VARCHAR(500) NULL COMMENT '执行失败原因（区别于判定失败）',
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_case_attempt` (`run_id`, `case_id`, `attempt`),
  KEY `idx_result_run_status` (`run_id`, `status`),
  KEY `idx_result_case` (`case_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 评测单条结果（M12）';

-- ------------------------------------------------------------
-- 3) ai_eval_case_meta 标注扩展（1:1，case_id 主键）
--    原始 ai_eval_case 11 字段保持不变；本表承载标注所需的最小附加信息
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_eval_case_meta` (
  `case_id` BIGINT NOT NULL COMMENT '评测用例ID（=ai_eval_case.id）',
  `should_refuse` TINYINT NOT NULL DEFAULT 0 COMMENT '是否应当拒答：0否 1是',
  `needs_citation` TINYINT NOT NULL DEFAULT 0 COMMENT '是否需要引用来源：0否 1是',
  `expect_tool` VARCHAR(50) NULL COMMENT '期望工具选择（REAL Dry-run 记录；MOCK 不适用）',
  `refuse_reason` VARCHAR(200) NULL COMMENT '应拒答原因（无证据/越权/越界/注入等）',
  `note` VARCHAR(500) NOT NULL COMMENT '标注理由（人工标注，非模型生成）',
  `data_deps` VARCHAR(200) NULL COMMENT '真实数据依赖（doc:xxx / course:1001 / admin_test_access）',
  `source` VARCHAR(100) NOT NULL COMMENT '标注来源（需求文档章节/真实库数据推导）',
  `is_regression` TINYINT NOT NULL DEFAULT 0 COMMENT '是否回归用例集：0否 1是',
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`case_id`),
  KEY `idx_meta_regression` (`is_regression`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 评测用例标注扩展（M12）';
