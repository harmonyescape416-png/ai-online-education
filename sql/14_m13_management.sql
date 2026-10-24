-- ============================================================
-- 课栈智伴 M13：平台监管、操作审计、系统参数、运营看板
-- 迁移：sql/14_m13_management.sql（幂等，可重复执行，不覆盖 13 号及更早脚本）
-- 用户批准：2026-10-20（四张扩展表 + sys_operation_log 时间索引；
--          不减少表数量；原始 12 张核心表零改动）
-- 全部表位于各服务实际共用的 course_stack_ai Schema。
-- ============================================================

-- ------------------------------------------------------------
-- 1. sys_session_review 会话监管复核（kzai-aigc 服务域）
--    - 只插入不覆盖：同一会话可多次标记/处理，每次一行，复核历史完整可追溯；
--    - Redis 消息过期后复核记录仍可追溯（本表不存消息正文，不冒充原始消息存在）；
--    - 所有写入仅 ADMIN 监管接口触发（服务端校验）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_session_review` (
  `id`              BIGINT       NOT NULL COMMENT '雪花ID',
  `session_id`      VARCHAR(64)  NOT NULL COMMENT '会话ID（chat_session.session_id，逻辑关联不设外键）',
  `user_id`         BIGINT       NOT NULL COMMENT '会话归属用户ID（标记时快照，不随用户改名变化）',
  `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0待复核 1已处理-无违规 2已处理-确认违规 3已处理-其他',
  `reason`          VARCHAR(255) NOT NULL COMMENT '标记原因（监管必填）',
  `handle_note`     VARCHAR(500) NULL COMMENT '处理备注（处理时填写）',
  `marked_by`       BIGINT       NOT NULL COMMENT '标记人（管理员）',
  `marked_by_name`  VARCHAR(32)  NOT NULL COMMENT '标记人姓名快照',
  `marked_at`       DATETIME     NOT NULL COMMENT '标记时间',
  `handled_by`      BIGINT       NULL COMMENT '处理人（管理员）',
  `handled_by_name` VARCHAR(32)  NULL COMMENT '处理人姓名快照',
  `handled_at`      DATETIME     NULL COMMENT '处理时间',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ssr_session` (`session_id`),
  KEY `idx_ssr_status_time` (`status`, `marked_at`),
  KEY `idx_ssr_user` (`user_id`, `marked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 会话监管复核（M13）';

-- ------------------------------------------------------------
-- 2. sys_config_param 系统参数（kzai-aigc 服务域；每参数一行：生效值+草稿）
--    - 草稿与生效值分离：保存草稿不影响运行；
--    - version 乐观锁：并发编辑/发布以 version 校验，防旧数据覆盖新配置；
--    - 仅存参数键值与范围，不含 API Key 等敏感凭据。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_config_param` (
  `id`                BIGINT       NOT NULL COMMENT '雪花ID',
  `param_key`         VARCHAR(64)  NOT NULL COMMENT '参数键（如 knowledge.top-k）',
  `param_name`        VARCHAR(64)  NOT NULL COMMENT '显示名',
  `description`       VARCHAR(255) NOT NULL COMMENT '参数说明（页面展示）',
  `effective_value`   VARCHAR(128) NOT NULL COMMENT '当前生效值（发布后的正式值）',
  `draft_value`       VARCHAR(128) NULL COMMENT '草稿值（NULL=无草稿；保存草稿不影响运行）',
  `value_type`        VARCHAR(16)  NOT NULL COMMENT '值类型：INT/DOUBLE',
  `min_value`         VARCHAR(32)  NOT NULL COMMENT '合法下界（含）',
  `max_value`         VARCHAR(32)  NOT NULL COMMENT '合法上界（含）',
  `version`           INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本（草稿保存/发布条件更新校验）',
  `draft_updated_by`  BIGINT       NULL COMMENT '草稿最后编辑人',
  `draft_update_time` DATETIME     NULL COMMENT '草稿最后编辑时间',
  `last_published_by` BIGINT       NULL COMMENT '最后发布人',
  `publish_time`      DATETIME     NULL COMMENT '最后生效时间',
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scp_key` (`param_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统参数（生效值+草稿+乐观锁，M13）';

-- ------------------------------------------------------------
-- 3. sys_config_publish 参数发布记录（kzai-aigc 服务域；只插入，发布历史）
--    - 区分「Nacos 发布结果」与「运行时生效验证」（runtime_verified）；
--    - 发布失败保留最后一次有效配置（values_before 即回退依据）；
--    - 仅作系统参数发布审计，不作为 Prompt 版本管理系统（D8-7 边界不变）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_config_publish` (
  `id`                BIGINT        NOT NULL COMMENT '雪花ID',
  `publish_version`   INT           NOT NULL COMMENT '发布序号（单调递增）',
  `param_keys`        VARCHAR(500)  NOT NULL COMMENT '本次发布参数键 JSON 数组',
  `config_snapshot`   VARCHAR(1000) NOT NULL COMMENT '发布后全量参数快照摘要 JSON（键=生效值）',
  `values_before`     VARCHAR(1000) NOT NULL COMMENT '发布前生效值 JSON（失败回退依据）',
  `values_after`      VARCHAR(1000) NOT NULL COMMENT '发布目标值 JSON',
  `publish_status`    TINYINT       NOT NULL COMMENT '状态：0发布中 1发布成功 2发布失败(已回退)',
  `runtime_verified`  TINYINT       NOT NULL DEFAULT 0 COMMENT '运行时生效验证：0未验证 1已验证服务读取新值 2验证失败(配置已回退)',
  `nacos_data_id`     VARCHAR(100)  NOT NULL COMMENT 'Nacos Data-ID',
  `error`             VARCHAR(500)  NULL COMMENT '失败原因',
  `published_by`      BIGINT        NOT NULL COMMENT '发布人（管理员）',
  `published_by_name` VARCHAR(32)   NOT NULL COMMENT '发布人姓名',
  `effective_time`    DATETIME      NULL COMMENT '运行时确认生效时间',
  `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_scsv_version` (`publish_version`),
  KEY `idx_scsv_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统参数发布记录（M13，只插入）';

-- ------------------------------------------------------------
-- 4. ai_model_call_log 模型调用日志（kzai-aigc 服务域）
--    - 来自真实调用链路（ChatService 轮终态/路由终态/课程问答生成），
--      不允许由评测结果或模拟数据伪造生产模型指标（评测走 provider=MOCK 显著标记）；
--    - 口径：source=CHAT/QUESTION 每个业务请求轮次恰好一条（底层 provider 内部重试
--      不单独成行）→ 模型调用成功率分母=业务请求轮次；
--    - first_token_ms 仅真实测量（流式首增量）时记录，否则 NULL；
--    - token 仅 provider 返回的真实 usage，未返回存 NULL，禁止字符数估算冒充；
--    - 不存 Prompt、对话正文、JWT、API Key 等敏感信息；
--    - 写入失败仅 WARN 告警，绝不阻断用户对话；
--    - 保留策略：90 天，由操作日志清理任务同批处理（见 90 天保留实现）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_model_call_log` (
  `id`                BIGINT       NOT NULL COMMENT '雪花ID',
  `request_id`        VARCHAR(64)  NOT NULL COMMENT '请求追踪ID',
  `user_id`           BIGINT       NULL COMMENT '触发用户（服务端可信身份；路由等无用户上下文为 NULL）',
  `session_id`        VARCHAR(64)  NULL COMMENT '会话ID（对话链路）',
  `source`            VARCHAR(20)  NOT NULL COMMENT '调用来源：CHAT对话/ROUTE路由/QUESTION课程问答AI/TITLE自动标题',
  `agent`             VARCHAR(20)  NULL COMMENT '路由命中 Agent（RECOMMEND/CONSULT/BUY/KNOWLEDGE/OTHER）',
  `provider`          VARCHAR(20)  NOT NULL COMMENT '提供方：DASHSCOPE(REAL)/MOCK（严格区分，MOCK 不得计入真实指标）',
  `model_code`        VARCHAR(50)  NULL COMMENT '模型编码（如 qwen-plus；MOCK 为 MOCK）',
  `success`           TINYINT      NOT NULL COMMENT '调用结果：0失败 1成功',
  `error_kind`        VARCHAR(30)  NULL COMMENT '错误类别（TIMEOUT/RATE_LIMIT/UPSTREAM_ERROR 等）',
  `route_latency_ms`  INT          NULL COMMENT '路由耗时（仅 ROUTE 行）',
  `first_token_ms`    INT          NULL COMMENT '首字延迟（仅真实测量时记录，否则 NULL）',
  `total_latency_ms`  INT          NULL COMMENT '端到端耗时',
  `prompt_tokens`     INT          NULL COMMENT '真实输入 token（provider 未返回为 NULL）',
  `completion_tokens` INT          NULL COMMENT '真实输出 token（同上）',
  `total_tokens`      INT          NULL COMMENT '真实总 token（同上）',
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_amcl_time` (`create_time`),
  KEY `idx_amcl_user_time` (`user_id`, `create_time`),
  KEY `idx_amcl_source_time` (`source`, `create_time`),
  KEY `idx_amcl_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='模型调用日志（M13，真实链路）';

-- ------------------------------------------------------------
-- 5. sys_operation_log 补充时间索引（90 天保留清理按时间批量删除）
--    - M2 扩展表（非原始 12 张核心表），不改动任何已有字段与审计记录；
--    - 先查重避免重复创建（幂等）。
-- ------------------------------------------------------------
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = 'course_stack_ai'
                      AND table_name = 'sys_operation_log'
                      AND index_name = 'idx_create_time');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE `course_stack_ai`.`sys_operation_log` ADD INDEX `idx_create_time` (`create_time`)',
    'SELECT ''idx_create_time 已存在，跳过'' AS note');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
