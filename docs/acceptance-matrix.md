# 课栈智伴——30 项原始功能需求验收矩阵（M15 终验）

> 依据：《需求分析说明书》3.2（3.2.1 管理员×10、3.2.2 教师×5、3.2.3 学员×5、3.2.4 AI 智能体×7、3.2.5 公共×3，共 30 项）。
> 结论口径：✅ 已实现（自动化+浏览器证据）｜🟡 部分完成（附限制）｜REAL 列：未验证=无 DASHSCOPE_API_KEY，协议验证通过。
> 证据脚本：scripts/e2e-m2…m14（HTTP）、scripts/e2e-m6…m14-browser（浏览器）。执行结果见 16-M15执行报告 §测试统计。

## A. 管理员功能（3.2.1）

| # | 需求 | 阶段 | 服务 | 前端页面 | 核心接口 | 持久化 | 自动化 | 结论 | REAL | 限制 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 3.2.1.1 用户管理（查询/增改/启停/重置/角色/删除） | M2 | kzai-user | /admin/users | /admin/users/** | user、sys_user_role、sys_operation_log | e2e-m2 46项 | ✅ | 不适用 | 删除为 fail-closed 五类 Guard 全齐 |
| 2 | 3.2.1.2 课程分类管理 | M3 | kzai-course | /admin/categories | /admin/categories/** | course_category | e2e-m3 66项 | ✅ | 不适用 | 两级（原始三级字段保留未用，D3-1） |
| 3 | 3.2.1.3 课程审核与上下架 | M3 | kzai-course | /admin/course-audits | /admin/courses/{id}/audit/**、/offline | course_audit、sys_operation_log、通知事件 | e2e-m3 + m11 通知 | ✅ | 不适用 | — |
| 4 | 3.2.1.4 知识库管理（上传/审核/发布/撤回/重建） | M9/M11/M16 | kzai-aigc | /admin/ai/knowledge | /admin/ai/knowledge/**、/internal/ai/knowledge/retry-resource | ai_knowledge_document.publish_generation、ai_knowledge_index_job、ES | KbFlowMysqlTest 6；KbRaceMysqlTest 3；WithdrawnHitFilterTest 1 | 发布竞态已在 MySQL 验证 | REAL Embedding 与真实 ES 集群本次未跑 | 检索过滤用 ES 替身；Mock 向量无语义 |
| 5 | 3.2.1.5 Prompt 模板管理（查看/预览/发布/热更新） | M8 | kzai-aigc | /admin/ai/prompts | /admin/ai/prompts/** | Nacos + Redis 快照（D8-7 无版本表） | e2e-m8 26项 | 🟡 | REAL 未验证 | 草稿/历史版本/回滚未实现（用户 2026-10-20 决策暂不建表） |
| 6 | 3.2.1.6 评测用例与批量评测 | M12 | kzai-aigc | /admin/ai/evaluations | /eval/** | ai_eval_case(11 原始)+run/result/meta | e2e-m12 43项 + 浏览器 19 | ✅ | REAL 批次显式不可执行；Buy Dry-run 工具选择验证待批（E3） | MOCK 结果显著标识非真实质量 |
| 7 | 3.2.1.7 会话与问答监管 | M13 | kzai-aigc/learning | /admin/supervision/* | /admin/supervision/** | sys_session_review、hidden 字段、sys_operation_log | e2e-m13 A/B 系列 | ✅ | 不适用 | 消息正文 Redis TTL 过期如实标注 |
| 8 | 3.2.1.8 数据概览 | M13 | user(聚合) | /admin/dashboard | /admin/dashboard/overview | 只读聚合（ai_model_call_log 等） | e2e-m13 G 系列 | ✅ | 模型指标仅 REAL（当前 null+提示） | 无真实模型数据时显示「暂无」 |
| 9 | 3.2.1.9 操作日志 | M13 | kzai-user | /admin/operation-logs | /admin/operation-logs/** | sys_operation_log（90 天保留） | e2e-m13 C/D 系列 | ✅ | 不适用 | — |
| 10 | 3.2.1.10 系统参数 | M13 | kzai-aigc | /admin/system-settings | /admin/system-settings/** | sys_config_param/publish + Nacos + Redis | e2e-m13 E 系列 + 热更新 | ✅ | 不适用 | 发布失败保留最后有效配置（已验证） |

## B. 教师/运营功能（3.2.2）

| # | 需求 | 阶段 | 服务 | 前端页面 | 核心接口 | 持久化 | 自动化 | 结论 | REAL | 限制 |
|---|---|---|---|---|---|---|---|---|---|---|
| 11 | 3.2.2.1 教师工作台 | M5/M10 | learning | /teacher/*（多页） | /teacher/** | learning/course 域 | e2e-m5/m10 | ✅ | 不适用 | — |
| 12 | 3.2.2.2 课程与章节管理 | M3 | kzai-course | /teacher/courses、/teacher/chapters | /teacher/courses/**、/teacher/chapters/** | course、course_chapter(+extra) | e2e-m3 + m5 | ✅ | 不适用 | 视频经 course_resource 管理（D3/P4） |
| 13 | 3.2.2.3 课程内容与资料管理 | M3/M5/M16 | kzai-course | /teacher/courses/{id}/resources | /teacher/resources/{id}/retry-kb、/files/{id}/download | course_resource | ResourceRetryTest 2；TeacherResourceRetry 1 | 重试入口已测 | 不适用 | 其他教师、未审核、撤回、替换不能重试；不改访问级别 |
| 14 | 3.2.2.4 问答管理（回复/修正/隐藏/精华/重生成） | M10 | learning | /teacher/questions | /teacher/questions/**、/teacher/replies/** | interaction_*、ai_question_reply_task | e2e-m10 34项 | ✅ | AI 回复 MOCK（REAL 未验证） | aiAdoptRate=null（口径未确认） |
| 15 | 3.2.2.5 学情统计 | M5/M16 | learning | /teacher/learning-stats | /teacher/learning-stats/** | learning_lesson(_progress)、learning_activity_fact | LearningServiceTest 26；FactConcurrencyMysqlTest 5（30+10+20=60） | 并发累加已在 MySQL 验证 | 历史区间在 facts_since 之前为覆盖不足，不回填 | 日事实原子累加；乐观锁失败未写入的增量不记账 |

## C. 学员功能（3.2.3）

| # | 需求 | 阶段 | 服务 | 前端页面 | 核心接口 | 持久化 | 自动化 | 结论 | REAL | 限制 |
|---|---|---|---|---|---|---|---|---|---|---|
| 16 | 3.2.3.1 课程浏览与检索 | M1/M3 | course | /courses | /courses、/categories | course/course_category | e2e-m3 + 浏览器 m6 | ✅ | 不适用 | 禁用分类过滤 |
| 17 | 3.2.3.2 课程详情与章节学习（视频/断点/试看） | M5 | learning/course | /courses/{id}、/learn | /learning/lessons/**、/files Range | learning_lesson(_progress)、video_position | e2e-m5 107项 | ✅ | 不适用 | — |
| 18 | 3.2.3.3 学习进度（章节完成/断点续学/时长） | M5 | learning | /learn | /learning/lessons/** | learning_lesson(_progress)、video_position | e2e-m5 107项 + 浏览器 | ✅ | 不适用 | 进度跨设备一致（TC-STU-05） |
| 19 | 3.2.3.4 我的订单（预下单/列表/取消/幂等） | M4 | trade | /orders、/orders/{id} | /orders/** | order、order_detail、order_request_idempotent | e2e-m4 56项 | ✅ | 不适用 | 不接真实支付（D4-5） |
| 20 | 3.2.3.5 课程问答（AI 自动回复/修正） | M10 | learning | /questions | /questions/** | interaction_question/reply(+extra)、AI 任务表 | e2e-m10 | ✅ | AI 回复 REAL qwen-plus 已验证（ai_model_call_log 溯源） | — |

> M15.7 修订：原矩阵将 3.2.3.3/3.2.3.4/3.2.3.5 误登记为订单/问答/通知（遗漏「学习进度」且与 3.2.5.3 通知重复），已按《需求分析说明书》3.2.3 原始编号修正；站内通知唯一登记于 E 段 #30（3.2.5.3）。

> 2026-10-23 支付与退款：模拟支付必须经过验签通知才入账。支付宝沙箱代码已按开放平台 RSA2 验签写好，但本环境没有沙箱密钥，也没有发起沙箱或真实资金交易。`sandboxAccepted=false`。详见 `docs/95-支付与退款.md`。
>
> 2026-10-23 优惠券：满减和折扣、领取、兑换、下单锁定、支付核销、取消释放、退款不退券。`CouponPricingTest` 3 项、`CouponFlowTest` 3 项在独立 H2 库通过。并发领取未在 MySQL 上复测。详见 `docs/96-优惠券.md`。
>
> 2026-10-23 签到积分排行：见 `docs/97-签到积分排行.md`。`PointFlowTest` 在独立 H2 库运行。Redis 故障降级按无 Redis 客户端验证为 `DATABASE`，未停真实 Redis 进程。
>
> 2026-10-23 学习笔记：见 `docs/98-学习笔记.md`。默认私有。公开笔记不向未购、退款或过期用户返回付费章节正文。`NoteFlowTest` 在独立 H2 库通过。并发点赞未在 MySQL 上复测。

> 2026-10-23 学习计划：不是累计进度。接口 `/learning/plans`，页面 `/#/learning/plans`。本周完成量只统计上海时区、周一到下周一之前、且落在计划有效区间内的章节首次完成。修改目标从下周生效。事实覆盖不足时不显示 0。`LearningPlanMysqlTest` 9 项、`PlanWeekTest` 1 项、前端 `LearningPlanView` 2 项通过。没有在正在运行的服务上点选验收。详见 `docs/94-学员学习计划.md`。

## D. AI 智能体功能（3.2.4）

| # | 需求 | 阶段 | 服务 | 前端页面 | 核心接口 | 持久化 | 自动化 | 结论 | REAL | 限制 |
|---|---|---|---|---|---|---|---|---|---|---|
| 21 | 3.2.4.1 AI 会话与流式对话 | M6 | aigc | /ai | /chat(SSE)、/session/** | chat_session(8 字段)+Redis List | e2e-m6 66项+浏览器18；M15 REAL 四场景 | ✅ | **REAL qwen-plus 已验证**（SSE/多轮/历史，ai_model_call_log 溯源） | — |
| 22 | 3.2.4.2 停止生成与会话历史 | M6/M7 | aigc | /ai | /chat/stop、/session/history | AI:STOP 标识、Redis TTL 7 天 | e2e-m6（停止实测 54ms） | ✅ | REAL 会话历史持久化已验证 | 停止为单实例可靠（D6-8） |
| 23 | 3.2.4.3 多智能体意图路由 | M8 | aigc | /ai（agent 标签） | /chat 终态 agent 字段 | ROUTE_AUDIT 日志 | e2e-m8 + AgentRoutingTest；M15 REAL 路由 | ✅ | **REAL 路由已验证**（KNOWLEDGE/CONSULT/BUY/OTHER 实测；ROUTE prompt 正式链更新 pub-20261021040616） | — |
| 24 | 3.2.4.4 课程查询工具与卡片 | M7 | aigc+course | /ai（COURSE_CARD） | /chat 1003 卡片 | ToolResultHolder→消息 cards | e2e-m7 33项；M15 REAL searchCourses/getCourseDetail | ✅ | **REAL Tool Calling 已验证**（工具审计 success、章节契约防编造） | — |
| 25 | 3.2.4.5 购买预下单工具与卡片 | M7 | aigc+trade | /ai（ORDER_CARD） | 确认门禁+createPreOrder | AI:PROPOSAL、order（幂等） | e2e-m7（确认门禁/停止保护）；M15 REAL 门禁回归 | ✅ | REAL 确认门禁已验证；**Dry-run 零副作用**（独立 ChatModel+安全断言测试，真实选择验证见首批评测） | — |
| 26 | 3.2.4.6 RAG 知识库问答 | M9 | aigc | /ai（RAG_SOURCE 卡片） | 检索注入+引用 | ES kzai-knowledge-v{N} + real-test-v1 | e2e-m9 + ES IT；M15.5/15.6 REAL 检索+权限直验 | ✅ | **REAL Embedding v4/1024 维已验证；31 条 REAL 定量评测全部执行**（意图 31/31=100%、Hit@6 7/8、拒答 V1 Recall 60%→V2 识别合理拒答、通过 28/31——见 docs/archive/test-evidence/21-全部31条REAL评测执行与质量分析报告.md 与 docs/archive/implementation/22-真实AI质量修复与评测机制补强报告.md；M15.10 只读工具受控执行 REAL 验证：searchCourses/getCourseDetail 真实执行+防编造生效）；质量风险（幻觉工具叙述）已由 FABRICATED 判定+Prompt 约束+只读工具三重修复 | — |
| 27 | 3.2.4.7 语音输入与输出 | M14 | aigc | /ai（🎤/播报） | /audio/stt、/audio/tts-stream、/audio/capabilities | 不落库（内存）+ai_model_call_log | e2e-m14 35+浏览器10；M15.11-R1 真实 STT/TTS 专项 | ✅ | **REAL STT 成功**：qwen3-asr-flash HTTP 同步（Base64 Data URL），识别「你好，我想学习 Java 课程。」与音频语义一致（provider=DASHSCOPE/mock=false/审计入库）；**REAL TTS 成功**：sambert-zhichu-v1 WebSocket（run-task 单指令协议），120KB 有效 WAV（PCM 16bit/16kHz，3.76s）afplay 播放成功；协议实测修正 6 项（GET uploads/OSS 根路径/204/X-DashScope-Async/Sambert 单指令/Data URL）；Paraformer 文件转写（需公网 URL）与 paraformer-realtime-v2（当前账号 ModelNotFound）留档为当前环境不可用 | 实机麦克风/扬声器需用户手动验收；录音需 HTTPS/localhost；完整同次真实对话链路未验证（SSE 复用历史结果） |

## E. 公共功能（3.2.5）

| # | 需求 | 阶段 | 服务 | 前端页面 | 核心接口 | 持久化 | 自动化 | 结论 | REAL | 限制 |
|---|---|---|---|---|---|---|---|---|---|---|
| 28 | 3.2.5.1 登录与鉴权（JWT/黑名单/版本号） | M1/M2 | auth+gateway+user | /login | /auth/login、/users/me | user、sys_token_version、Redis 黑名单 | e2e-m2（伪造/过期/禁用/登出） | ✅ | 不适用 | — |
| 29 | 3.2.5.2 个人中心与修改密码 | M2 | user | /profile | /users/me、/users/me/password | user | e2e-m2 | ✅ | 不适用 | 改密后旧 token 全失效 |
| 30 | 3.2.5.3 站内通知 | M11 | user+双服务 Outbox | /notifications + 铃铛 | /notifications/**、/internal/notifications | sys_notification、sys_event_outbox | e2e-m11（幂等/隔离/补偿） | ✅ | 不适用 | — |

## 汇总

- ✅ 已实现：**27 项**（其中 21 项不依赖真实 AI，全部有自动化+浏览器证据；6 项 AI 相关功能协议完成、REAL 标注未验证）
- 🟡 部分完成：**3 项**——#5 Prompt（版本表未建=用户决策）、#6 评测（BUY Dry-run 工具选择验证待批方案）、#27 语音（真实 API/实机未验证，协议完成）
- ❌ 未实现：**0 项**（30 项原始需求均有对应实现；无凭空遗漏、无凭空发明）
- **REAL 补充验收清单（当前状态，M15.10）**：qwen-plus 对话/路由 ✅ 已验证；真实 Tool Calling ✅ 已验证（M15.7 前端 + M15.10 只读工具受控执行 TOOL_AUDIT success）；真实 Embedding ✅ v4/1024 维（real-test-v1，非 1536 维目标）；真实 RAG 命中 ✅（31 条评测 7/8）；31 条 REAL 评测 ✅ 全部执行（28/31 通过、意图 31/31）；真实 Paraformer STT / sambert TTS ⏸ 未验证；系统具备 MOCK/REAL 开关切换能力（零代码改动）

## M15.5 REAL 补充验收进度（2026-10-21，详见 docs/archive/implementation/18-真实Embedding与RAG接入报告.md）

| 项 | 状态 | 说明 |
|---|---|---|
| qwen-plus 对话/路由/真实 Tool Calling | ✅ REAL | M15 阶段完成；本轮回归保持 |
| 真实 Embedding | ✅ REAL | text-embedding-v4 / 1024 维（实测）；单次调用 + 批量向量化 REAL API 成功 |
| 真实 RAG 命中（小样本） | ✅ REAL | 独立索引 kzai-knowledge-real-test-v1；改写/语义/有效期 3 场景命中（score 0.80-0.86），来源卡片 docId/score/provider 可回溯；无召回拒答 ✅；PROTECTED 已授权可见 ✅、未授权 E2E 部分（模型行为，证据链补齐） |
| 课程信息幻觉修复 | ✅ REAL | chapters 契约（E 授权）；编造章节→DB 章节一致 |
| ES/Embedding 故障处理 | ✅ 受控注入 | ES 挂起 7.8s 降级（超时修复后）；429 为单测注入 |
| ≥30 条 REAL 评测 | ✅ 已执行（M15.8） | 31/31 条全部真实执行（5 批次）：意图 31/31=100%、Hit@6 7/8、拒答 V1 Recall 60%（V2 已识别合理拒答）、通过 28/31（1 执行故障+1 规则分歧+1 配置冲突）——见 docs/archive/test-evidence/21-全部31条REAL评测执行与质量分析报告.md；M15.10 完成 8 项质量修复复核（只读工具真实执行/防编造/V2/BUY 双工具）——见 docs/archive/test-evidence/23-真实AI质量修复REAL复核与验收报告.md |
| 真实 Paraformer STT / sambert TTS | ⏸ 未执行 | 用户未授权（接入建议见 19 报告 §20） |


## M23 课程语义推荐验收（2026-10-23）

| 验收项 | 结果与证据边界 |
|---|---|
| 真实 Embedding、独立 ES 课程召回 | 已执行 v4/1024维；固定8场景满足预期，额外4表达3项满足；是小样本，不是全目录准确率 |
| 混合召回、当前价格与购买资格 | 现有课程服务/学习/交易服务真实 API 验收；卡片价格与课程详情复核 |
| 上下架/编辑/删除同步 | 持久化分页对账；单测验证变化版本、幂等、物理删除墓碑；ES 实测旧写不能覆盖墓碑；未在业务库改课程来造测试 |
| 失败补偿与模型隔离 | H2 测试重试耗尽、人工重投、重建版本防护；真实 ES 独立模型命名空间协议通过 |
| 当前用户记录/越权 | 当前用户查询单测、偏好会话隔离及跨用户真实 API 403；读取失败拒绝假设为空 |
| 购买确认 | 保留原服务端确认和幂等；OrderTools 10项、PurchaseProposalStore 6项回归通过；未执行真实支付或新开课交易 |
| 前端 | 推荐条件、真实证据与价格、条件重设、已购标记、咨询/选择回填；30项测试通过、7跳过，构建成功 |
| 扩大回归 | AI 189通过/2跳过；课程26通过；学习66通过/2跳过/2项旧测试库缺积分表错误，不能视为整体全绿 |

完整文件、指标定义、首次误召回记录、网络失败、漏召回、未执行项和演示步骤见 [交付报告](99-AI课程语义推荐.md)，原始小样本 JSON 与页面截图见 `docs/evaluation/course-recommendation/`。


## 推荐回答策略增量验收（2026-10-24）

| 验收项 | 结果 |
|---|---|
| 纯主题先查课、未说明条件不默认 | 参数守卫与流式单测通过；新旧真实会话“我要学 Python”均先调用推荐工具，四项可选条件保持未限定 |
| 无召回/条件排除/故障区分 | 服务层结构化原因及重叠计数单测通过；真实请求确认 Python 两路查询正常且候选0；真实预算变更返回预算排除说明 |
| 旧会话与实际发布 | Nacos已发布 pub-20261024075445，其他四类内容未变；真实旧会话仍绑定52，新会话绑定59，映射前后不漂移 |
| 原有能力回归 | AI 208通过/2跳过；购买门禁10项、提案6项通过；未更改购买门禁实现 |
| 前端 | 31通过/7跳过；构建成功，lint0错误/23条既有警告 |
| 真实流程 | 第一轮5项流程断言通过；最终版本另以两个临时会话完成5项策略断言，全部清理；不是全目录推荐准确率 |

基线重放的两次上游超时未计为成功复现。真实故障注入、生产压测和实际购买/支付未执行。原始输出和边界见 [修复报告](AI推荐回答策略与无结果处理修复报告.md)。
