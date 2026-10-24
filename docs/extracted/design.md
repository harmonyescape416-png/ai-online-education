

系 统 设 计 说 明 书

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
| 文档编号 | KZ-SDD-2026-002 |
| 项目名称 | 课栈智伴——AI 智能在线教育平台 |
| 编制人 | 张桐溪（Java 开发实习生） |
| 实习岗位 | Java 开发 |
| 实习时间 | 2026-08-31 ～ 2026-09-25 |
| 指导/审核 | 付红星（企业导师） |
| 版本 | v1.1（2026-09-25 定稿，与代码实现一致） |
<<<END TABLE>>>

1. 引言
1.1 编写目的
本文档确认课栈智伴平台的系统详细设计，供项目负责人、开发人员、测试人员与维护人员参考，作为编码实现、系统测试与后续维护的依据。
文档覆盖系统总体设计、模块设计、核心业务流程、分层详细设计、安全与权限、异常与日志、缓存与界面设计，并与《用户需求说明书》《数据库设计说明书》配套使用。
1.2 背景
（1）项目背景：在线职业教育学员在选课、咨询、购买与学习过程中，面临课程信息分散、人工客服响应慢、购买决策与学习计划割裂等问题。课栈智伴在既有课程交易与学习服务之上增加 AI 助手，提供课程咨询、推荐、查询、预下单、知识库问答、学习问答自动回复与语音交互。
（2）技术可行性：项目采用 Spring Cloud Alibaba 微服务架构，AI 服务基于 Spring AI 接入 DashScope/OpenAI 兼容模型；流式对话使用 SSE，知识库使用 Elasticsearch 向量库；团队已有网关、鉴权、课程、交易、学习等基础服务，可在其上增量开发。
（3）经济可行性：开发使用开源框架与已有开发环境，不产生额外授权费用；AI 服务按 token 计费，调试期成本可控；上线后可减少人工客服重复咨询，提升选课转化与学习体验。
1.3 术语与缩略语
<<<TABLE>>>
| 术语/缩略语 | 含义 |
| SSE | Server-Sent Events，服务端推送，用于流式对话 |
| RAG | Retrieval-Augmented Generation，检索增强生成 |
| Agent | 智能体，按职责封装的 AI 处理单元 |
| Tool | 工具调用，模型按意图调用后端接口 |
| Advisor | Spring AI 的请求/响应增强组件 |
| ChatClient | Spring AI 的对话客户端 |
| Nacos | 注册中心与配置中心 |
| OpenFeign | 微服务之间的声明式 HTTP 调用 |
| JWT | JSON Web Token，登录令牌 |
<<<END TABLE>>>

2. 系统总体设计
2.1 设计目标
功能完整：覆盖登录、课程、AI 对话、工具调用、RAG、多智能体、自动回复与语音。
分层清晰：Controller → Service → Mapper 单向依赖，AI 能力通过 Advisor/Tools 横向增强。
流式体验：SSE 逐字输出，首字延迟优先；支持停止生成与部分内容保存。
数据隔离：会话、订单、工具调用均按 userId 隔离。
能力可替换：模型、向量库、会话记忆实现通过配置切换。
可追溯：requestId 贯穿网关、AI 服务与工具调用日志。
2.2 设计原则
微服务复用：不重写课程/交易/学习，AI 作为独立 aigc 服务接入。
分层解耦：控制层只做参数装配与结果封装，业务层负责规则与事务，数据层负责 SQL。
配置外置：提示词、模型、ES/Redis 配置放 Nacos，支持热更新。
流式优先：对话链路全程流式，避免一次性聚合。
异常兜底：模型超时、ES 不可用、工具失败都有提示或降级。
前后端职责分离：前端只负责渲染与交互，权限与校验在后端。
渐进增强：先单 Agent，再多智能体；先 ES，再 Redis Stack。
2.3 运行环境
<<<TABLE>>>
| 项目 | 配置 |
| 操作系统 | Windows 11 / macOS |
| JDK | Java 17 |
| 构建 | Maven 3.9+ |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 6/7 |
| 搜索/向量 | Elasticsearch 8 + Kibana |
| 注册/配置 | Nacos 2.x |
| 前端 | Vue 3 + Vite + Element Plus |
| 浏览器 | Chrome/Edge 最新两个大版本 |
<<<END TABLE>>>

2.4 技术选型
<<<TABLE>>>
| 层次 | 技术选型 | 版本 | 用途说明 |
| 基础框架 | Spring Boot | 3.3.5 | 微服务基础 |
| 微服务 | Spring Cloud Alibaba | 2023.0.3.2 | Nacos、Gateway、OpenFeign |
| AI 框架 | Spring AI | 1.0.0-M6 | ChatClient、Advisor、Tools、Memory |
| AI 接入 | Spring AI Alibaba | 1.0.0-M6.1 | DashScope 千问 |
| 数据访问 | MyBatis-Plus | 3.5.9 | ORM 与分页 |
| 前端 | Vue 3 + Vite | 3.x / 5.x | AI 工作台 |
| 流式 | SSE | — | 流式对话事件 |
| 向量库 | Elasticsearch | 8.x | RAG 向量检索 |
<<<END TABLE>>>

2.5 总体架构设计
系统采用微服务 + 分层架构：用户层通过网关进入，业务层由 auth/user/course/trade/learning 等基础服务与 aigc 服务组成；aigc 内部再分 ChatClient、Advisor、Tools、Memory、VectorStore 与 Agent；数据与外部能力包括 MySQL、Redis、Elasticsearch、Nacos 与大模型 API。

图 2-1 系统总体架构图

3. 系统模块设计
3.1 功能模块结构
系统按角色与能力划分为学员端、AI 服务、教师/运营端、管理端四个一级模块，AI 服务再分为会话/SSE、记忆/历史、工具调用、RAG、多智能体与语音。

图 3-1 功能模块结构图
3.2 模块清单与职责
<<<TABLE>>>
| 模块 | 包含功能 | 主要实现类 | 对应页面 |
| 学员端 | 登录、课程、订单、历史会话 | AuthController、CourseController、OrderController | 登录页、课程详情、订单确认 |
| AI 服务 | 会话、SSE、记忆、工具、RAG、多智能体、语音 | ChatController、SessionController、ChatService、AgentService | AI 工作台 |
| 教师/运营端 | 课程管理、资料管理、问答管理 | CourseAdminController、QuestionAdminController | 课程管理、问答管理 |
| 管理端 | 用户、知识库、评测看板 | UserAdminController、EvalController | 用户管理、评测看板 |
| 公共支撑 | 鉴权、异常、日志、配置 | GlobalExceptionHandler、AuthFilter、SystemPromptConfig | — |
<<<END TABLE>>>

3.3 模块间调用关系
<<<TABLE>>>
| 调用方 | 被调方 | 协议 | 用途 |
| 前端 | gateway | HTTPS/SSE | 统一入口、鉴权、路由 |
| gateway | aigc | HTTP | /chat、/session、/audio |
| aigc | course | OpenFeign | 课程查询工具 |
| aigc | trade | OpenFeign | 预下单工具 |
| aigc | learning | OpenFeign | 问答上下文/自动回复 |
| learning | aigc | OpenFeign | 文本自动回复 |
| aigc | Elasticsearch | HTTP | RAG 检索与写入 |
| aigc | Redis | Redis 协议 | 会话记忆、停止标识 |
| aigc | Nacos | HTTP | 配置与提示词 |
| aigc | 大模型 API | HTTPS | 对话/向量/语音 |
<<<END TABLE>>>

4. 核心业务流程设计
4.1 总体业务流程
学员登录后进入 AI 工作台，输入问题；路由 Agent 判断意图，分发到推荐/咨询/购买/知识讲解；需要时调用课程工具、预下单工具或 RAG；结果通过 SSE 流式返回，最后保存会话记忆并异步更新标题。

图 4-1 学员 AI 对话主流程
4.2 AI 对话流程
前端 POST /chat，网关鉴权后转发 aigc；aigc 生成 conversationId，加载提示词与记忆，调用模型流式输出；文本事件 1001、参数事件 1003、结束事件 1002。

图 4-2 AI 对话与工具调用时序
4.3 课程查询与预下单流程
模型识别课程查询/购买意图，调用 CourseTools/OrderTools。
CourseTools 通过 CourseClient 查课程详情；OrderTools 通过 TradeClient 预下单。
工具执行前从 ToolContext 取 userId，重设 UserContext，避免 Feign 401。
工具结果暂存 ToolResultHolder，流结束组装 1003 参数事件。
前端渲染课程卡片/订单确认卡片；历史回放时参数随消息返回。
4.4 RAG 知识库流程
离线阶段清洗课程资料/FAQ，切片后 Embedding 写入 ES；在线阶段对问题做 Embedding，TopK 检索后拼 Prompt，模型生成带来源的回答；召回为空或超范围时拒答。

图 4-3 RAG 知识库流程图
4.5 多智能体路由流程
RouteAgent 非流式判断意图，输出 RECOMMEND/BUY/CONSULT/KNOWLEDGE 或自然语言；AgentServiceImpl 按枚举分发；记录优化 Advisor 清理路由产生的两条临时消息。

图 4-4 多智能体路由图
4.6 学习问答自动回复流程
学员在课程下提问，interaction_question 落库；learning 服务异步调用 aigc /chat/text；生成内容写入 interaction_reply（系统用户 9999，is_student=0）；更新问题最新回答。
4.7 语音流程
TTS：文本 → OpenAI 语音合成 → 流式 mp3 → 前端播放。STT：前端 MediaRecorder 录音 → 上传 → Whisper 识别 → 繁转简 → 回填输入框。麦克风需 HTTPS/localhost，音频不落库。

5. 分层详细设计
5.1 分层结构与职责
<<<TABLE>>>
| 层次 | 组成 | 数量 | 主要职责 |
| 表现层 | Vue 3 页面/组件 | 6 个页面 | 渲染、交互、SSE 消费、卡片展示 |
| 控制层 | Controller | 8 个 | 参数校验、用户上下文、返回值封装 |
| 业务层 | Service/Agent/Tools | 15 个 | 业务规则、AI 编排、事务边界 |
| 数据层 | Mapper/Redis/ES | 8 个 Mapper | SQL、缓存、向量检索 |
| 公共层 | Advisor/拦截器/工具 | 12 个 | 鉴权、异常、日志、提示词、记忆 |
<<<END TABLE>>>

5.2 控制层设计
5.2.1 职责与编码约定
只做参数接收、基础校验、UserContext 读取与结果封装，不写业务规则。
统一使用 @RestController，返回 R<T> 或 Flux<ChatEventVO>（SSE）。
SSE 接口使用 @NoWrapper，避免全局返回包装破坏流。
路径按业务前缀划分：/chat、/session、/audio、/embedding、/courses、/orders。
5.2.2 统一返回结构
<<<TABLE>>>
| 字段 | 类型 | 说明 |
| code | int | 业务状态码，200 成功 |
| msg | string | 提示信息 |
| data | object | 业务数据 |
| requestId | string | 请求标识，便于排查 |
<<<END TABLE>>>

5.2.3 接口清单
<<<TABLE>>>
| 业务模块 | 请求方式 | 接口路径 | 功能说明 |
| 认证 | POST | /auth/login | 登录 |
| 认证 | POST | /auth/logout | 退出 |
| 用户 | GET | /users/me | 当前用户 |
| 课程 | GET | /courses | 课程列表 |
| 课程 | GET | /courses/{id} | 课程详情 |
| 课程 | GET | /courses/search | 课程搜索 |
| AI 会话 | POST | /session | 新建会话 |
| AI 会话 | GET | /session/hot | 热门问题 |
| AI 会话 | POST | /chat | 流式对话（SSE） |
| AI 会话 | POST | /chat/stop | 停止生成 |
| AI 会话 | POST | /chat/text | 非流式文本 |
| AI 会话 | GET | /chat/templates | 模板/欢迎语 |
| AI 历史 | GET | /session/{sessionId} | 当前会话消息 |
| AI 历史 | GET | /session/history | 历史会话列表 |
| AI 历史 | DELETE | /session/history | 删除会话 |
| AI 历史 | PUT | /session/history | 修改标题 |
| AI 工具 | GET | /courses/{id}/ai | 课程信息（Tool） |
| AI 工具 | POST | /orders/pre-place | 预下单（Tool） |
| 语音 | POST | /audio/tts-stream | 文字转语音 |
| 语音 | POST | /audio/stt | 语音转文字 |
| 向量 | POST | /embedding | 写入向量库 |
| 向量 | GET | /embedding/search | 向量检索 |
| 问答 | POST | /questions | 学员提问 |
| 问答 | GET | /questions/{id} | 问题详情 |
| 问答 | POST | /questions/{id}/replies | 回答 |
| 问答 | GET | /questions | 问题列表 |
| 评测 | GET | /eval/cases | 评测用例 |
| 评测 | POST | /eval/run | 批量评测 |
| 评测 | GET | /eval/summary | 评测统计 |
<<<END TABLE>>>

5.3 业务逻辑层设计

5.3.1 业务接口与实现清单
<<<TABLE>>>
| 业务接口 | 主要方法数 | 职责说明 |
| ChatService | 3 | 对话、停止、文本对话；ENHANCE/ROUTE/APP 三种实现 |
| ChatSessionService | 5 | 会话创建、热门问题、历史查询、标题、删除 |
| AgentService | 1 | 按意图分发到专业 Agent |
| AudioService | 2 | TTS/STT |
| CourseService | 3 | 课程列表、详情、搜索 |
| OrderService | 1 | 预下单 |
| QuestionService | 3 | 提问、回答、列表 |
| EvalService | 2 | 评测执行与统计 |
| EmbeddingService | 2 | 向量写入与检索 |
<<<END TABLE>>>

5.3.2 事务设计
<<<TABLE>>>
| 业务方法 | 涉及数据表 | 事务要求 |
| createSession | chat_session | REQUIRED，单表插入 |
| prePlaceOrder | order、order_detail | REQUIRED，两表一致 |
| askQuestion | interaction_question | REQUIRED，单表插入 |
| autoReply | interaction_reply、interaction_question | REQUIRED，回复+计数 |
| deleteSession | chat_session + Redis | 先 DB 后 Redis，失败补偿 |
| updateTitle | chat_session | REQUIRED，异步执行 |
| runEval | ai_eval_case | REQUIRED，批量写入 |
<<<END TABLE>>>

5.4 数据访问层设计
5.4.1 Mapper 与 SQL 语句清单
<<<TABLE>>>
| Mapper 接口 | 主要方法 | 对应数据表 |
| ChatSessionMapper | insert/select/update/delete | chat_session |
| CourseMapper | selectById/selectList | course、course_content |
| OrderMapper | insert/selectById | order |
| OrderDetailMapper | insert/selectByOrderId | order_detail |
| InteractionQuestionMapper | insert/select/update | interaction_question |
| InteractionReplyMapper | insert/selectByQuestionId | interaction_reply |
| LearningLessonMapper | select/insert/update | learning_lesson |
| AiEvalCaseMapper | insert/select/update | ai_eval_case |
<<<END TABLE>>>

5.4.2 关键数据访问设计
会话消息不落 MySQL：chat_session 只存元数据，消息存 Redis list。
课程查询走课程服务/只读 Mapper，不跨库写业务表。
订单与明细在同一事务写入，明细保存课程快照。
问答自动回复先写回复，再更新问题 latest_answer_id/answer_times。
评测结果按批次写入 ai_eval_case，支持按 category 统计。
5.5 公共组件设计
5.5.1 实体、视图对象与请求参数对象
<<<TABLE>>>
| 类名 | 类型 | 说明 |
| ChatDTO | DTO | 问题 + sessionId |
| ChatEventVO | VO | SSE 事件（eventType/eventData） |
| SessionVO | VO | 会话创建结果与热门问题 |
| MessageVO | VO | 历史消息与参数 |
| CourseInfo | Tool Result | 课程卡片数据 |
| PrePlaceOrder | Tool Result | 预下单卡片数据 |
| ChatSession | Entity | chat_session 表映射 |
| AiEvalCase | Entity | ai_eval_case 表映射 |
<<<END TABLE>>>

5.5.2 Advisor、拦截器与切面
<<<TABLE>>>
| 组件 | 类型 | 说明 |
| SimpleLoggerAdvisor | Advisor | 记录模型请求/响应 |
| MessageChatMemoryAdvisor | Advisor | 注入会话记忆 |
| QuestionAnswerAdvisor | Advisor | RAG 检索增强 |
| RecordOptimizationAdvisor | Advisor | 清理路由临时消息 |
| AuthFilter | Filter | 网关 JWT 校验 |
| GlobalExceptionHandler | Advice | 统一异常处理 |
| SystemPromptConfig | Config | Nacos 提示词热更新 |
| ToolResultHolder | Holder | 工具结果暂存 |
| RedisChatMemory | Memory | Redis 会话记忆 |
| UserContext | ThreadLocal | 用户上下文 |
<<<END TABLE>>>

5.5.3 常量与工具类
<<<TABLE>>>
| 类名 | 说明 |
| Constant | 工具名、参数名、事件类型常量 |
| ChatEventTypeEnum | 1001/1003/1002 事件枚举 |
| AgentTypeEnum | 5 个 Agent 类型枚举 |
| MessageUtil | 消息 JSON 序列化/反序列化 |
| JwtUtils | token 签发与解析 |
| IdUtil | sessionId/requestId 生成 |
<<<END TABLE>>>

6. 安全与权限设计
6.1 身份认证设计
登录由 auth 服务校验账号密码，签发 JWT；网关校验 token 并把 userId/role 注入请求头；业务服务从请求头恢复 UserContext。token 过期或非法返回 401。
6.2 密码存储设计
user.password 只存 BCrypt 哈希，不存明文；登录时用哈希比对；数据库账号、API Key 通过环境变量或 Nacos 加密配置注入。
6.3 权限控制设计
6.3.1 权限模型
<<<TABLE>>>
| 权限模型 | 取值 | 说明 |
| 角色 | 学员/教师运营/管理员 | 三类角色 |
| 资源 | 会话/课程/订单/问答/评测 | 按业务资源划分 |
| 操作 | 读/写/删除 | 按接口方法划分 |
<<<END TABLE>>>

6.3.2 权限控制层次
<<<TABLE>>>
| 控制层次 | 实现方式 | 粒度 | 控制范围 |
| 网关 | JWT 校验 + 路由白名单 | 接口 | 未登录拦截 |
| Controller | 角色注解/手动校验 | 接口 | 跨角色访问 |
| Service | userId 归属校验 | 数据 | 会话/订单归属 |
| 数据层 | SQL 带 userId 条件 | 行 | 防越权读取 |
<<<END TABLE>>>

6.3.3 角色权限分配
<<<TABLE>>>
| 角色 | 权限数量 | 主要业务范围 |
| 学员 | 12 | AI 会话、课程、订单、问答、语音 |
| 教师/运营 | 9 | 课程、资料、问答管理 |
| 管理员 | 8 | 用户、知识库、评测看板 |
<<<END TABLE>>>

6.4 数据安全设计
会话/订单/问答按 userId 隔离，sessionId 不能跨用户读取。
工具调用通过 ToolContext 传 userId，Feign 前重设 UserContext。
提示词注入与敏感词有兜底；工具参数做类型与范围校验。
API Key、数据库口令不入库、不进代码。
日志脱敏：不打印完整 token、手机号、身份证号。
6.5 操作审计设计
网关生成 requestId，aigc 与工具调用日志携带 requestId；关键写操作记录操作人、接口、参数摘要、耗时与结果；异常日志记录堆栈与业务上下文，便于追溯。

7. 异常处理与日志设计
7.1 异常体系设计
<<<TABLE>>>
| 异常类 | 说明 | 典型场景 |
| BizException | 业务异常 | 课程不存在、重复下单 |
| AuthException | 认证/权限异常 | token 过期、越权 |
| FeignException | 远程调用异常 | 课程/交易服务超时 |
| AiTimeoutException | 模型超时/限流 | 大模型 429/超时 |
| VectorStoreException | 向量库异常 | ES 不可用/维度不一致 |
| GlobalException | 兜底异常 | 未预期运行时异常 |
<<<END TABLE>>>

7.2 全局异常处理设计
使用 @RestControllerAdvice 统一捕获异常，转换为 R.error(code,msg)；SSE 接口在流内发送错误事件并结束；网关异常返回统一 JSON，前端按 code 提示。
7.3 错误码与错误页设计
<<<TABLE>>>
| 错误码 | 含义 | 处理方式 |
| 200 | 成功 | 正常返回 |
| 400 | 参数错误 | 前端提示并保留输入 |
| 401 | 未登录/过期 | 跳转登录 |
| 403 | 无权限 | 提示无权限 |
| 429 | 模型限流 | 提示稍后重试 |
| 500 | 服务异常 | 提示稍后重试并记录 requestId |
<<<END TABLE>>>

7.4 运行日志设计
<<<TABLE>>>
| 日志类型 | 记录内容 | 级别 | 落点 |
| 访问日志 | 路径、耗时、状态码、requestId | INFO | 网关/服务 |
| AI 日志 | 模型、token、首字延迟、工具调用 | INFO | aigc |
| 异常日志 | 异常类、堆栈、requestId | ERROR | 各服务 |
| 审计日志 | 操作人、接口、参数摘要、结果 | INFO | 管理端 |
| 慢查询日志 | SQL、耗时 | WARN | MySQL |
<<<END TABLE>>>

8. 缓存设计
8.1 缓存使用场景
<<<TABLE>>>
| 缓存内容 | 缓存键 | 过期时间 | 失效策略 |
| 会话消息 | CHAT:{conversationId} | 7 天 | 删除会话/停止补写 |
| 停止标识 | AI:STOP:{sessionId} | 5 分钟 | 生成结束删除 |
| 提示词 | Nacos 配置 | 长驻 | 监听热更新 |
| 会话标题 | chat_session.title | DB | 异步更新 |
| 热门问题 | Nacos tj.ai.session | 长驻 | 配置更新 |
| 课程热点 | course:{id} | 10 分钟 | 课程更新删除 |
| 评测汇总 | eval:summary | 30 分钟 | 批次完成刷新 |
<<<END TABLE>>>

8.2 缓存降级设计
Redis 不可用：会话记忆降级为无记忆，停止标识降级为本地状态。
ES 不可用：RAG 降级为无知识库回答，提示“知识库暂不可用”。
Nacos 不可用：使用本地缓存提示词，记录告警。
模型不可用：返回可读错误，允许重试；必要时切换备用模型。
8.3 缓存一致性设计
会话删除先删 DB 再删 Redis；停止生成时补写已生成内容；标题异步更新以 DB 为准；提示词以 Nacos 为唯一来源，应用只读缓存。

9. 界面设计
9.1 界面设计规约
布局：AI 工作台采用左会话、中对话、右上下文三栏；窄屏折叠为单栏。
色彩：主色深蓝，AI 回答浅灰底，课程卡片浅绿，警告/停止浅红。
字体：正文 14px，标题 20–24px，代码/路径使用等宽字体。
状态：加载中、空数据、失败、停止生成四种状态都有明确提示。
交互：Enter 发送、Shift+Enter 换行；停止按钮在生成中可用；卡片可点击。
9.2 页面清单
<<<TABLE>>>
| 模块 | 目录 | 页面数 | 页面文件 |
| 登录 | /login | 1 | Login.vue |
| AI 工作台 | /ai | 1 | AiWorkspace.vue、ChatPanel.vue、SessionList.vue |
| 课程 | /course | 2 | CourseList.vue、CourseDetail.vue |
| 订单 | /order | 1 | OrderConfirm.vue |
| 问答 | /question | 1 | QuestionDetail.vue |
| 管理端 | /admin | 3 | UserManage.vue、EvalDashboard.vue、KnowledgeManage.vue |
<<<END TABLE>>>

9.3 关键界面设计
AI 工作台是核心界面：左侧会话列表支持新建/历史/删除，中间对话区展示流式回答与课程/订单卡片，右侧上下文面板展示当前推荐、学习计划与回答来源。

图 9-1 AI 工作台界面设计示意
课程详情页展示封面、价格、章节目录与讲师信息；已购学员可直接开始学习，未购学员显示立即购买。章节完成状态与学习进度同步，课程资料可加入知识库供 AI 检索。

图 9-2 课程详情与章节学习界面设计示意
管理端数据概览以指标卡、趋势图和待处理列表呈现运营状态，覆盖用户、课程、会话、提问与模型调用指标，支持按日期筛选与导出报表。

图 9-3 管理端数据概览界面设计示意
知识库管理页展示文档名称、关联课程、切片数、解析状态与更新时间，右侧提供检索测试，可查看 topK 片段、相似度与来源，支撑 RAG 效果排查。

图 9-4 知识库管理界面设计示意
评测看板展示意图准确率、召回命中率、拒答准确率与失败用例，支持批量评测与历史记录查看，作为 Prompt 与路由调整的依据。

图 9-5 评测看板界面设计示意
10. 个人参与说明
10.1 个人参与与文档说明
本人岗位为 Java 开发实习生，在付红星指导下参与：第一周环境搭建、代码结构梳理与简单接口调试；第二周查询/修改接口、OpenFeign 调用与接口测试；第三周 AI 功能联调，包括模型调用、Prompt 配置与结果处理；第四周配合测试、补充文档与测试记录、了解部署并整理交接。
平台级架构、模块划分与 Agent 设计用于说明系统上下文；本人重点参与基础接口、OpenFeign 与 AI 联调，不代表独立完成全部设计。
接口路径、类名、表结构以项目实际代码为准；如文档与代码不一致，以代码为准，并在版本记录中注明变更。