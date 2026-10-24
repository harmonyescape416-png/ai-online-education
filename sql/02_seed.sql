-- =============================================================
-- SEED 数据（演示/联调用，明确标记，非生产数据）
-- 说明：本文件所有记录均为 seed 数据，用于 M1 阶段登录、课程列表
--       等真实接口的联调验证，写入 course_stack_ai 数据库。
-- 密码：统一 BCrypt(rounds=10) 哈希，明文见注释，不入库明文。
-- =============================================================

SET NAMES utf8mb4;

-- ---------- user（SEED）----------
-- 角色映射依据《数据库设计说明书》3.3.1：type 1员工(管理员/运营)，2学员，3老师
-- 明文：admin123 / teacher123 / student123
INSERT INTO `user` (id, username, cell_phone, password, status, type, creater, updater) VALUES
 (1,    'admin',     '13800000001', '$2a$10$9lkB1snHzCJG1aIdcqqgJeG5tqqr6LzWcjQ5GgDRrh3G7ahBSDEHu', 1, 1, 1, 1),
 (2,    'teacher01', '13800000002', '$2a$10$55iuLKYM7Qv8TwjdST7i3uQGvYNU6Jbe/romOmd9EAzmkMyBhkWye', 1, 3, 1, 1),
 (3,    'student01', '13800000003', '$2a$10$AvKGC7MBJCNHfZIljGX1VOznWFMFj6SolO./J469YnV4OyfcBoPsm', 1, 2, 1, 1),
 (9999, 'ai_bot',    NULL,          '$2a$10$9lkB1snHzCJG1aIdcqqgJeG5tqqr6LzWcjQ5GgDRrh3G7ahBSDEHu', 1, 1, 1, 1)  -- AI 系统账号（设计说明书 4.6 节：自动回复落库使用系统用户 9999）
ON DUPLICATE KEY UPDATE username = VALUES(username);

-- ---------- course_category（SEED）----------
INSERT INTO `course_category` (id, name, parent_id, level, sort, status) VALUES
 (101, 'IT技术',   0,   1, 1, 1),
 (102, '职业素养', 0,   1, 2, 1),
 (201, '后端开发', 101, 2, 1, 1),
 (202, '人工智能', 101, 2, 2, 1),
 (203, '职场沟通', 102, 2, 1, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- ---------- course（SEED）----------
-- status：1待上架，2已上架，3下架，4已完结；price 单位分
INSERT INTO `course` (id, name, course_type, cover_url, first_cate_id, second_cate_id, third_cate_id,
                      free, price, status, purchase_start_time, purchase_end_time, valid_duration,
                      section_num, score, creater, updater) VALUES
 (1001, 'Java 微服务架构实战',      2, 'https://placehold.co/480x270?text=Java',     101, 201, NULL, 0, 29900, 2, NOW(), DATE_ADD(NOW(), INTERVAL 1 YEAR), 12, 4, 47, 2, 2),
 (1002, 'Spring Cloud Alibaba 进阶', 2, 'https://placehold.co/480x270?text=SCA',      101, 201, NULL, 0, 39900, 2, NOW(), DATE_ADD(NOW(), INTERVAL 1 YEAR), 12, 3, 45, 2, 2),
 (1003, '大模型应用开发入门',        2, 'https://placehold.co/480x270?text=LLM',      101, 202, NULL, 0, 49900, 2, NOW(), DATE_ADD(NOW(), INTERVAL 1 YEAR), 6,  3, 48, 2, 2),
 (1004, 'RAG 检索增强生成实践',      1, 'https://placehold.co/480x270?text=RAG',      101, 202, NULL, 0, 59900, 2, NOW(), DATE_ADD(NOW(), INTERVAL 1 YEAR), 6,  2, 46, 2, 2),
 (1005, '职场高效沟通技巧',          2, 'https://placehold.co/480x270?text=Comm',     102, 203, NULL, 1,     0, 2, NOW(), DATE_ADD(NOW(), INTERVAL 1 YEAR), 3,  2, 43, 2, 2),
 (1006, '待上架课程示例（草稿）',    2, NULL,                                           101, 201, NULL, 0, 19900, 1, NULL, NULL, 12, 0, 0,  2, 2)
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- ---------- course_content（SEED，id 与 course.id 一致）----------
INSERT INTO `course_content` (id, course_introduce, use_people, course_detail, dep_id, creater, updater) VALUES
 (1001, '从单体到微服务的完整演进路径，覆盖注册中心、配置中心、网关与远程调用。',
        '具备 Java 基础、想系统掌握微服务落地的后端工程师',
        '课程包含服务拆分、Nacos 注册与配置、Gateway 路由与鉴权、OpenFeign 调用、链路追踪等模块。', NULL, 2, 2),
 (1002, '深入 Spring Cloud Alibaba 生态，掌握生产级微服务治理能力。',
        '已了解 Spring Boot，希望进阶微服务治理的开发者',
        '覆盖服务注册发现、配置热更新、熔断限流、分布式事务与灰度发布。', NULL, 2, 2),
 (1003, '面向应用开发者的大模型入门课，讲透 Prompt、工具调用与流式输出。',
        '希望把大模型能力接入业务系统的开发者',
        '包含模型选型、Prompt 工程、Function/Tool Calling、SSE 流式返回与成本优化。', NULL, 2, 2),
 (1004, '从零搭建企业知识库问答，覆盖切片、向量化、检索与重排。',
        '需要落地知识库问答的开发与算法工程师',
        '包含文档解析、切片策略、Embedding、向量库检索、召回评估与效果调优。', NULL, 2, 2),
 (1005, '面向职场新人的沟通方法论与实操训练。',
        '初入职场或希望提升沟通效率的在职人员',
        '包含结构化表达、跨部门协作、汇报与反馈技巧。', NULL, 2, 2),
 (1006, '该课程为草稿示例，用于验证「仅展示已上架课程」的筛选逻辑。', NULL, NULL, NULL, 2, 2)
ON DUPLICATE KEY UPDATE course_introduce = VALUES(course_introduce);

-- ---------- course_chapter（SEED）----------
INSERT INTO `course_chapter` (id, course_id, name, chapter_order, description) VALUES
 (1101, 1001, '第1章 微服务演进与设计原则', 1, '单体架构痛点与服务拆分原则'),
 (1102, 1001, '第2章 注册中心与配置中心',   2, 'Nacos 服务注册与配置热更新'),
 (1103, 1001, '第3章 网关与统一鉴权',       3, 'Gateway 路由、过滤与 JWT 校验'),
 (1104, 1001, '第4章 服务调用与容错',       4, 'OpenFeign 调用、超时与降级'),
 (1201, 1002, '第1章 服务治理总览',         1, '治理目标与能力地图'),
 (1202, 1002, '第2章 流量治理与限流',       2, '限流、熔断与降级策略'),
 (1203, 1002, '第3章 分布式事务',           3, 'Seata AT 模式实践'),
 (1301, 1003, '第1章 大模型基础',           1, '模型能力与调用方式'),
 (1302, 1003, '第2章 Prompt 工程',          2, '提示词设计与模板管理'),
 (1303, 1003, '第3章 流式与工具调用',       3, 'SSE 输出与 Tool Calling'),
 (1401, 1004, '第1章 RAG 架构总览',         1, '离线索引与在线检索'),
 (1402, 1004, '第2章 切片与向量化',         2, '500 字切片、50 字重叠与 Embedding'),
 (1501, 1005, '第1章 结构化表达',           1, '结论先行与金字塔原理'),
 (1502, 1005, '第2章 跨部门协作',           2, '协作边界与共识达成')
ON DUPLICATE KEY UPDATE name = VALUES(name);
