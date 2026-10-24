

数 据 库 设 计 说 明 书

		实 习 名 称：		毕业实习——课栈智伴——AI 智能在线教育平台
		院 部 名 称：		软件工程学院
		专　　　业：		软件工程
		班　　　级：		23软件工程（6）
		学 生 姓 名：		张桐溪
		学　　　号：		2212001191
		实 习 地 点：		沧州云智大数据产业园有限公司
		实 习 岗 位：		Java 开发
		指 导 教 师：		付红星（企业导师）
		实习起止时间：		2026 年 8 月 31 日 至 2026 年 9 月 25 日

金陵科技学院教务处制

文 档 信 息
<<<TABLE>>>
| 文档编号 | KZ-DBD-2026-001 |
| 项目名称 | 课栈智伴——AI 智能在线教育平台 |
| 编制人 | 张桐溪（Java 开发实习生） |
| 实习岗位 | Java 开发 |
| 实习时间 | 2026-08-31 ～ 2026-09-25 |
| 指导/审核 | 付红星（企业导师） |
| 版本 | v1.1（2026-09-25 定稿，与代码实现一致） |
<<<END TABLE>>>

<<<TABLE>>>
| 项目 | 内容 |
| 数据库管理系统 | MySQL 8.0 |
| 存储引擎 | InnoDB（支持事务、行锁与外键） |
| 字符集/排序规则 | utf8mb4 / utf8mb4_general_ci |
| 数据库名 | course_stack_ai |
| 表命名 | 小写下划线，AI 表以 ai_ 前缀区分（chat_session、ai_eval_case） |
| 字段命名 | 小写下划线，主键统一 id，关联字段使用 引用表_id（如 course_id、user_id） |
| 时间字段 | 统一 DATETIME，默认 CURRENT_TIMESTAMP |
| 金额字段 | 统一 INT，单位分，避免浮点误差 |
| 状态字段 | TINYINT 存枚举值，注释写清取值 |
<<<END TABLE>>>

1. 引言
1.1 编写目的
本文档确认课栈智伴平台的数据库详细设计，供系统设计、开发实现、测试与后续维护人员参考，作为建表、接口开发、数据初始化与问题排查的共同依据。
文档描述数据库的概念结构、逻辑结构、物理结构、数据字典、建表语句、索引与约束、安全设计，并说明 AI 会话、工具参数、评测数据等关键设计。
1.2 文档范围
本文档覆盖课栈智伴的 12 张核心表：用户、课程分类、课程、课程内容、课程章、订单、订单明细、互动提问、互动回答、AI 会话、AI 评测用例、学习记录。
本文档由本人在企业导师付红星指导下整理。chat_session、ai_eval_case 为 AI 模块新增/扩展表，本人参与字段确认与接口联调；其余为项目已有表，本人按基础接口开发与 AI 联调需要梳理字段、索引与关联关系，不代表独立完成全部表结构设计。
2. 数据库设计概述
2.1 设计目标
完整覆盖业务：用户与课程、交易、互动、学习、AI 会话与评测数据的存储。
保证数据一致性：主键、唯一约束、非空约束与逻辑外键保证实体完整性与参照完整性。
支撑查询与统计：针对用户、课程、时间等高频字段建立索引。
保留历史痕迹：订单明细保存课程快照，避免课程改名/调价影响历史订单。
支撑 AI 链路：会话元数据与消息分离，工具参数随消息持久化，评测结果可追溯。
2.2 设计原则
命名规范：库名、表名、字段名统一小写下划线；表名用单数。
主键规范：所有表统一 id，BIGINT；由 MyBatis-Plus 雪花算法生成，不使用自增。
注释规范：所有表与字段添加中文注释，枚举字段写清取值。
关联规范：微服务之间使用逻辑外键，不建物理外键；同一服务内强关联可保留。
索引规范：唯一业务字段建唯一索引（uk_ 前缀），高频查询建普通索引（idx_ 前缀）。
时间规范：业务表统一 create_time/update_time，服务端写入。
金额规范：金额统一 INT，单位分；展示层再转元。
2.3 运行环境与命名规范
数据库采用 MySQL 8.0，InnoDB 引擎，utf8mb4 字符集；库名 course_stack_ai。表名、字段名小写下划线，主键 id，关联字段使用 引用表_id。时间字段 DATETIME，默认 CURRENT_TIMESTAMP；金额 INT 分；状态 TINYINT 枚举。

2.4 数据表清单
<<<TABLE>>>
| 序号 | 表名 | 中文名称 | 主要用途 | 字段数 |
| 1 | user | 用户表 | 存储学员、教师、员工账号与状态 | 10 |
| 2 | course_category | 课程分类表 | 课程一、二、三级分类 | 8 |
| 3 | course | 课程表 | 课程主体、价格、状态与购买有效期 | 20 |
| 4 | course_content | 课程内容表 | 课程介绍、适用人群、详情长文本 | 10 |
| 5 | course_chapter | 课程章表 | 课程章节结构与顺序 | 8 |
| 6 | order | 订单表 | 课程订单主体与状态流转 | 19 |
| 7 | order_detail | 订单明细表 | 订单中的课程快照与金额 | 18 |
| 8 | interaction_question | 互动提问表 | 学员在课程下的提问 | 14 |
| 9 | interaction_reply | 互动回答表 | 问题回答与 AI 自动回复 | 14 |
| 10 | chat_session | AI会话表 | AI 会话元数据与标题 | 8 |
| 11 | ai_eval_case | AI评测用例表 | 意图路由/RAG 批量评测结果 | 11 |
| 12 | learning_lesson | 学习记录表 | 学员课程学习进度 | 10 |
<<<END TABLE>>>

共 12 张表，150 个字段。

3. 概念结构设计
3.1 E-R 图
系统抽象出用户、课程、订单、互动、AI 会话、评测等核心实体。用户创建课程、下单、提问、发起 AI 会话、记录学习；课程包含内容与章；订单包含明细；提问包含回答；评测表独立记录批量评测结果。实体联系如图 3-1 所示。

图 3-1 核心实体 E-R 图
3.2 实体与联系说明
<<<TABLE>>>
| 实体 A | 实体 B | 联系类型 | 连接字段 | 业务含义 |
| user | course | 1:N | course.creater / updater | 用户创建课程 |
| user | order | 1:N | order.user_id | 用户下单 |
| order | order_detail | 1:N | order_detail.order_id | 订单包含明细 |
| course | order_detail | 1:N | order_detail.course_id | 课程被购买，明细保存快照 |
| course_category | course | 1:N | course.first_cate_id 等 | 课程归属分类 |
| course | course_content | 1:1 | course_content.id | 课程与详情一一对应 |
| course | course_chapter | 1:N | course_chapter.course_id | 课程包含章 |
| user | interaction_question | 1:N | interaction_question.user_id | 学员提问 |
| course | interaction_question | 1:N | interaction_question.course_id | 课程下的提问 |
| interaction_question | interaction_reply | 1:N | interaction_reply.question_id | 问题包含回答 |
| user | chat_session | 1:N | chat_session.user_id | 用户发起 AI 会话 |
| user | learning_lesson | 1:N | learning_lesson.user_id | 用户学习记录 |
| course | learning_lesson | 1:N | learning_lesson.course_id | 课程学习记录 |
<<<END TABLE>>>

3.3 数据字典
数据字典按表逐一说明字段名、数据类型、是否允许为空、默认值与业务含义。
3.3.1 user（用户表）
表说明：存储学员、教师、员工账号与状态。本表共 10 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 用户ID，主键 |
| username | VARCHAR(32) | 否 | - | 登录账号 |
| cell_phone | VARCHAR(20) | 是 | NULL | 手机号 |
| password | VARCHAR(64) | 否 | - | 密码哈希值 |
| status | TINYINT | 否 | 1 | 状态：0禁用，1正常 |
| type | TINYINT | 否 | - | 类型：1员工，2学员，3老师 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
| creater | BIGINT | 是 | NULL | 创建人 |
| updater | BIGINT | 是 | NULL | 更新人 |
<<<END TABLE>>>

3.3.2 course_category（课程分类表）
表说明：课程一、二、三级分类。本表共 8 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 分类ID，主键 |
| name | VARCHAR(50) | 否 | - | 分类名称 |
| parent_id | BIGINT | 否 | 0 | 父分类ID，0为一级 |
| level | TINYINT | 否 | - | 层级：1/2/3 |
| sort | INT | 否 | 0 | 排序值 |
| status | TINYINT | 否 | 1 | 状态：0禁用，1启用 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
<<<END TABLE>>>

3.3.3 course（课程表）
表说明：课程主体、价格、状态与购买有效期。本表共 20 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 课程ID，主键 |
| name | VARCHAR(100) | 否 | - | 课程名称 |
| course_type | TINYINT | 否 | - | 类型：1直播，2录播 |
| cover_url | VARCHAR(255) | 是 | NULL | 封面链接 |
| first_cate_id | BIGINT | 是 | NULL | 一级分类ID |
| second_cate_id | BIGINT | 是 | NULL | 二级分类ID |
| third_cate_id | BIGINT | 是 | NULL | 三级分类ID |
| free | TINYINT | 否 | 0 | 0付费，1免费 |
| price | INT | 否 | 0 | 课程价格，单位分 |
| status | TINYINT | 否 | - | 1待上架，2已上架，3下架，4已完结 |
| purchase_start_time | DATETIME | 是 | NULL | 购买有效期开始 |
| purchase_end_time | DATETIME | 是 | NULL | 购买有效期结束 |
| valid_duration | INT | 是 | NULL | 有效期，单位月 |
| section_num | INT | 否 | 0 | 课程总节数 |
| score | INT | 否 | 0 | 评分，45代表4.5星 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
| creater | BIGINT | 是 | NULL | 创建人 |
| updater | BIGINT | 是 | NULL | 更新人 |
| deleted | TINYINT | 否 | 0 | 逻辑删除：0正常，1删除 |
<<<END TABLE>>>

3.3.4 course_content（课程内容表）
表说明：课程介绍、适用人群、详情长文本。本表共 10 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 课程内容ID，与course.id一致 |
| course_introduce | TEXT | 是 | NULL | 课程介绍 |
| use_people | VARCHAR(255) | 是 | NULL | 适用人群 |
| course_detail | LONGTEXT | 是 | NULL | 课程详情 |
| dep_id | BIGINT | 是 | NULL | 部门ID |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
| creater | BIGINT | 是 | NULL | 创建人 |
| updater | BIGINT | 是 | NULL | 更新人 |
| deleted | TINYINT | 否 | 0 | 逻辑删除 |
<<<END TABLE>>>

3.3.5 course_chapter（课程章表）
表说明：课程章节结构与顺序。本表共 8 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 章ID，主键 |
| course_id | BIGINT | 否 | - | 课程ID |
| name | VARCHAR(100) | 否 | - | 章名称 |
| chapter_order | INT | 否 | - | 章序号 |
| description | VARCHAR(255) | 是 | NULL | 章描述 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
| deleted | TINYINT | 否 | 0 | 逻辑删除 |
<<<END TABLE>>>

3.3.6 order（订单表）
表说明：课程订单主体与状态流转。本表共 19 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 订单ID，主键 |
| pay_order_no | BIGINT | 是 | NULL | 支付交易流水号 |
| user_id | BIGINT | 否 | - | 下单用户ID |
| status | TINYINT | 否 | - | 1待支付，2已支付，3已关闭，4已完成，5已报名，6已申请退款 |
| message | VARCHAR(255) | 是 | NULL | 状态备注 |
| total_amount | INT | 否 | - | 订单总金额，单位分 |
| real_amount | INT | 否 | - | 实付金额，单位分 |
| discount_amount | INT | 否 | 0 | 优惠金额，单位分 |
| pay_channel | VARCHAR(20) | 是 | NULL | 支付渠道 |
| coupon_ids | JSON | 是 | NULL | 优惠券ID集合 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| pay_time | DATETIME | 是 | NULL | 支付时间 |
| close_time | DATETIME | 是 | NULL | 关闭时间 |
| finish_time | DATETIME | 是 | NULL | 完成时间 |
| refund_time | DATETIME | 是 | NULL | 退款时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
| creater | BIGINT | 是 | NULL | 创建人 |
| updater | BIGINT | 是 | NULL | 更新人 |
| deleted | TINYINT | 否 | 0 | 逻辑删除 |
<<<END TABLE>>>

3.3.7 order_detail（订单明细表）
表说明：订单中的课程快照与金额。本表共 18 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 明细ID，主键 |
| order_id | BIGINT | 否 | - | 订单ID |
| user_id | BIGINT | 否 | - | 用户ID |
| course_id | BIGINT | 否 | - | 课程ID |
| price | INT | 否 | - | 课程价格，单位分 |
| name | VARCHAR(100) | 否 | - | 课程名称快照 |
| cover_url | VARCHAR(255) | 是 | NULL | 封面快照 |
| valid_duration | INT | 是 | NULL | 有效期，单位月 |
| course_expire_time | DATETIME | 是 | NULL | 课程过期时间 |
| discount_amount | INT | 否 | 0 | 折扣金额，单位分 |
| real_pay_amount | INT | 否 | - | 实付金额，单位分 |
| status | TINYINT | 否 | - | 1待支付，2已支付，3已关闭，4已完成，5已报名 |
| refund_status | TINYINT | 是 | NULL | 退款状态 |
| pay_channel | VARCHAR(20) | 是 | NULL | 支付渠道 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
| creater | BIGINT | 是 | NULL | 创建人 |
| updater | BIGINT | 是 | NULL | 更新人 |
<<<END TABLE>>>

3.3.8 interaction_question（互动提问表）
表说明：学员在课程下的提问。本表共 14 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 问题ID，主键 |
| title | VARCHAR(255) | 否 | - | 问题标题 |
| description | TEXT | 是 | NULL | 问题描述 |
| course_id | BIGINT | 否 | - | 课程ID |
| chapter_id | BIGINT | 是 | NULL | 章ID |
| section_id | BIGINT | 是 | NULL | 节ID |
| user_id | BIGINT | 否 | - | 提问学员ID |
| latest_answer_id | BIGINT | 是 | NULL | 最新回答ID |
| answer_times | INT | 否 | 0 | 回答数量 |
| anonymity | TINYINT | 否 | 0 | 是否匿名 |
| hidden | TINYINT | 否 | 0 | 是否隐藏 |
| status | TINYINT | 否 | 0 | 0未查看，1已查看 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 提问时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
<<<END TABLE>>>

3.3.9 interaction_reply（互动回答表）
表说明：问题回答与 AI 自动回复。本表共 14 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 回答ID，主键 |
| question_id | BIGINT | 否 | - | 问题ID |
| answer_id | BIGINT | 是 | NULL | 上级回答ID |
| user_id | BIGINT | 否 | - | 回答者ID |
| content | TEXT | 否 | - | 回答内容 |
| target_user_id | BIGINT | 是 | NULL | 目标用户ID |
| target_reply_id | BIGINT | 是 | NULL | 目标回答ID |
| reply_times | INT | 否 | 0 | 评论数 |
| liked_times | INT | 否 | 0 | 点赞数 |
| hidden | TINYINT | 否 | 0 | 是否隐藏 |
| anonymity | TINYINT | 否 | 0 | 是否匿名 |
| is_student | TINYINT | 否 | 1 | 是否学员：0AI/系统，1学员 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
<<<END TABLE>>>

3.3.10 chat_session（AI会话表）
表说明：AI 会话元数据与标题。本表共 8 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 主键 |
| session_id | VARCHAR(64) | 否 | - | 会话ID（UUID） |
| user_id | BIGINT | 否 | - | 归属用户ID |
| title | VARCHAR(100) | 是 | NULL | 会话标题 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
| creater | BIGINT | 是 | NULL | 创建人 |
| updater | BIGINT | 是 | NULL | 更新人 |
<<<END TABLE>>>

3.3.11 ai_eval_case（AI评测用例表）
表说明：意图路由/RAG 批量评测结果。本表共 11 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 用例ID，主键 |
| question | VARCHAR(500) | 否 | - | 评测问题 |
| category | VARCHAR(50) | 否 | - | 类别：recommend/consult/buy/knowledge/greeting/out_of_scope |
| expected_intent | VARCHAR(50) | 是 | NULL | 期望意图 |
| expected_doc_id | VARCHAR(64) | 是 | NULL | 期望召回文档/课程ID |
| actual_intent | VARCHAR(50) | 是 | NULL | 实际意图 |
| actual_doc_ids | VARCHAR(500) | 是 | NULL | 实际召回ID集合 |
| latency_ms | INT | 是 | NULL | 端到端耗时，毫秒 |
| token_usage | INT | 是 | NULL | token 用量 |
| passed | TINYINT | 否 | 0 | 是否通过：0否，1是 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 执行时间 |
<<<END TABLE>>>

3.3.12 learning_lesson（学习记录表）
表说明：学员课程学习进度。本表共 10 个字段。
<<<TABLE>>>
| 字段名 | 数据类型 | 允许空 | 默认值 | 说明 |
| id | BIGINT | 否 | - | 学习记录ID，主键 |
| user_id | BIGINT | 否 | - | 用户ID |
| course_id | BIGINT | 否 | - | 课程ID |
| chapter_id | BIGINT | 是 | NULL | 章ID |
| section_id | BIGINT | 是 | NULL | 节ID |
| status | TINYINT | 否 | 0 | 0未开始，1学习中，2已完成 |
| progress | INT | 否 | 0 | 进度百分比 |
| last_study_time | DATETIME | 是 | NULL | 最近学习时间 |
| create_time | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间 |
<<<END TABLE>>>

4. 逻辑结构设计
4.1 关系模式
1. user(id, username, cell_phone, password, status, type, create_time, update_time, creater, updater)
2. course_category(id, name, parent_id, level, sort, status, create_time, update_time)
3. course(id, name, course_type, cover_url, first_cate_id, second_cate_id, third_cate_id, free, price, status, purchase_start_time, purchase_end_time, valid_duration, section_num, score, create_time, update_time, creater, updater, deleted)
4. course_content(id, course_introduce, use_people, course_detail, dep_id, create_time, update_time, creater, updater, deleted)
5. course_chapter(id, course_id, name, chapter_order, description, create_time, update_time, deleted)
6. order(id, pay_order_no, user_id, status, message, total_amount, real_amount, discount_amount, pay_channel, coupon_ids, create_time, pay_time, close_time, finish_time, refund_time, update_time, creater, updater, deleted)
7. order_detail(id, order_id, user_id, course_id, price, name, cover_url, valid_duration, course_expire_time, discount_amount, real_pay_amount, status, refund_status, pay_channel, create_time, update_time, creater, updater)
8. interaction_question(id, title, description, course_id, chapter_id, section_id, user_id, latest_answer_id, answer_times, anonymity, hidden, status, create_time, update_time)
9. interaction_reply(id, question_id, answer_id, user_id, content, target_user_id, target_reply_id, reply_times, liked_times, hidden, anonymity, is_student, create_time, update_time)
10. chat_session(id, session_id, user_id, title, create_time, update_time, creater, updater)
11. ai_eval_case(id, question, category, expected_intent, expected_doc_id, actual_intent, actual_doc_ids, latency_ms, token_usage, passed, create_time)
12. learning_lesson(id, user_id, course_id, chapter_id, section_id, status, progress, last_study_time, create_time, update_time)
4.2 表间关系说明
核心实体关系如图 3-1 所示；表间关联以逻辑外键为主，订单明细保存课程快照，AI 会话消息存 Redis，评测表独立。
<<<TABLE>>>
| 表 | 关联表 | 关联字段 | 基数 | 说明 |
| user | course | course.creater / updater | 1:N | 用户创建课程 |
| user | order | order.user_id | 1:N | 用户下单 |
| order | order_detail | order_detail.order_id | 1:N | 订单包含明细 |
| course | order_detail | order_detail.course_id | 1:N | 课程被购买，明细保存快照 |
| course_category | course | course.first_cate_id 等 | 1:N | 课程归属分类 |
| course | course_content | course_content.id | 1:1 | 课程与详情一一对应 |
| course | course_chapter | course_chapter.course_id | 1:N | 课程包含章 |
| user | interaction_question | interaction_question.user_id | 1:N | 学员提问 |
| course | interaction_question | interaction_question.course_id | 1:N | 课程下的提问 |
| interaction_question | interaction_reply | interaction_reply.question_id | 1:N | 问题包含回答 |
| user | chat_session | chat_session.user_id | 1:N | 用户发起 AI 会话 |
| user | learning_lesson | learning_lesson.user_id | 1:N | 用户学习记录 |
| course | learning_lesson | learning_lesson.course_id | 1:N | 课程学习记录 |
<<<END TABLE>>>

4.3 范式说明
各表满足第三范式：非主键字段完全依赖主键，消除部分依赖与传递依赖。订单明细保存课程名称、封面、价格快照，属于有意的反范式设计，用于保证历史订单不受课程变更影响。
coupon_ids、actual_doc_ids 等字段使用 JSON/逗号串存储，属于业务上的多值字段；读取时在应用层解析，写入前做格式校验。

5. 物理结构设计
5.1 存储引擎与字符集
存储引擎 InnoDB，字符集 utf8mb4，排序规则 utf8mb4_general_ci。InnoDB 支持事务、行级锁与外键，适合订单、会话等需要一致性的场景。
5.2 建表语句
以下建表语句与数据字典一致，可直接在 MySQL 8.0 执行；主键由应用层雪花算法生成，因此不使用 AUTO_INCREMENT。
5.2.1 user（用户表）
CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT NOT NULL COMMENT '用户ID，主键',
  `username` VARCHAR(32) NOT NULL COMMENT '登录账号',
  `cell_phone` VARCHAR(20) NULL DEFAULT NULL COMMENT '手机号',
  `password` VARCHAR(64) NOT NULL COMMENT '密码哈希值',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用，1正常',
  `type` TINYINT NOT NULL COMMENT '类型：1员工，2学员，3老师',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_cell_phone (cell_phone),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户表';

5.2.2 course_category（课程分类表）
CREATE TABLE IF NOT EXISTS `course_category` (
  `id` BIGINT NOT NULL COMMENT '分类ID，主键',
  `name` VARCHAR(50) NOT NULL COMMENT '分类名称',
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父分类ID，0为一级',
  `level` TINYINT NOT NULL COMMENT '层级：1/2/3',
  `sort` INT NOT NULL DEFAULT 0 COMMENT '排序值',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用，1启用',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_parent (parent_id),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程分类表';

5.2.3 course（课程表）
CREATE TABLE IF NOT EXISTS `course` (
  `id` BIGINT NOT NULL COMMENT '课程ID，主键',
  `name` VARCHAR(100) NOT NULL COMMENT '课程名称',
  `course_type` TINYINT NOT NULL COMMENT '类型：1直播，2录播',
  `cover_url` VARCHAR(255) NULL DEFAULT NULL COMMENT '封面链接',
  `first_cate_id` BIGINT NULL DEFAULT NULL COMMENT '一级分类ID',
  `second_cate_id` BIGINT NULL DEFAULT NULL COMMENT '二级分类ID',
  `third_cate_id` BIGINT NULL DEFAULT NULL COMMENT '三级分类ID',
  `free` TINYINT NOT NULL DEFAULT 0 COMMENT '0付费，1免费',
  `price` INT NOT NULL DEFAULT 0 COMMENT '课程价格，单位分',
  `status` TINYINT NOT NULL COMMENT '1待上架，2已上架，3下架，4已完结',
  `purchase_start_time` DATETIME NULL DEFAULT NULL COMMENT '购买有效期开始',
  `purchase_end_time` DATETIME NULL DEFAULT NULL COMMENT '购买有效期结束',
  `valid_duration` INT NULL DEFAULT NULL COMMENT '有效期，单位月',
  `section_num` INT NOT NULL DEFAULT 0 COMMENT '课程总节数',
  `score` INT NOT NULL DEFAULT 0 COMMENT '评分，45代表4.5星',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
  KEY idx_cate (first_cate_id, second_cate_id, third_cate_id),
  KEY idx_status (status),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程表';

5.2.4 course_content（课程内容表）
CREATE TABLE IF NOT EXISTS `course_content` (
  `id` BIGINT NOT NULL COMMENT '课程内容ID，与course.id一致',
  `course_introduce` TEXT NULL DEFAULT NULL COMMENT '课程介绍',
  `use_people` VARCHAR(255) NULL DEFAULT NULL COMMENT '适用人群',
  `course_detail` LONGTEXT NULL DEFAULT NULL COMMENT '课程详情',
  `dep_id` BIGINT NULL DEFAULT NULL COMMENT '部门ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程内容表';

5.2.5 course_chapter（课程章表）
CREATE TABLE IF NOT EXISTS `course_chapter` (
  `id` BIGINT NOT NULL COMMENT '章ID，主键',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `name` VARCHAR(100) NOT NULL COMMENT '章名称',
  `chapter_order` INT NOT NULL COMMENT '章序号',
  `description` VARCHAR(255) NULL DEFAULT NULL COMMENT '章描述',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_course (course_id, chapter_order),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程章表';

5.2.6 order（订单表）
CREATE TABLE IF NOT EXISTS `order` (
  `id` BIGINT NOT NULL COMMENT '订单ID，主键',
  `pay_order_no` BIGINT NULL DEFAULT NULL COMMENT '支付交易流水号',
  `user_id` BIGINT NOT NULL COMMENT '下单用户ID',
  `status` TINYINT NOT NULL COMMENT '1待支付，2已支付，3已关闭，4已完成，5已报名，6已申请退款',
  `message` VARCHAR(255) NULL DEFAULT NULL COMMENT '状态备注',
  `total_amount` INT NOT NULL COMMENT '订单总金额，单位分',
  `real_amount` INT NOT NULL COMMENT '实付金额，单位分',
  `discount_amount` INT NOT NULL DEFAULT 0 COMMENT '优惠金额，单位分',
  `pay_channel` VARCHAR(20) NULL DEFAULT NULL COMMENT '支付渠道',
  `coupon_ids` JSON NULL DEFAULT NULL COMMENT '优惠券ID集合',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `pay_time` DATETIME NULL DEFAULT NULL COMMENT '支付时间',
  `close_time` DATETIME NULL DEFAULT NULL COMMENT '关闭时间',
  `finish_time` DATETIME NULL DEFAULT NULL COMMENT '完成时间',
  `refund_time` DATETIME NULL DEFAULT NULL COMMENT '退款时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_user_time (user_id, create_time),
  KEY idx_status (status),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单表';

5.2.7 order_detail（订单明细表）
CREATE TABLE IF NOT EXISTS `order_detail` (
  `id` BIGINT NOT NULL COMMENT '明细ID，主键',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `price` INT NOT NULL COMMENT '课程价格，单位分',
  `name` VARCHAR(100) NOT NULL COMMENT '课程名称快照',
  `cover_url` VARCHAR(255) NULL DEFAULT NULL COMMENT '封面快照',
  `valid_duration` INT NULL DEFAULT NULL COMMENT '有效期，单位月',
  `course_expire_time` DATETIME NULL DEFAULT NULL COMMENT '课程过期时间',
  `discount_amount` INT NOT NULL DEFAULT 0 COMMENT '折扣金额，单位分',
  `real_pay_amount` INT NOT NULL COMMENT '实付金额，单位分',
  `status` TINYINT NOT NULL COMMENT '1待支付，2已支付，3已关闭，4已完成，5已报名',
  `refund_status` TINYINT NULL DEFAULT NULL COMMENT '退款状态',
  `pay_channel` VARCHAR(20) NULL DEFAULT NULL COMMENT '支付渠道',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  KEY idx_order (order_id),
  KEY idx_course (course_id),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单明细表';

5.2.8 interaction_question（互动提问表）
CREATE TABLE IF NOT EXISTS `interaction_question` (
  `id` BIGINT NOT NULL COMMENT '问题ID，主键',
  `title` VARCHAR(255) NOT NULL COMMENT '问题标题',
  `description` TEXT NULL DEFAULT NULL COMMENT '问题描述',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `chapter_id` BIGINT NULL DEFAULT NULL COMMENT '章ID',
  `section_id` BIGINT NULL DEFAULT NULL COMMENT '节ID',
  `user_id` BIGINT NOT NULL COMMENT '提问学员ID',
  `latest_answer_id` BIGINT NULL DEFAULT NULL COMMENT '最新回答ID',
  `answer_times` INT NOT NULL DEFAULT 0 COMMENT '回答数量',
  `anonymity` TINYINT NOT NULL DEFAULT 0 COMMENT '是否匿名',
  `hidden` TINYINT NOT NULL DEFAULT 0 COMMENT '是否隐藏',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未查看，1已查看',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_course_time (course_id, create_time),
  KEY idx_user (user_id),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='互动提问表';

5.2.9 interaction_reply（互动回答表）
CREATE TABLE IF NOT EXISTS `interaction_reply` (
  `id` BIGINT NOT NULL COMMENT '回答ID，主键',
  `question_id` BIGINT NOT NULL COMMENT '问题ID',
  `answer_id` BIGINT NULL DEFAULT NULL COMMENT '上级回答ID',
  `user_id` BIGINT NOT NULL COMMENT '回答者ID',
  `content` TEXT NOT NULL COMMENT '回答内容',
  `target_user_id` BIGINT NULL DEFAULT NULL COMMENT '目标用户ID',
  `target_reply_id` BIGINT NULL DEFAULT NULL COMMENT '目标回答ID',
  `reply_times` INT NOT NULL DEFAULT 0 COMMENT '评论数',
  `liked_times` INT NOT NULL DEFAULT 0 COMMENT '点赞数',
  `hidden` TINYINT NOT NULL DEFAULT 0 COMMENT '是否隐藏',
  `anonymity` TINYINT NOT NULL DEFAULT 0 COMMENT '是否匿名',
  `is_student` TINYINT NOT NULL DEFAULT 1 COMMENT '是否学员：0AI/系统，1学员',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_question_time (question_id, create_time),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='互动回答表';

5.2.10 chat_session（AI会话表）
CREATE TABLE IF NOT EXISTS `chat_session` (
  `id` BIGINT NOT NULL COMMENT '主键',
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID（UUID）',
  `user_id` BIGINT NOT NULL COMMENT '归属用户ID',
  `title` VARCHAR(100) NULL DEFAULT NULL COMMENT '会话标题',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` BIGINT NULL DEFAULT NULL COMMENT '创建人',
  `updater` BIGINT NULL DEFAULT NULL COMMENT '更新人',
  UNIQUE KEY uk_session_id (session_id),
  KEY idx_user_time (user_id, update_time),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI会话表';

5.2.11 ai_eval_case（AI评测用例表）
CREATE TABLE IF NOT EXISTS `ai_eval_case` (
  `id` BIGINT NOT NULL COMMENT '用例ID，主键',
  `question` VARCHAR(500) NOT NULL COMMENT '评测问题',
  `category` VARCHAR(50) NOT NULL COMMENT '类别：recommend/consult/buy/knowledge/greeting/out_of_scope',
  `expected_intent` VARCHAR(50) NULL DEFAULT NULL COMMENT '期望意图',
  `expected_doc_id` VARCHAR(64) NULL DEFAULT NULL COMMENT '期望召回文档/课程ID',
  `actual_intent` VARCHAR(50) NULL DEFAULT NULL COMMENT '实际意图',
  `actual_doc_ids` VARCHAR(500) NULL DEFAULT NULL COMMENT '实际召回ID集合',
  `latency_ms` INT NULL DEFAULT NULL COMMENT '端到端耗时，毫秒',
  `token_usage` INT NULL DEFAULT NULL COMMENT 'token 用量',
  `passed` TINYINT NOT NULL DEFAULT 0 COMMENT '是否通过：0否，1是',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
  KEY idx_category (category),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI评测用例表';

5.2.12 learning_lesson（学习记录表）
CREATE TABLE IF NOT EXISTS `learning_lesson` (
  `id` BIGINT NOT NULL COMMENT '学习记录ID，主键',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `chapter_id` BIGINT NULL DEFAULT NULL COMMENT '章ID',
  `section_id` BIGINT NULL DEFAULT NULL COMMENT '节ID',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未开始，1学习中，2已完成',
  `progress` INT NOT NULL DEFAULT 0 COMMENT '进度百分比',
  `last_study_time` DATETIME NULL DEFAULT NULL COMMENT '最近学习时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_user_course_section (user_id, course_id, section_id),
  KEY idx_course (course_id),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学习记录表';

5.3 索引设计
<<<TABLE>>>
| 表名 | 索引名 | 类型 | 索引字段 | 设计目的 |
| user | uk_username | 唯一 | username | 登录账号唯一 |
| user | uk_cell_phone | 唯一 | cell_phone | 手机号唯一 |
| course_category | idx_parent | 普通 | parent_id | 按父分类查子分类 |
| course | idx_cate | 普通 | first_cate_id, second_cate_id, third_cate_id | 分类筛选 |
| course | idx_status | 普通 | status | 上架课程筛选 |
| order | idx_user_time | 普通 | user_id, create_time | 用户订单列表 |
| order | idx_status | 普通 | status | 订单状态筛选 |
| order_detail | idx_order | 普通 | order_id | 订单明细查询 |
| order_detail | idx_course | 普通 | course_id | 课程销量统计 |
| interaction_question | idx_course_time | 普通 | course_id, create_time | 课程问答列表 |
| interaction_question | idx_user | 普通 | user_id | 我的提问 |
| interaction_reply | idx_question_time | 普通 | question_id, create_time | 问题回答列表 |
| chat_session | uk_session_id | 唯一 | session_id | 会话ID唯一 |
| chat_session | idx_user_time | 普通 | user_id, update_time | 历史会话排序 |
| ai_eval_case | idx_category | 普通 | category | 按类别统计评测 |
| learning_lesson | uk_user_course_section | 唯一 | user_id, course_id, section_id | 学习记录唯一 |
| learning_lesson | idx_course | 普通 | course_id | 课程学习统计 |
| course_chapter | idx_course | 普通 | course_id, chapter_order | 章顺序 |
<<<END TABLE>>>

5.4 完整性约束设计
主键约束：所有表 id 为主键，应用层雪花算法生成，保证全局唯一。
唯一约束：user.username、user.cell_phone、chat_session.session_id、learning_lesson(user_id, course_id, section_id)。
非空约束：业务必填字段 NOT NULL，如 name、price、user_id、status。
逻辑外键：微服务之间不建物理外键，由应用层保证；同一服务内可保留物理外键。
默认值：状态、金额、时间字段设置默认值，减少写入遗漏。
检查约束：金额、进度等字段在应用层校验非负；JSON 字段入库前校验格式。
逻辑删除：course、order 等表使用 deleted 字段，查询默认过滤 deleted=0。

6. 数据库安全设计
6.1 账号与权限
应用使用独立数据库账号，禁止使用 root；按服务分配最小权限，AI 服务只读课程/订单数据，写 chat_session、ai_eval_case；业务服务只写自己的库表。
6.2 密码存储
user.password 只存 BCrypt 哈希，不存明文；登录校验使用哈希比对。API Key、数据库口令通过环境变量或 Nacos 加密配置注入，不写入源码与文档。
6.3 数据备份
每日 mysqldump 全量备份，保留 7 天；开启 binlog 支持按时间点恢复；备份文件加密存储，定期做恢复演练。

7. 关键设计说明
7.1 会话记忆与消息一致性
chat_session 只存会话元数据，消息正文存 Redis 列表 CHAT:{conversationId}，conversationId = userId + "_" + sessionId。停止生成时把已生成内容补写 Redis；删除会话时同时删 DB 与 Redis，避免残留。
7.2 工具参数快照
课程查询/预下单的工具结果放 ToolResultHolder，流结束时通过 1003 事件返回前端；参数随 assistant 消息持久化，历史回放仍能渲染课程/订单卡片。
7.3 多智能体记录去重
路由 Agent 的调用会产生 user/assistant 两条临时消息，RecordOptimizationAdvisor 在路由完成后清理这两条，避免历史记录重复。
7.4 RAG 索引与评测
课程/知识文档切片后写入 Elasticsearch 向量库，MySQL 保留原文与元数据；ai_eval_case 记录 30 条评测问题的意图、召回、耗时、token 与是否通过，支撑 Prompt/RAG 调参。

8. 个人参与说明
8.1 个人参与范围
本人岗位为 Java 开发实习生，在付红星指导下参与：第一周开发环境搭建、代码结构/服务关系梳理、Git 使用、启动项目并调试简单接口；第二周负责模块的查询/修改/信息维护接口、OpenFeign 调用、接口测试与修改；第三周 AI 功能与接口联调，包括大模型接口调用、Prompt 配置、结果处理、前端参数/返回校验与问题修复；第四周配合测试、修复接口/业务问题、补充接口文档与测试记录、了解部署流程、整理总结与交接。
本文档中的数据库整体设计用于说明项目上下文；chat_session、ai_eval_case 参与字段确认与联调，其余业务表按接口开发需要梳理，不代表本人独立完成全部表结构设计。
8.2 文档与代码一致性
表结构最终以项目实际数据库与建表脚本为准；如文档与代码不一致，以代码为准，并在版本记录中注明变更。