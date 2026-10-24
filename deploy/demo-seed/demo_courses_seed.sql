-- ============================================================
-- 课栈智伴 DEMO 演示课程数据（M15.13-R1）
-- 执行模型：纯 INSERT fail-fast（ID 冲突直接报错，不静默掩盖）；重复执行前须先跑 demo_rollback.sql
-- 标识：课程 ID 段 9100000000000000xxx（DEMO 专用段）；部署说明中登记来源
-- 关联：course_content.id 与 course.id 一致（数据库设计 3.3.4 原文）
-- 封面：本地主题 SVG（/covers/*.svg，原创资产）
-- 回滚：demo_rollback.sql（三表逻辑删除）；目标库：隔离演示库（course_stack_ai_m15），禁止在业务库执行
-- ============================================================

-- ① Java 程序设计基础
INSERT INTO course (id, name, course_type, cover_url, first_cate_id, second_cate_id, free, price, status, valid_duration, section_num, score, creater, deleted)
VALUES (9100000000000000001, 'Java 程序设计基础', 2, '/covers/java.svg',
        101,
        201,
        1, 0, 2, 12, 4, 0, 2, 0);
INSERT INTO course_content (id, course_introduce, use_people, course_detail, creater, deleted)
VALUES (9100000000000000001,
 '本课程面向编程零基础学员，系统讲解 Java 语言核心语法与面向对象编程思想。从开发环境搭建开始，逐步覆盖变量与数据类型、流程控制、数组、类与对象、继承与多态、常用集合框架及异常处理，帮助你建立扎实的编程思维。',
 '零基础学员、转行进入软件开发的学习者、在校计算机专业学生。',
 '课程共 4 章：第 1 章建立开发环境并完成第一个 Java 程序；第 2 章掌握变量、运算符与流程控制；第 3 章深入面向对象（类、继承、接口、多态）；第 4 章学习集合框架与异常处理。每章配有课堂练习与阶段小结，学完可独立编写结构清晰的 Java 控制台程序。', 2, 0);
INSERT INTO course_chapter (id, course_id, name, chapter_order, deleted) VALUES
(9100000000000000101, 9100000000000000001, '第1章 Java 入门与开发环境', 1, 0),
(9100000000000000102, 9100000000000000001, '第2章 数据类型、运算符与流程控制', 2, 0),
(9100000000000000103, 9100000000000000001, '第3章 面向对象编程核心', 3, 0),
(9100000000000000104, 9100000000000000001, '第4章 集合框架与异常处理', 4, 0);

-- ② Spring Boot 企业级开发实战
INSERT INTO course (id, name, course_type, cover_url, first_cate_id, second_cate_id, free, price, status, valid_duration, section_num, score, creater, deleted)
VALUES (9100000000000000002, 'Spring Boot 企业级开发实战', 2, '/covers/spring.svg',
        101,
        201,
        0, 19900, 2, 12, 4, 0, 2, 0);
INSERT INTO course_content (id, course_introduce, use_people, course_detail, creater, deleted)
VALUES (9100000000000000002,
 '本课程以企业真实项目为主线，讲解 Spring Boot 核心机制与工程实践。内容涵盖自动装配原理、Web 开发、数据访问、事务管理、参数校验、统一异常处理、日志规划与 Actuator 监控，帮助你具备独立搭建生产级后端服务的能力。',
 '已掌握 Java 基础与 Maven 的学习者、希望系统化提升后端工程能力的初中级开发者。',
 '课程共 4 章：第 1 章快速上手与自动装配原理；第 2 章 Web 开发与 RESTful 设计；第 3 章数据访问、事务与校验；第 4 章异常体系、日志与监控运维。课程贯穿一个图书管理后端项目，随章节逐步演进。', 2, 0);
INSERT INTO course_chapter (id, course_id, name, chapter_order, deleted) VALUES
(9100000000000000201, 9100000000000000002, '第1章 Spring Boot 快速上手与核心原理', 1, 0),
(9100000000000000202, 9100000000000000002, '第2章 Web 开发与 RESTful 接口设计', 2, 0),
(9100000000000000203, 9100000000000000002, '第3章 数据访问、事务与参数校验', 3, 0),
(9100000000000000204, 9100000000000000002, '第4章 异常体系、日志与监控', 4, 0);

-- ③ Spring Cloud 微服务架构
INSERT INTO course (id, name, course_type, cover_url, first_cate_id, second_cate_id, free, price, status, valid_duration, section_num, score, creater, deleted)
VALUES (9100000000000000003, 'Spring Cloud 微服务架构', 2, '/covers/spring.svg',
        101,
        201,
        0, 29900, 2, 12, 4, 0, 2, 0);
INSERT INTO course_content (id, course_introduce, use_people, course_detail, creater, deleted)
VALUES (9100000000000000003,
 '本课程讲解微服务架构的设计思想与 Spring Cloud 落地实践。从单体到微服务的演进出发，覆盖服务注册发现、配置中心、负载均衡、OpenFeign 远程调用、网关路由与鉴权、熔断降级与分布式链路追踪，帮助你掌握大型系统的拆分与治理方法。',
 '具备 Spring Boot 基础、希望理解分布式系统设计的中高级后端开发者。',
 '课程共 4 章：第 1 章微服务演进与设计原则；第 2 章服务注册发现与配置中心；第 3 章服务通信、网关与鉴权；第 4 章熔断降级、链路追踪与灰度发布。课程以电商后端拆分案例贯穿。', 2, 0);
INSERT INTO course_chapter (id, course_id, name, chapter_order, deleted) VALUES
(9100000000000000301, 9100000000000000003, '第1章 从单体到微服务的演进', 1, 0),
(9100000000000000302, 9100000000000000003, '第2章 注册中心与配置中心', 2, 0),
(9100000000000000303, 9100000000000000003, '第3章 服务通信、网关与统一鉴权', 3, 0),
(9100000000000000304, 9100000000000000003, '第4章 服务治理：熔断、限流与链路追踪', 4, 0);

-- ④ Vue3 前端项目开发
INSERT INTO course (id, name, course_type, cover_url, first_cate_id, second_cate_id, free, price, status, valid_duration, section_num, score, creater, deleted)
VALUES (9100000000000000004, 'Vue3 前端项目开发', 2, '/covers/vue.svg',
        101,
        202,
        0, 15900, 2, 12, 4, 0, 2, 0);
INSERT INTO course_content (id, course_introduce, use_people, course_detail, creater, deleted)
VALUES (9100000000000000004,
 '本课程系统讲解 Vue3 组合式 API 与现代前端工程化实践。内容涵盖响应式原理、组件通信、Vue Router 路由设计、Pinia 状态管理、Axios 封装与前后端联调，最终完成一个包含登录鉴权、列表、详情与表单的完整前端项目。',
 '具备 HTML/CSS/JS 基础、希望掌握现代前端框架的学习者与初级前端工程师。',
 '课程共 4 章：第 1 章 Vue3 基础与组合式 API；第 2 章组件设计与通信；第 3 章路由、状态管理与请求封装；第 4 章项目实战与构建部署。', 2, 0);
INSERT INTO course_chapter (id, course_id, name, chapter_order, deleted) VALUES
(9100000000000000401, 9100000000000000004, '第1章 Vue3 基础与组合式 API', 1, 0),
(9100000000000000402, 9100000000000000004, '第2章 组件设计与通信机制', 2, 0),
(9100000000000000403, 9100000000000000004, '第3章 路由、Pinia 与请求封装', 3, 0),
(9100000000000000404, 9100000000000000004, '第4章 项目实战与工程化部署', 4, 0);

-- ⑤ MySQL 数据库设计与优化
INSERT INTO course (id, name, course_type, cover_url, first_cate_id, second_cate_id, free, price, status, valid_duration, section_num, score, creater, deleted)
VALUES (9100000000000000005, 'MySQL 数据库设计与优化', 2, '/covers/db.svg',
        101,
        201,
        0, 19900, 2, 12, 4, 0, 2, 0);
INSERT INTO course_content (id, course_introduce, use_people, course_detail, creater, deleted)
VALUES (9100000000000000005,
 '本课程从业务建模出发讲解 MySQL 数据库设计与性能优化。内容涵盖表结构设计范式与反范式权衡、索引原理与执行计划、SQL 调优、事务与锁机制、主从复制与高可用方案，帮助你设计出高性能、可扩展的数据存储方案。',
 '后端开发者、数据库管理员及需要处理业务数据建模的技术人员。',
 '课程共 4 章：第 1 章表结构设计与数据类型选择；第 2 章索引原理与执行计划分析；第 3 章事务、锁与并发控制；第 4 章主从复制与高可用架构。', 2, 0);
INSERT INTO course_chapter (id, course_id, name, chapter_order, deleted) VALUES
(9100000000000000501, 9100000000000000005, '第1章 表结构设计与范式权衡', 1, 0),
(9100000000000000502, 9100000000000000005, '第2章 索引原理与执行计划', 2, 0),
(9100000000000000503, 9100000000000000005, '第3章 事务、锁与并发控制', 3, 0),
(9100000000000000504, 9100000000000000005, '第4章 复制、备份与高可用', 4, 0);

-- ⑥ Redis 缓存技术与应用
INSERT INTO course (id, name, course_type, cover_url, first_cate_id, second_cate_id, free, price, status, valid_duration, section_num, score, creater, deleted)
VALUES (9100000000000000006, 'Redis 缓存技术与应用', 2, '/covers/db.svg',
        101,
        201,
        0, 12900, 2, 12, 3, 0, 2, 0);
INSERT INTO course_content (id, course_introduce, use_people, course_detail, creater, deleted)
VALUES (9100000000000000006,
 '本课程讲解 Redis 核心数据结构与高并发缓存实践。内容涵盖五大数据类型与底层编码、持久化机制、缓存穿透/击穿/雪崩三大问题的应对策略、分布式锁实现与集群部署，帮助你为业务系统构建稳定高效的缓存层。',
 '后端开发者与需要设计高并发缓存架构的技术人员。',
 '课程共 3 章：第 1 章核心数据结构与命令实践；第 2 章持久化、过期策略与内存管理；第 3 章缓存三大问题、分布式锁与集群。', 2, 0);
INSERT INTO course_chapter (id, course_id, name, chapter_order, deleted) VALUES
(9100000000000000601, 9100000000000000006, '第1章 核心数据结构与命令实践', 1, 0),
(9100000000000000602, 9100000000000000006, '第2章 持久化与内存管理', 2, 0),
(9100000000000000603, 9100000000000000006, '第3章 缓存三大问题与分布式锁', 3, 0);

-- ⑦ Python 编程与数据处理
INSERT INTO course (id, name, course_type, cover_url, first_cate_id, second_cate_id, free, price, status, valid_duration, section_num, score, creater, deleted)
VALUES (9100000000000000007, 'Python 编程与数据处理', 2, '/covers/python.svg',
        101,
        201,
        1, 0, 2, 12, 4, 0, 2, 0);
INSERT INTO course_content (id, course_introduce, use_people, course_detail, creater, deleted)
VALUES (9100000000000000007,
 '本课程以数据处理为主线讲解 Python 编程。从基础语法起步，覆盖列表/字典等核心数据结构、函数与模块、文件操作，进而学习 NumPy 数组运算与 Pandas 表格处理，最终能完成一份完整的数据清洗与分析报告。',
 '零基础学习者、需要用 Python 处理数据的运营与分析师、希望转入数据方向的开发者。',
 '课程共 4 章：第 1 章 Python 基础语法与环境；第 2 章数据结构与函数；第 3 章文件操作与模块化；第 4 章 NumPy 与 Pandas 数据处理实战。', 2, 0);
INSERT INTO course_chapter (id, course_id, name, chapter_order, deleted) VALUES
(9100000000000000701, 9100000000000000007, '第1章 Python 基础语法与环境搭建', 1, 0),
(9100000000000000702, 9100000000000000007, '第2章 核心数据结构与函数', 2, 0),
(9100000000000000703, 9100000000000000007, '第3章 文件操作与模块化开发', 3, 0),
(9100000000000000704, 9100000000000000007, '第4章 NumPy 与 Pandas 数据处理', 4, 0);

-- ⑧ 大模型应用开发入门
INSERT INTO course (id, name, course_type, cover_url, first_cate_id, second_cate_id, free, price, status, valid_duration, section_num, score, creater, deleted)
VALUES (9100000000000000008, '大模型应用开发入门', 2, '/covers/ai.svg',
        101,
        202,
        0, 25900, 2, 12, 4, 0, 2, 0);
INSERT INTO course_content (id, course_introduce, use_people, course_detail, creater, deleted)
VALUES (9100000000000000008,
 '本课程面向希望进入 AI 应用开发的工程师，讲解大语言模型的核心概念与工程化用法。内容涵盖 Prompt 设计、Chat Completions 接口调用、流式输出、Function Calling 工具调用、多轮会话管理与Token 成本控制，帮助你构建第一个可靠的 AI 应用。',
 '具备任意一门语言基础、希望开发 AI 应用的后端/全栈工程师与产品技术人员。',
 '课程共 4 章：第 1 章大模型核心概念与能力边界；第 2 章 Prompt 设计与接口调用；第 3 章 Function Calling 与多轮会话；第 4 章 工程化实践：流式、审计与成本控制。', 2, 0);
INSERT INTO course_chapter (id, course_id, name, chapter_order, deleted) VALUES
(9100000000000000801, 9100000000000000008, '第1章 大模型核心概念与能力边界', 1, 0),
(9100000000000000802, 9100000000000000008, '第2章 Prompt 设计与接口调用', 2, 0),
(9100000000000000803, 9100000000000000008, '第3章 Function Calling 工具调用', 3, 0),
(9100000000000000804, 9100000000000000008, '第4章 工程化：流式、审计与成本控制', 4, 0);

-- ⑨ RAG 检索增强生成实战
INSERT INTO course (id, name, course_type, cover_url, first_cate_id, second_cate_id, free, price, status, valid_duration, section_num, score, creater, deleted)
VALUES (9100000000000000009, 'RAG 检索增强生成实战', 2, '/covers/rag.svg',
        101,
        202,
        0, 23900, 2, 12, 3, 0, 2, 0);
INSERT INTO course_content (id, course_introduce, use_people, course_detail, creater, deleted)
VALUES (9100000000000000009,
 '本课程讲解检索增强生成（RAG）的完整工程链路。内容涵盖文本切分与向量化、向量数据库选型与索引设计、相似度检索、引用溯源与权限过滤、回答质量评估，帮助你搭建可信赖的企业知识问答系统。',
 '已了解大模型基础调用、希望构建企业知识库与智能问答系统的开发者。',
 '课程共 3 章：第 1 章 RAG 原理与整体链路；第 2 章向量化、切分与检索策略；第 3 章 引用溯源、权限过滤与质量评估。', 2, 0);
INSERT INTO course_chapter (id, course_id, name, chapter_order, deleted) VALUES
(9100000000000000901, 9100000000000000009, '第1章 RAG 原理与整体链路', 1, 0),
(9100000000000000902, 9100000000000000009, '第2章 向量化、切分与检索策略', 2, 0),
(9100000000000000903, 9100000000000000009, '第3章 溯源、权限过滤与质量评估', 3, 0);

-- ⑩ 数据结构与算法基础
INSERT INTO course (id, name, course_type, cover_url, first_cate_id, second_cate_id, free, price, status, valid_duration, section_num, score, creater, deleted)
VALUES (9100000000000000010, '数据结构与算法基础', 2, '/covers/ds.svg',
        101,
        201,
        1, 0, 2, 12, 3, 0, 2, 0);
INSERT INTO course_content (id, course_introduce, use_people, course_detail, creater, deleted)
VALUES (9100000000000000010,
 '本课程系统梳理常用数据结构与经典算法。内容涵盖数组与链表、栈与队列、哈希表、二叉树遍历、递归与分治、排序算法及常见时间复杂度分析，配合典型例题讲解解题思路，帮助你打下扎实的算法基础。',
 '准备技术面试的求职者、计算机专业学生、希望夯实编程内功的开发者。',
 '课程共 3 章：第 1 章线性结构：数组、链表、栈与队列；第 2 章哈希表与树结构；第 3 章递归、排序与复杂度分析。', 2, 0);
INSERT INTO course_chapter (id, course_id, name, chapter_order, deleted) VALUES
(9100000000000001001, 9100000000000000010, '第1章 线性结构：数组、链表、栈与队列', 1, 0),
(9100000000000001002, 9100000000000000010, '第2章 哈希表与二叉树', 2, 0),
(9100000000000001003, 9100000000000000010, '第3章 递归、排序与复杂度分析', 3, 0);
