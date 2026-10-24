# 设计决策登记（decisions.md）

本文件按阶段登记所有偏离"原始文档直接实现"的技术决策、扩展表与风险权衡。
每项决策均注明：背景 → 决策 → 影响 → 状态。**未登记即不得实施**。

---

## M1（已实施）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D1-1 | 端口由 8080~8083 改为 **18080~18083** | 本机 8082/8083 已被其他程序占用 | 仅本地环境；`scripts/run-services.sh` 与网关路由同步 | 已实施 |
| D1-2 | 取消 MP 全局逻辑删除，改为 `Course/CourseContent/CourseChapter` 实体上 `@TableLogic` | 全局 `logic-delete-field: deleted` 会给无 `deleted` 列的 `course_category`/`user` 追加非法条件 | 仅影响查询构建 | 已实施 |
| D1-3 | 依赖补充 `mybatis-plus-extension`、`mybatis-plus-jsqlparser` | MP 3.5.9 起分页插件拆包；Spring Boot 版本未调整 | 构建依赖 | 已实施 |
| D1-4 | `spring-boot-maven-plugin` 显式声明 `repackage` execution | 未继承 starter-parent，jar 无主清单 | 构建配置 | 已实施 |
| D1-5 | 登出按 `jti` 写 Redis 黑名单 `AUTH:BLACKLIST:{jti}` | 原文无 token 撤销表，JWT 本身无法失效 | 扩展设计 E1 | 已实施 |

## M2（已实施）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D2-1 | 新增 `sys_user_role(user_id, role_code)`，**不改** `user.type` | 原文 `user.type` 仅 1员工/2学员/3老师，无法区分 ADMIN/STAFF；需求禁止篡改枚举 | 历史账号无需补数据（回退推导） | 已实施 |
| D2-2 | 新增 `sys_operation_log` 记录管理操作 | 原文 12 张表无操作日志载体 | 新增表，仅写不读业务 | 已实施 |
| D2-3 | Token 失效采用 **jti 黑名单 + 用户级版本号** `AUTH:TOKEN:VER:{userId}`，未新增表 | 满足"禁用/重置密码/改密后旧 token 全部失效" | 依赖 Redis（见 D2-7 遗留） | 已实施 |
| D2-4 | 角色校验走 `@RequireRoles` + 拦截器，身份只认网关注入头 | 需求禁止依赖前端隐藏按钮 | 后端多层校验 | 已实施 |
| D2-5 | 网关拒绝外部访问 `/internal/**` | 服务间接口不得暴露 | 路由约束 | 已实施 |
| D2-6 | `AuthException` 的 HTTP 状态码跟随业务码（401/403） | 原先固定 401，权限不足语义失真 | 前端可区分 401/403 | 已实施 |

## M2 遗留修复（本轮已实施）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D2-7 | **身份信任边界**：网关/Feign 对身份头做 HMAC-SHA256 签名（`X-Internal-Sign`），业务服务校验通过才采信；无签名按匿名处理，`/internal/**` 无签名直接 403 | 此前业务服务无条件信任 `X-User-Id`/`X-User-Roles`，绕过网关直连业务端口即可伪造管理员 | 所有服务新增 `kzai.internal.secret`；`@FeignClient` 显式 `configuration = InternalAuthFeignConfig` | **已实施并验证**（直连伪造返回 401/403） |
| D2-8 | **JWT 密钥外置**：`secret: ${KZAI_JWT_SECRET:...}`，内部签名密钥 `${KZAI_INTERNAL_SECRET:...}` | 生产密钥不得落源码/正式配置 | 本地保留 dev 默认值；部署必须注入环境变量 | **已实施** |
| D2-9 | **严格删除 fail-closed**：`Constants.REQUIRED_DELETION_BINDINGS`（课程/订单/学习/会话/互动）每一类都必须有校验实现，缺失即拒绝删除 | 需求：不得假设"服务未建设 = 无业务数据" | **当前删除功能一律拒绝**，直到 M4(订单)/M5(学习)/M3(互动) 各注册 Guard；开关 `kzai.user.deletion.strict`（默认 true） | **已实施并验证** |
| D2-10 | `course_chapter` 字段数**勘误**：原文档与真实 MySQL 均为 **8 个字段**，M1 报告中"9 字段"为笔误 | 逐项核对 `docs/extracted/db.md` 3.3.5 与 `SHOW CREATE TABLE` | 仅文档更正，代码与 DDL 无需变更 | **已核实** |

## 待用户确认（尚未实施）

| # | 议题 | 最小方案 | 影响评估 | 状态 |
|---|---|---|---|---|
| P1 | `user.type=1` 回退角色当前为 **ADMIN**，普通员工账号存在提权风险（如 seed 中 `ai_bot` type=1 且无角色记录 → 获得 ADMIN） | 回退角色改为 **STAFF**（无管理权限），ADMIN 必须由 `sys_user_role` 显式授予 | 仅影响"无角色记录且 type=1"的账号；seed 中 `admin` 已有 ADMIN 记录不受影响；`ai_bot` 降为 STAFF | **已批准并实施** |
| P2 | Redis 中 token 版本号丢失后旧 token 会恢复有效，且 Redis 不可用时网关默认放行 | 新增 `sys_token_version(user_id, version, update_time)` 持久化版本号；Redis 仅作缓存；校验顺序 DB → Redis；DB 与 Redis 同时不可用 → **拒绝放行** | 新增 1 张表；网关需调用 auth 内部接口查版本号（增加一次内部调用，可本地短缓存） | **已批准并实施** |

## M3（已批准后实施完成）

| # | 议题 | 最小方案 | 影响评估 | 状态 |
|---|---|---|---|---|
| P3 | 课程审核需表达"草稿/待审核/驳回/强制下架"，但 `course.status` 原文枚举仅 1待上架/2已上架/3下架/4已完结，禁止重定义 | 新增 `course_audit(id, course_id, audit_status, reason, auditor_id, auditor_name, create_time, update_time)` 独立审核记录；`course.status` 仅按原文流转（提交审核=1待上架，通过=2已上架，强制下架=3下架） | 新增 1 张表，不改原表 | **已批准并实施** |
| P4 | 原表无文件/视频/资料载体（`course_chapter` 无视频地址字段） | 新增 `course_resource(id, course_id, chapter_id, kind, file_name, file_url, file_size, content_type, uploader, create_time, deleted)`；封面/资料/视频统一登记，文件落本地受控目录并通过 `/files/{id}/download` 鉴权下载 | 新增 1 张表；不新增任何原始表字段 | **已批准并实施** |
| P5 | 章节视频地址/课时数 | **不新增** `course_chapter` 字段；视频通过 `course_resource`（kind=VIDEO，绑定 chapter_id）表达 | 保持原表 8 字段不变 | **待确认**（随 P4） |
| P6 | 站内通知（审核结果通知教师） | 原文属 M11 范围；本阶段**不实现**，审核结果仅在审核记录与接口返回中体现，报告明确标注缺口 | 无 | 已知缺口 |

---

## M3 实施决策（2026-10-19 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D3-1 | 分类管理**只支持两级**（新增/修改校验父级必须为一级）；`course` 原有 `first/second/third_cate_id` 三字段语义保留，仅数据层面不再写入三级 | 需求"本阶段支持两级管理"，同时禁止改动原始表字段 | 三级分类创建返回 400；学员端/管理端均按两级树展示 | 已实施 |
| D3-2 | 价格**统一以「分」入库**（`course.price` INT），接口与页面按元输入并在服务层 `×100` 取整，禁止浮点金额 | 原始表 `price` 为 INT，需求要求价格准确 | 免费课程 `price=0` 且 `free=1` | 已实施 |
| D3-3 | **审核中禁止编辑**：`course_audit` 最新记录为待审核（1）时，教师编辑课程直接 400 | 避免"审核中内容被偷偷改动" | 教师端详情页返回 `editable=false`，前端禁用保存 | 已实施 |
| D3-4 | 已上架课程修改**关键字段（名称/价格）后回到待上架**并需重新审核 | 需求：课程状态流转需审核把关 | 学员端立即不可见，需管理员重新通过 | 已实施 |
| D3-5 | 文件落**服务端受控目录**（`kzai.file.root`，默认 `data/upload`），库内只存相对路径；对外只暴露 `/files/{id}/download` | 需求：禁止假链接，访问必须鉴权 | 下载鉴权：管理员 / 上传人 / 课程已上架且分类启用；其余 403 | 已实施 |
| D3-6 | 分类禁用后**学员端不可见**：课程列表与详情均过滤禁用分类（含一级禁用连带隐藏其下二级分类课程）；课程创建人/管理员仍可预览 | 需求：分类禁用后学员端不展示 | 审核通过时校验分类启用，禁用分类的课程无法上架 | 已实施 |
| D3-7 | 章节删除为**逻辑删除**（`deleted=1`），并同步 `course.section_num`（沿用原字段，不新增） | 需求：删除与排序状态持久化 | 列表过滤逻辑删除，原生 SQL 校验 `deleted=1` 仍落库 | 已实施 |
| D3-8 | 审核流转为**显式状态机校验**（`CourseSupport.checkTransition`）：1→2 通过、2→3 下架、3→1 重新提交；非法流转直接 400 | 防止重复/非法操作导致状态错乱 | 重复提交、重复下架均被拒 | 已实施 |
| D3-9 | 站内通知闭环**不在本阶段实现**（原文属 M11）；审核结果仅在 `course_audit` 与接口返回中体现，教师端可查看驳回原因 | 需求禁止跨阶段抢建 | 已知缺口，M11 补齐 | 已知缺口 |

---

## M4 实施决策（2026-10-19 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D4-1 | **新增最小扩展表 `order_request_idempotent`**（user_id + request_id 唯一索引），原始 `order`/`order_detail` 零改动 | 原始表无 requestId 字段，无法表达"userId + requestId 幂等" | 迁移 `sql/06_m4_trade.sql`；未创建任何 `order_master`/`order_flow` 替代表 | 已实施 |
| D4-2 | 幂等以**数据库唯一索引 + 同一事务**保证：先插幂等记录(处理中) → 建订单/明细 → 回写 order_id；并发冲突时整事务回滚，外层回读既有订单 | 需求明确禁止仅用 Redis 锁替代数据库约束 | 重复/并发请求只产生一笔订单，失败不留孤立明细或占位记录 | 已实施 |
| D4-3 | 幂等请求内容用 **SHA-256(课程ID升序)** 指纹比对；同 requestId 不同内容返回 400 冲突 | 需求要求区分"重复提交"与"请求冲突" | 冲突提示明确指向 requestId | 已实施 |
| D4-4 | 金额**全部 INT 分**，服务端按课程实时价格求和；接口支持以「元」字符串提交（`MoneyUtils` BigDecimal 精确换算，超 2 位小数直接拒绝） | 禁止浮点误差，前端不可信 | 前端不再自行乘 100；溢出/非法格式返回 400 | 已实施 |
| D4-5 | **支付边界**：本阶段只做预下单/查询/取消，不接支付宝/微信、不提供"置为已支付"接口 | 需求明确不伪造支付 | 已支付状态仅作为历史业务状态；测试用数据明确标注 `SEED-DEMO 演示已支付订单（非真实支付）` | 已实施 |
| D4-6 | 学习权益由**交易服务内部接口**判定，课程服务通过 Feign 查询，不直连订单表；交易服务不可用时 fail-closed（视为未购） | 保持微服务边界 + 安全默认拒绝 | 付费课程资料仅对已支付用户开放；免费课程公开 | 已实施 |
| D4-7 | 用户删除前订单关联校验由 **`TradeAssociationGuard`（Feign 调交易服务）** 实现；校验服务不可用按"存在关联"处理 | 需求要求用户服务不直连订单表，且不得默认放行 | 有订单的用户删除被拒；交易服务宕机时同样拒绝删除 | 已实施 |
| D4-8 | 全部 `Long` 统一**序列化为字符串**（Jackson ToStringSerializer） | 雪花 ID 19 位超出 JS 安全整数（2^53-1），前端 JSON.parse 会静默丢精度，实测导致"提交订单命中错误课程ID" | 前端一律按字符串透传 ID，禁止 `Number()` 转换；端到端脚本同步按字符串比较 | 已实施 |
| D4-9 | 待审核列表改为按**每门课程最新一条审核记录**过滤 | M3 遗留缺陷：历史 PENDING 记录使已通过课程长期留在待审核列表 | 已通过/已驳回课程不再出现在待审核列表 | 已实施（修复 M3 缺陷） |

---

## M5 实施决策（2026-10-19 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D5-1 | **新建独立微服务 `kzai-learning`（18085）**，注册 Nacos，经网关 `/learning/**`、`/teacher/**` 暴露；不改写 course/trade/user 已有逻辑 | 需求要求独立学习微服务，且不得推倒重写已完成模块 | 新增 6 个微服务；Gateway 新增路由 | 已实施 |
| D5-2 | **原始 12 张核心表零改动**；新增两张最小扩展表：`learning_lesson_progress`（章节级进度明细）、`course_chapter_extra`（章节试看/时长） | `learning_lesson`（10 字段）只能表达课程级最近状态，无法承载章节独立进度/断点/有效时长；`course_chapter`（8 字段）无试看字段 | 迁移 `sql/07_m5_learning.sql`；原表字段/枚举/语义完全保留 | 已实施 |
| D5-3 | 章节进度唯一键 **`uk_user_chapter(user_id, course_id, chapter_id)`**；写入走"条件更新 + 回读"，学习时长只按**视频位置真实前进量**累计 | 需求禁止重复累计、禁止用登录/退出时间差冒充有效时长；并发上报需幂等 | 回放/重复上报不增加 `study_seconds`；进度不倒退 | 已实施 |
| D5-4 | **课程完成率由服务端按"已完成章节数 / 实际章节数"计算**，不信任前端传入的百分比 | 需求明确禁止信任前端 100% | `learning_lesson.progress` 由服务端回写 | 已实施 |
| D5-5 | **免费课程入课独立流程**：`POST /learning/lessons` 直写 `learning_lesson`，**不生成任何订单/支付记录**；重复入课返回既有记录 | 需求禁止用伪造支付状态实现免费入课 | 免费课可入课且不污染成交额 | 已实施 |
| D5-6 | **付费权益判定联合订单主体 + 订单明细 + 退款 + 有效期**：明细状态 2/4/5 结合订单主体 `status` 与退款状态判定；歧义/历史组合一律**不授予**（fail-closed） | 需求禁止默认授予，退款/过期语义必须先定义规则 | 已取消订单、已退款明细、已过期权益均被拒 | 已实施 |
| D5-7 | **交易服务不可用 = 无权益**（fail-closed），返回明确提示"未查询到该课程的已支付订单，请先购买课程"/服务不可用提示 | 需求要求受控错误，不得放行 | 受保护资源在依赖故障时安全拒绝 | 已实施 |
| D5-8 | **试看为教师显式设置的落库标识**（`course_chapter_extra.free_preview`），未标记一律不开放；禁止"第一章永远免费"硬编码 | 需求禁止硬编码冒充业务规则 | 未购学员仅能访问明确授权章节，改 URL 无效 | 已实施 |
| D5-9 | **视频断点服务端持久化**（`video_position_sec`），前端仅上报位置；跨设备恢复依赖数据库，不使用 localStorage 作为事实来源 | 需求明确禁止只存 localStorage 宣称跨设备续学 | 刷新/重新登录/换设备均恢复真实断点 | 已实施 |
| D5-10 | **HTTP Range 在课程服务实现**：合法 → 206 + `Content-Range`；越界/起止倒置/非法表达式 → 416；无 Range → 200 | 需求要求验证 206 与非法 Range 受控处理 | 视频拖动可用，异常 Range 不 500 | 已实施 |
| D5-11 | **教师学情只统计本人课程**（服务层按课程归属二次校验），越权返回 403 | 需求禁止教师 A 查看教师 B 数据 | 非本人课程学情查询被拒 | 已实施 |
| D5-12 | **课程问答数量与 AI 回复采纳率返回 `null`，页面与 CSV 明确标注"M10 未完成"** | 依赖 M10 问答数据，本阶段禁止硬编码 | 统计口径声明可见；不假装实现 | 已实施 |
| D5-13 | **CSV 导出防公式注入 + UTF-8 BOM**：`= + - @ \t \r` 开头加前导 `'`，双引号转义，换行替换为空格 | Excel 公式注入与中文乱码 | 导出的中文字段正常，公式不被执行 | 已实施 |
| D5-14 | 用户删除关联校验新增 `LearningAssociationGuard`（Feign → `/internal/learning/lessons/count-by-user`），服务不可用返回负数按"存在关联"处理 | 延续 D2-9 fail-closed 与 D4-7 模式 | 有学习记录的用户删除被拒 | 已实施 |
| D5-15 | 前端修复两处缺陷：①课程详情/学习页在**路由参数变化时未重新加载**（组件复用导致显示上一门课的权益状态）；②接口报错仅显示 axios 的 "Request failed with status code 403"，改为展示服务端业务原因 | 浏览器 E2E 实测发现 | 课程间跳转状态正确；未购提示可读 | 已实施（修复 M5 自测缺陷） |

---

## M6 实施决策（2026-10-19 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D6-1 | **新建独立微服务 `kzai-aigc`（18086）**，分层为 controller / chat / session / memory / advisor / provider / config / dto / vo；注册 Nacos，经网关 `/chat/**`、`/session/**` 暴露 | 需求要求独立 AI 微服务，且不得推倒重写 M1–M5 | 微服务增至 7 个；Gateway 新增 2 条路由 | 已实施 |
| D6-2 | **严格复用原始 `chat_session` 表（8 字段零增删）**；新建会话 id 走雪花、sessionId 走 UUID；**消息正文不落此表** | 需求明确禁止为核心表加字段（尤其禁止为统计 AI 采纳率加列） | 元数据落 MySQL，正文落 Redis | 已实施 |
| D6-3 | **依赖版本锁定**：Spring AI `1.0.0-M6`（父 POM BOM）+ Spring AI Alibaba `1.0.0-M6.1`；排除 `spring-alibaba-nacos-config`（会强行拉起 `NacosConfigAutoConfiguration` 并因连不上 Nacos 导致启动失败） | 实测启动失败，需求禁止靠关校验绕过 | aigc 仅用 Nacos Discovery | 已实施（附构建证据） |
| D6-4 | **排除 `com.google.protobuf:protobuf-java-util`，显式引入 `protobuf-java:3.25.5`** | 该版本 POM 内含 `$com.google.protobuf:protobuf-java:jar` 占位符，Maven 解析报错 | 依赖树干净，编译通过 | 已实施（附 dependency:tree 证据） |
| D6-5 | **`DashScopeAutoConfiguration` 整体 exclude**，按需由 `AiModelConfig` 手工导入 `DashScopeChatConfiguration` 并装配 `ChatModel` → `ChatClient` | 该自动配置内含 Rerank/Embedding/Image 等无关模块，缺 Key 时全部启动失败；同时避免多个默认模型 Bean 冲突 | 仅当 `DASHSCOPE_ENABLED=true` 且 `DASHSCOPE_API_KEY` 非空才创建真实模型 Bean | 已实施 |
| D6-6 | **API Key 只经环境变量 `DASHSCOPE_API_KEY` 注入**；源码/配置模板/日志均不含密钥；未配置时启用**独立 MOCK Provider**，接口返回 `mock=true`、`provider="MOCK"`，前端显著标识 | 需求要求密钥不入库、MOCK 必须可识别且不得计入真实模型验收 | 无 Key 环境仍可验证 SSE 协议与会话流程 | 已实施 |
| D6-7 | **Redis 记忆**：`CHAT:{userId}_{sessionId}`（TTL 7 天）存全量消息 JSON List；**停止标识** `AI:STOP:{sessionId}`（TTL 5 分钟），生成开始时清除、终态时清理，防止误伤下一轮 | 需求明确 Key 格式与 TTL；需防旧停止标识影响新请求 | 刷新/切会话可恢复；停止只存已输出内容 | 已实施 |
| D6-8 | **停止生成** = 同一 sessionId 应用内单飞锁（`StopRegistry.tryAcquire`）+ Redis 停止标识；生成循环在每段增量前检查，命中即 `cancel` 订阅并只持久化已输出片段 | 需求禁止"只停前端、后端继续跑" | 停止后历史中只有真实输出的部分 | 已实施（**仅单实例可靠**，见报告第 12 节） |
| D6-9 | **会话所有权服务端强制校验**：所有会话接口按 JWT 上下文 `userId` 过滤，越权返回 **403**（含读取/重命名/删除/stop） | 需求禁止用户访问他者会话 | 跨用户请求全部被拒 | 已实施 |
| D6-10 | **幂等键 `messageId`**：首次写入 Redis 标记，重试同一 messageId 不再重复写用户消息 | 需求禁止重试导致消息重复持久化 | 重试安全 | 已实施 |
| D6-11 | **Redis 不可用降级**：消息改存进程内存（上限 20 条），并在 SSE 终态返回 `persisted=false` + 明确文案，前端显著提示 | 需求禁止把临时内容冒充持久会话 | 用户可见"未可靠保存" | 已实施 |
| D6-12 | **SSE 协议 1001/1002/1003**：M6 只发 1001（文本增量）与 1002（终态，每轮恰好一个）；1003 保留解析与编码能力但不构造卡片 | 需求禁止 M6 伪造课程/订单卡片 | 协议兼容 M7 | 已实施 |
| D6-13 | **删除补偿**：先删 MySQL 元数据（切断可访问性），再清 Redis；Redis 删除失败写 `AI:PENDING_CLEAN:{sessionId}`（TTL 30 天）并由定时任务重试 | 需求要求"不允许残留消息被越权读取" | 残留 Key 也无法被任何人访问，且会被重试清理 | 已实施 |
| D6-14 | **M5 遗留修复——有效学习时长防虚增**：单次上报增量 = min(位置前进量, 距上次上报真实流逝时间 + 3 秒容差)，再受 900 秒单次上限约束 | 需求禁止把拖动进度条的全部差值当真实观看时长 | 实测拖动 700 秒仅计 8 秒 | 已实施 |
| D6-15 | **M5 报告勘误**：原报告"真实 MySQL 集成测试"实为被跳过的 H2 用例（缺 schema）；本轮补 `sql/08_m6_aigc.sql` 并新增可选真实 MySQL 集成测试（需 `KZAI_MYSQL_TEST=true`，输出实际 JDBC URL 作为证据） | 需求要求按实际数据源与执行日志判断，不按测试类名 | 测试分类口径修正 | 已实施 |
| D6-16 | **用户删除保护扩充**：新增 `ChatSessionAssociationGuard`（Feign → `/internal/ai/sessions/count-by-user`），服务不可用返回负数按"存在关联"处理；**INTERACTION Guard 仍未实现，删除流程维持 fail-closed** | 需求禁止宣称已解除 | 有会话/互动可能的用户删除被拒 | 已实施 |

---

## M7 实施决策（2026-10-19 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D7-1 | **Redis 消息存储恢复原始设计的 List 结构**（`CHAT:{userId}_{sessionId}`，每条消息一个元素，RPUSH 追加 + 每次 EXPIRE 刷新 7 天 TTL）；M6 的 String JSON 数组在读/写路径遇 `WRONGTYPE` 时**无损迁移**：迁移锁（SETNX 10s）→ 复验类型 → 全量读出 → 原样重写为 List，绝不删除旧会话消息 | 原始设计（系统设计说明书 5.5.3/8.1）明确要求 Redis List；M6 偏离 | 历史旧数据自动迁移且可继续对话；写入天然保序、并发安全 | 已实施 |
| D7-2 | **工具调用必须携带 `FunctionCallingOptions`**（`functionCallbacks + toolContext + proxyToolCalls=true`）构造 Prompt；禁止照搬新版示例用普通 `new Prompt(messages)` + `.functions(...)` | 经 `AdvisedRequest.toPrompt()` 字节码核验：M6 ChatClient 仅在 options 为 `FunctionCallingOptions` 时才合并回调与 ToolContext，普通 Prompt 会**静默丢弃**工具 | 用桩 ChatModel 单测锁定该行为（含陷阱回归用例） | 已实施 |
| D7-3 | **工具身份只来自 ToolContext 的可信上下文**（userId/roles 由 ChatService 从网关验证后的 UserContext 写入）；工具执行线程恢复 UserContext、结束必清理；模型入参只有意图字段（keyword/courseId），无任何身份/价格/状态字段 | 需求禁止模型指定身份；防 ThreadLocal 线程池复用泄露 | 用户 A 无法替用户 B 下单；Prompt Injection 无法改变工具权限 | 已实施 |
| D7-4 | **ToolResultHolder 实例随生成轮次创建**（userId+sessionId+轮次隔离，无全局静态）；按 cardId 去重——同实体只发一张 1003，后到载荷就地合并覆盖（如 getCourseDetail 补充章节数） | 需求禁止跨用户/跨会话结果串流、禁止重复发送同一工具结果 | 一轮多工具结果有序保存；重复模型 Tool Call 只产生一张卡片 | 已实施 |
| D7-5 | **购买确认门禁**：AI 首次收到购买意图只查真实课程并登记「待确认提案」（`AI:PROPOSAL:{userId}_{sessionId}`，绑定课程列表、TTL 10 分钟）；仅当同会话下一条用户消息匹配**严格确认短语**（整句「确认/确认购买/好的/是的/同意/下单吧」等）才标记 confirmed；预下单执行前校验 confirmed 且包含该课程；成功（含幂等命中）即消费提案 | 需求禁止凭模糊购买意图写库；确认须绑定用户+会话+课程且不可跨会话/过期复用 | 「帮我购买 X」不产生订单；「确认」才产生且只产生一笔 | 已实施 |
| D7-6 | **稳定 requestId**：由 proposalId 派生（`ai-{proposalId}`），交易失败/超时**不消费提案**，重试复用同一 requestId | 需求禁止超时后换新 requestId 再下单；幂等由 order_request_idempotent 保证 | 超时重试幂等命中同一订单；相同 requestId 不同课程被交易服务判冲突 | 已实施 |
| D7-7 | **写工具受停止保护**：`createPreOrder` 执行前检查 `AI:STOP:{sessionId}`，停止即拒绝；M6 的「break 出迭代遗留订阅」改为 `takeWhile(stop)` 向上游传播取消 | 需求禁止停止后执行未授权写操作；修复 M6 遗留资源清理 | 停止后不产生订单；上游模型流被真实取消 | 已实施 |
| D7-8 | **MOCK 工具脚本**（`MockToolScriptService`）：MOCK Provider 下按确定性脚本触发**与真实路径完全相同**的工具实现（CourseTools/OrderTools/ProposalStore），回复显著标注【MOCK · 脚本化工具流】 | 无 API Key 环境需验证 1003/提案/幂等协议，但禁止把 MOCK 冒充真实模型 Tool Calling | 协议验收可自动化；真实模型 Tool Calling 单独标记未验证 | 已实施 |
| D7-9 | **工具审计走结构化日志（logger=TOOL_AUDIT）+ 进程级指标，不新增审计表、不写 sys_operation_log** | sys_operation_log 归 kzai-user 服务域，跨服务高频写不合适；日志已含 requestId/sessionId/toolName/耗时/成败/结果数/受控错误类型，且不含 API Key、JWT、用户原文 | 零 schema 变更；如后续需持久化审计可扩展专用表（另行确认） | 已实施 |
| D7-10 | **SSE 终态不再重复携带全文**：`content` → `contentLength`，新增 `cardCount` 与可选 token 用量；修复 M6 遗留的重复传输 | 1001 已传输全部增量，终态全文是冗余且增大长回答开销 | 前端与 e2e 同步适配；协议变更登记 api-extension.md | 已实施 |
| D7-11 | **课程服务新增内部 AI 查询契约**（`/internal/courses/ai-search`、`/internal/courses/{id}/ai`），复用 searchPage/可见性判定逻辑，不新建业务表；`CourseAiVO` 的 ID 按字符串传输、金额按分 + 元展示值 | 需求要求复用现有课程服务与 `/courses/{id}/ai` 原始设计；禁伪造数据 | 课程可见性（上架/分类启用/未删除）由课程服务统一裁决 | 已实施 |
| D7-12 | **系统提示词追加工具使用策略**（价格必须来自工具、未确认不调用 createPreOrder、错误如实转述），系统消息不入 Redis 历史 | 约束模型行为；权限仍由服务端强制（提示词不是安全边界） | 真实模型下工具调用更稳；MOCK 不受影响 | 已实施 |
| D7-13 | **自动标题改为条件更新**（仅 `title IS NULL OR ''` 时写入） | 修复 M6 遗留：读取-更新的竞态窗口会覆盖用户主动重命名 | 并发下重命名优先 | 已实施 |

---

## M8 实施决策（2026-10-20 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D8-1 | **M7 缺陷修复：`proxyToolCalls` 由 TRUE 改为 FALSE**。字节码核验（DashScopeChatModel 1.0.0-M6.1 call/stream）：`isProxyToolCalls=true` 时模型**不执行工具**、直接返回 ToolCall 元数据——M7 误设 TRUE 会导致真实模型工具从不执行 | 任务书要求运行级核验；已被计数测试模型（走真实 AbstractToolCallSupport 框架代码）实证并固化回归用例 | 真实模型工具链路（ToolCall→执行→ToolResponseMessage 回传→续轮）恢复正确；真实调用仍待 Key 验收 | 已实施（附测试） |
| D8-2 | **M7 缺陷修复：模型构造期注册工具回调**。字节码核验：M6 的 schema 发送与执行解析都来自构造期 `functionCallbackRegister=merge(defaultOptions, toolFunctionCallbacks)`，运行期回调只提供名字；M7 用 2 参构造（空 register）执行时报 "No function callback found"。AiModelConfig 改用 5 参构造注入 ToolRegistry 全量回调；各 Agent 白名单仍由运行期回调子集控制（按名解析，白名单 ⊆ register） | 同上，计数模型实证空 register 失败路径 | 工具 schema 按白名单发送；执行可解析 | 已实施（附测试） |
| D8-3 | **工具轮次预算**：`ToolResultHolder.tryAcquireToolCall`（每工具每轮上限 8），超限返回受控提示引导模型直接作答 | M6 框架 `handleToolCalls` 递归**无最大迭代保护**，模型无限 ToolCall 将无限续轮；在不升级版本前提下于工具层兜底 | 防无限工具循环与费用失控；预算语义有单测 | 已实施 |
| D8-4 | **多智能体编排并入 ChatService**（不新建第五套会话服务）：RouteAgent 非流式路由 → OTHER 直答 / 专业 Agent（Prompt 快照 + 记忆 + 白名单工具）流式；复用 M6/M7 的 provider、RedisChatMemory、ToolRegistry、ToolResultHolder、StopRegistry、SSE 体系 | 任务书禁止重复实现；原始设计 4.5 | 终态新增 `agent` 标识（向后兼容） | 已实施 |
| D8-5 | **RecordOptimization 等价机制 = 路由零落库**：RouteAgent 使用独立临时消息列表（System+User 各一条，进程内），不触碰 RedisChatMemory；不依赖"先写后删"清理 | 原始设计 7.3 的目标是历史不重复，零写入更简单且无清理失败窗口 | e2e 实证 5 轮对话 Redis 恰好 5+5 条，路由失败也零残留 | 已实施 |
| D8-6 | **路由严格枚举 + 双重兜底**：模型输出经 `AgentTypeEnum.parseIntent` 精确枚举解析；OTHER/GREETING → 直接回复；无法解析/模型超时/异常 → 兜底 CONSULT（受控降级，不阻塞输入）；ROUTE_AUDIT 记录意图/耗时/错误，不记录正文 | 原始需求 3.2.4.3 | 非法枚举与上游故障不影响可用性；MOCK 脚本路由显著标识、不计入真实准确率 | 已实施 |
| D8-7 | **Prompt 方案为「内置默认 + Nacos 直发」，暂不建表**（用户 2026-10-20 决策）：草稿/历史版本/回滚持久化（ai_prompt_template/ai_prompt_version 两表方案已提交）暂缓，相关验收标记未实现 | 用户选择暂不建表 | D23/D24/D29/D32 标记未实现；其余 Prompt 能力（查看/预览/发布/审计/热更新/降级/恢复）完整落地 | 已实施（范围裁剪） |
| D8-8 | **Nacos 网关用 v1 OpenAPI + 有界回读一致性校验**：POST 成功响应为 "true"（非 "ok"）；Derby 单机持久化有秒级延迟，写后回读重试 ≤5s，超时如实返回失败（不应用本地）；30s 轮询兜底最终一致 | 真实 Nacos 2.x 实测；防止"页面显示成功但实际未生效"与反向不一致 | e2e 实证发布→Nacos 真实写入→热生效；失败路径配置不变 | 已实施 |
| D8-9 | **Prompt 热更新三层生效链**：Nacos（权威）→ Redis `AI:PROMPT:EFFECTIVE`（重启恢复）→ 代码内置默认（永不失败）；原子替换（AtomicReference），生成开始读取快照、运行中稳定；校验失败拒绝应用并保留已验证版本 | 任务书第七部分全部 10 条要求 | e2e 实证发布热生效、历史不被污染、非法拒绝、重启恢复（source=NACOS） | 已实施 |
| D8-10 | **Gateway 精确路由优先**：`/admin/ai/prompts/**` → kzai-aigc 置于 `/admin/**` 之前；后端 `@RequireRoles(ADMIN)` 强制（不依赖前端） | 任务书第八部分 | /admin/users、/admin/courses 等既有路由不受影响（回归通过） | 已实施 |
| D8-11 | **本地 Nacos 未开启鉴权**——本地开发环境限制，生产发布前必须开启 Nacos 鉴权并加密配置（原始设计 6.2） | 本地 2.x standalone 默认无鉴权 | 报告显著标注；Prompt 内容校验已拒绝密钥字样 | 已标注 |

---

## M9 实施决策（2026-10-20 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D9-1 | **新增两张扩展表**（用户批准 + 调整）：`ai_knowledge_document`（含审核状态 review_status 0待审核/1已发布/2已撤回 + reviewer/review_time——用户要求的调整项）与 `ai_knowledge_index_job`（状态机 0待处理/1执行中/2成功/3失败，attempt≤5，重启补偿）；迁移 `sql/10_m9_knowledge.sql` 幂等执行 | 任务书要求持久化文档元数据/任务/失败原因；原始 AS5「经管理员审核后入库」 | 仅「已发布」文档可被检索；原始 12 表零改动 | 已实施 |
| D9-2 | **切片不建独立元数据表**：切片正文+chunkIndex 存 ES，MySQL 仅存计数；重复解析确定性产生同切片 | 用户未勾选切片表选项；ES 本身可追溯 docId/chunkIndex | 减少写入放大；重建以文件为源 | 已实施 |
| D9-3 | **ES 采用 HTTP 适配层（RestClient）而非 spring-ai vector store**：M6 的 spring-ai-elasticsearch 适配面向 8.x 但与本地 8.11.4 兼容性未经验证；设计说明书 3.4 明确「aigc→Elasticsearch HTTP」 | 任务书允许「经过验证的 Elasticsearch Java Client 适配层」；直接 REST 最透明、零额外客户端依赖 | knn 检索/写 bulk/别名原子切换全部实测通过 | 已实施 |
| D9-4 | **版本化索引 + 别名原子切换**：`kzai-knowledge-v{N}` + alias；重建=新版本 bulk→数量核验→单次 _aliases 原子 remove+add→验证成功前旧索引/别名不动 | 原始约束「重建索引期间旧索引继续可用」 | ES IT 实证切换前别名不变、失败不切换；旧版本索引保留可回滚 | 已实施 |
| D9-5 | **Embedding 双实现 + 显著标识**：`MOCK`（哈希投影 256 维，缺省；仅协议链路）与 `DASHSCOPE`（text-embedding-v1 真实，Key 环境变量注入，维度运行时实测写入 mapping）；qwen-plus 聊天模型绝不冒充向量 | 任务书第六部分；无 Key 环境需可独立验证 ES 链路 | Mock 链路不计入真实语义检索验收（报告分列） | 已实施 |
| D9-6 | **检索授权双层过滤**：ES 预过滤（reviewStatus=1+accessLevel/courseId 候选）→ **MySQL 实时权威复核**（已发布/未删除；PROTECTED 走 TradeClient 权益查询 fail-closed；INTERNAL 仅 ADMIN） | 任务书第八部分「不能只靠模型判断」「撤回/失效后重新检查」 | 撤回/删除/失效立即不可召回（e2e 实证） | 已实施 |
| D9-7 | **KnowledgeTools.searchKnowledge 仅授 KNOWLEDGE Agent**；RAG 来源以 `RAG_SOURCE` 卡片进 ToolResultHolder 随 assistant 消息持久化，历史回放免检索恢复；检索内容为非可信输入，工具无任何写能力 | 任务书第九部分 | Agent 权限矩阵更新（KNOWLEDGE 行 +searchKnowledge）；注入文档指令不改变工具行为（单测） | 已实施 |
| D9-8 | **MockToolScript 分支严格跟随 RouteAgent 路由结果**（tryStream 增加 routedAgent 参数），消除脚本二次自检与路由分歧（修复「购买规则 FAQ」误入 BUY 分支） | M8 遗留：文本含「购买」但意图为知识问法时脚本与路由不一致 | 脚本分支 = 路由结果；M7/M8 回归通过 | 已实施 |
| D9-9 | **扫描型 PDF 无 OCR → 明确「不支持文本提取」**，不生成虚假文本；PDF 魔数（%PDF-）与扩展名双重校验 | 任务书第四部分 | 解析失败原因可视（fail_reason 落库） | 已实施 |
| D9-10 | **Prompt 建表决策维持 M8 结论**：`docs/decisions.md` D8-7 记录用户 2026-10-20 明确选择「暂不建表」（问答确认留痕），非助手建议；直发模式保留，草稿/版本/回滚/会话版本绑定维持「未实现」标记 | 任务书第一部分第 2 节要求核对 | 需求追踪表更新 | 已核对 |

---

## M10 实施决策（2026-10-20 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D10-1 | **新增两张扩展表**（用户批准选项 E）：`ai_question_reply_task`（异步任务 + 租约 + 幂等）、`interaction_reply_extra`（AI 来源/模型/修正审计）；迁移 `sql/11_m10_interaction.sql`（幂等，不覆盖 10 号脚本） | 原 `interaction_question`/`interaction_reply`（各 14 字段）语义不得改；异步任务与 AI 归因需要载体 | 原两表零改动；`status`(0/1)、`is_student`(0/1) 语义保持 | 已实施 |
| D10-2 | **任务幂等用生成列 + 唯一索引，不用 Redis 锁**：`active_flag=IF(status IN(0,1),1,NULL)` + `UNIQUE(question_id, active_flag)`；领取=原子条件更新 + `worker_id/lease_until` 租约；失败按 `attempt≤5` 指数退避 | 任务书要求任务可恢复、不重复消费；Redis 锁需额外续约组件 | 同问题同时至多一个进行中任务；Worker 崩溃后租约到期可被安全接管 | 已实施 |
| D10-3 | **AI 回复落库为单一事务**（任务 SUCCESS + 回复 + extra + 问题聚合字段） | 避免"回复已入库但任务/问题未更新"的半截状态 | 失败则整体回滚，任务保留原因，前端显示失败而非静默 | 已实施 |
| D10-4 | **`aiAdoptRate` 维持 null**：`questionCount` 真实接入，「AI 采纳率」口径未确认 | 用户明确要求未确认前不得臆造公式 | 统计接口返回 null，不以精华/AI 回复占比冒充 | 已实施 |
| D10-5 | **修正审计复用 `sys_operation_log`**：`prev_content` 仅保存修正前内容快照，完整历史走操作日志时间线 | 用户要求不得用快照冒充完整历史 | 修正可追溯时间与操作人，快照仅作对照 | 已实施 |
| D10-6 | **隐藏内容仅教师/管理员可见**：过滤条件不含"问题提出者"分支 | 隐藏回复若对 owner 可见等同泄漏 | 学员（含提问者）看不到隐藏回复，e2e 实证 | 已实施 |
| D10-7 | **Gateway 路由前置**：`/questions/**`、`/teacher/questions/**`、`/teacher/replies/**` → learning，均置于课程服务 `/teacher/**` 通配之前；多路径不合并为逗号单路由（实测不生效） | 通配路由抢先命中会导致 404/500 | 教师问答链路正确到达 learning；`/teacher/**` 既有路由不受影响（回归通过） | 已实施 |
| D10-8 | **M9 顺带修复纳入 M10**：① 逐文档重建改就地重建（原实现切全局别名会导致其他文档不可检索）；② `ai_knowledge_index_job` 补 `uk_doc_active` 并发唯一约束 | 用户批准的 M10 方案含该补丁；重建隔离缺陷为实测发现的真实缺陷 | ES IT 6/6（含新增多文档隔离用例） | 已实施 |
| D10-9 | **启动自检 `MappingSelfCheck`**：aigc 启动打印映射数量并对含 `//` 的路径告警 | 前缀常量拼接产生 `/internal//ai` 曾长期以 404 形式存在并被 fail-closed 掩盖 | 同类路径问题启动即暴露 | 已实施 |
| D10-10 | **真实模型/真实 Embedding 未执行**：无 `DASHSCOPE_API_KEY`，AI 回复与向量走 Mock 并在界面显著标识 | 环境限制，不得谎报 | 报告分列「未执行」；Mock 不计入真实语义验收 | 已标注 |

---

## M11 实施决策（2026-10-20 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D11-1 | **通知归属 kzai-user 增量实现**（不新建通知微服务、不引入 MQ） | 用户要求优先在现有 `kzai-user` 内增量开发；仓库已验证 OpenFeign + 内部签名 | 3 张表：`sys_notification` + learning/course 各一张 `sys_event_outbox` | 已实施 |
| D11-2 | **Outbox 分库两份**（learning 库、course 库各一张同结构表） | 用户批准选项 A：与各自业务表同库同事务，业务回滚则事件不产生 | 两服务各自 Worker 投递；跨服务不需要共享表 | 已实施 |
| D11-3 | **outbox 保留 `payload_json`(1000, 可空)，不加 trace_id** | 用户勾选「保留 payload_json」 | payload 仅放跳转/展示补充信息，不放受保护正文 | 已实施 |
| D11-4 | **通知幂等以 MySQL 唯一约束为唯一保障**：`UNIQUE(recipient_id,event_key)` + `UNIQUE(event_key)` | 用户要求不得用 Redis 临时 Key 代替持久化保障 | 重复投递（含 Feign 超时重试）只命中同一行；并发 10 次实测 1 条 | 已实施 |
| D11-5 | **可靠投递 = 本地 Outbox + 原子领取 + 租约 + 指数退避**（≤5 次），不使用业务提交后的单次 Feign | 跨服务事务无法用单个 `@Transactional` 保证 | 通知服务不可用时事件保留并补偿；重启自动恢复；通知失败不回滚业务、不重复业务数据 | 已实施 |
| D11-6 | **同一回复只对应一个通知类型**：`AI_REPLY:{replyId}` 与 `QUESTION_REPLY:{replyId}` 互斥；对 AI 回复的追问不再通知 | 用户要求避免同一 replyId 双通知 | 追问 AI 回复不通知（AI 回复已有通知），避免骚扰 | 已实施 |
| D11-7 | **教师普通文字修正不产生通知**；修正历史由 `sys_operation_log` 时间线承载 | 用户要求「默认只对新回复产生通知，避免重复骚扰」 | `correct` 不入队；历史可追溯（修复后实测多次修正多条记录） | 已实施 |
| D11-8 | **审核通过通知以真实审核记录为事件依据**（`COURSE_APPROVED:{auditId}`） | 用户要求不得依据「当前为已上架」重复发送 | 重新提交并再次通过 → 新事件；驳回/失败不产生 | 已实施 |
| D11-9 | **权限模型**：身份只取 JWT，所有查询/变更强制 `recipient_id` 条件；越权/不存在统一 body.code=404；未登录 401；内部接口 403 | 用户安全要求；项目既有约定（业务异常 HTTP 200 + body.code） | 跨用户读写删全部拒绝；不信任客户端 recipientId | 已实施 |
| D11-10 | **用户删除时同库事务清理其通知** | 避免新增表产生未处理的用户关联残留 | 物理删除该用户通知，不误删他人（单测覆盖） | 已实施 |
| D11-11 | **M9 重建改为版本化发布**（用户批准方案 + 孤儿清理策略 C） | 故障注入实证「就地重建中途失败 → 新旧混用/部分覆盖」 | 写新版本 → 核验 → MySQL 权威版本切换（发布）→ 清理旧版；检索按权威版本过滤；`OrphanChunkCleaner` 定时兜底；不改表、不改索引架构 | 已实施 |
| D11-12 | **审计路径双斜杠修复**（`/internal//users` → `/internal/users`） | M10 验收发现修正审计从未落库（405/500 被 fail-closed 静默） | `sys_operation_log` 真实记录多次修正；全仓排查同类拼接 | 已实施 |
| D11-13 | **通知文案规则**：只存摘要（≤100 字、去换行）；MOCK 必须显著标识；跳转仅站内固定路由 | 用户要求防 XSS、防受保护内容泄漏、防危险跳转 | 前端纯文本插值渲染；通知存在 ≠ 业务权限 | 已实施 |

---

## M12 实施决策（2026-10-20 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D12-1 | **新增三张扩展表**（用户批准）：`ai_eval_run`（批次）、`ai_eval_result`（单条结果，只插入不覆盖）、`ai_eval_case_meta`（标注扩展 1:1）；迁移 `sql/13_m12_evaluation.sql` 幂等 | 一个用例多次执行必须保留历史；原始 `ai_eval_case` 11 字段无批次/尝试/标注载体且语义不可挪用（expected_doc_id/expected_intent 语义固定，question 是被评测输入） | 批次与结果全量快照（模型/Embedding/知识库版本/Prompt 摘要/执行人），历史永不覆盖；`ai_eval_case.actual_*` 仅作最近一次镜像 | 已实施 |
| D12-2 | **批次防并发重复用生成列 + 唯一索引**（`active_flag` 仅运行中=1 + `UNIQUE`），沿用 M10 `uk_question_active` 模式 | 任务书要求控制重复点击并发重复批次；数据库约束为唯一可靠依据 | 全局至多一个运行中批次；冲突返回受控 400 | 已实施 |
| D12-3 | **重试 = 新 attempt 新行**（`UNIQUE(run_id,case_id,attempt)`），统计取每用例最新 attempt | 用户要求重试可追溯，不覆盖历史 | 失败重试后旧 attempt 行保留；批次收尾按最新 attempt 聚合 | 已实施 |
| D12-4 | **重启策略 = 明确终止**（用户批准）：启动补偿将运行中批次置 4「已终止」并保留已完成结果 | 用户在两个选项（明确终止/自动恢复）中选择简单安全方案 | 实测：构造运行中批次重启 → 置 4 + 明确 error + 日志 | 已实施并验证 |
| D12-5 | **REAL 模式无 Key 显式不可执行**（status=5 终态 + 明确文案），绝不退化 MOCK 冒充真实评测 | 任务书第五部分/第十二部分硬性要求 | 本轮环境无 `DASHSCOPE_API_KEY`：REAL 批次创建即终态，无任何结果行，报告如实标注「REAL 未执行」 | 已实施并验证 |
| D12-6 | **评测安全隔离**：专用 `EVAL_USER_ID=1`、默认学员视角（`data_deps=admin_test_access` 用例切换管理员测试视角）；评测路径不触碰 RedisChatMemory/会话/通知 Outbox/业务表/知识库状态；BUY 写工具 Dry-run（记录选择、绝不调 TradeClient；MOCK 下 NULL） | 任务书第六部分；依赖 Prompt「不要下单」不可作为安全边界 | 实测：批量评测期间订单数不变、无 `EVAL-` 前缀会话、无评测通知 | 已实施并验证 |
| D12-7 | **拒答/引用/泄露自动判定基于公开启发式规则**（`EvalRefusalRules`：拒答话术表/文档标题引用/泄露标题匹配），结果行留存 `refusal_rule`；泄露类只标 `leak_suspected` 待人工复核 | 任务书第七部分：不能让模型自评打分宣称真实拒答准确率；无法可靠判定时标记待人工复核 | 指标页面展示口径与局限声明 | 已实施 |
| D12-8 | **执行失败与模型判断错误分离**：结果 status 1完成/2执行失败/3超时/4跳过；主指标（意图准确率/Hit@6）将执行失败计为未正确分类/未命中，执行成功率独立展示 | 任务书第七部分：不得通过排除失败样本美化指标 | summary 中 `execSuccessRate`、`execFailedCount` 独立可见 | 已实施 |
| D12-9 | **M9 既有缺陷修复：ES HTTP 网关 String body 默认 ISO-8859-1 编码损坏非 ASCII 内容**——中文标题文档 bulk 写入必失败（`document_parsing_exception: docTitle preview ''`），M9 起所有测试标题均为 ASCII 故从未暴露。修复：`EsKnowledgeGateway` 全部请求体改 UTF-8 字节发送；`bulkIndex` 失败时记录首个失败项原始行与具体原因 | M12 验收发现（SEED 中文文档索引 5 次重试全失败）；修复后 4 份中文文档索引成功、`KnowledgeEsIT` 8/8 回归通过、M9 e2e 25/25 通过 | 含中文标题/内容的知识文档此后可正常建立索引 | 已实施并回归 |
| D12-10 | **M9 TopK 挤占增强：检索增加 MySQL 权威 (docId,indexVersion) 版本白名单 ES 预过滤**（`should` 子句 + minimum_should_match），未发布/过期版本切片不占用 TopK 候选窗口；MySQL 逐条复核保留（删除/撤回/权益双保险）；白名单上限 500 文档，超限回退 3 倍候选扩大口径 | 任务书第一部分：实测证明无预过滤时 k=18 窗口被未发布 V2 切片占据 12/18（有效结果挤占→漏召回）；修复后窗口仅含权威版本切片 | 多版本混合数据测试：F 文档不再被 E 的 20 片无效版本挤出；E 只返回已发布 V1；`hit.indexVersion` 随命中返回供评测审计 | 已实施并验证 |
| D12-11 | **MOCK 路由桩最小词表增强**（测试桩，非被测真实模型）：补充「哪门课/有没有/讲什么/多少个章节/证书/折算」等自然问法词 | 评测问句为自然表达，原桩词表覆盖不足导致大量 OTHER 兜底，协议链路验证覆盖不足；增强后 MOCK 路由分发协议验证覆盖六类意图 | 不影响真实路由（RouteAgent 模型调用）；M8/M9 e2e 断言不变且回归通过；MOCK 路由准确率仍显著标识为脚本口径 | 已实施并回归 |
| D12-12 | **越权负样本判定反转**：`should_refuse=1` 且标注期望文档的 KNOWLEDGE 用例（越权访问受保护资料），RAG 判定为「期望文档必须不可召回」（命中=越权召回，严重失败）；不应按普通 RAG_MISS 判失败 | 首跑实测发现判定缺陷：越权用例被误判 RAG_MISS | `verdictRule=RAG_FORBIDDEN@6`；命中时 failReason=UNAUTHORIZED_RECALL | 已实施 |
| D12-13 | **Prompt 版本管理边界维持 M8 决策**：批次仅记录生效 Prompt 快照摘要（版本号+五类模板哈希），不新建 Prompt 表 | D8-7 用户明确「暂不建表」；M12 不提前实现草稿/版本/回滚 | `ai_eval_run.prompt_snapshot` 承载快照追溯 | 已实施 |
| D12-14 | **`aiAdoptRate` 维持 null**（M10 决策不变）；M12 不改动学情统计口径 | 用户未确认新口径 | 无变化 | 维持 |

---

## 永久开发规范（2026-10-20 用户指定，自 M12 起生效）

| # | 规范 | 内容 | 执行时点 |
|---|---|---|---|
| R1 | **全历史阶段代码清理** | 每个阶段（M12 起含 M13/M14/M15）结束前，必须回溯检查 M1→当前阶段的**全部代码**：测试类、E2E 脚本、SQL/SEED、配置、临时文件、调试接口/打印、无效 Mock 与演示逻辑；只删除确认无关/重复/失效项 | 每阶段结束前 |
| R2 | **禁止误删有效测试** | 保留全部真实单测/集成/E2E 回归、历史 Bug 防回归（ES 故障注入、异步任务、Outbox 幂等、鉴权/并发/事务/安全测试、M12 Mock 与 Dry-run）；**不得以删测试/减断言/跳过测试提高通过率** | 每次清理 |
| R3 | **清理后必须重新验证** | 全仓 Maven + 前端构建 + M1–当前阶段 HTTP/浏览器回归，并对清理前后测试数量与场景核对；清理导致失败必须修复后阶段才可结束 | 每阶段结束前 |
| R4 | **报告新增章节** | 阶段报告新增「M1–当前阶段全仓历史代码清理」章节（检查范围/删除项与原因/合并项/保留项/测试数量变化/清理后验证结果/未清理项与原因） | 每阶段报告 |
| R5 | **数据红线** | 不得删除用户真实业务数据、正式数据库记录、历史迁移文件、必要审计记录（含评测批次历史） | 永久 |

---

## 历史决策勘误（M13 阶段核对，2026-10-20）

> 以下勘误**不改动历史记录原文、不改动数据库与业务代码语义**，仅标注当前实际有效方案。
> 勘误依据：真实源码、真实数据库结构（information_schema）与 M12 阶段验收证据。

| 原决策 | 原文要点 | 勘误结论 | 当前有效方案 |
|---|---|---|---|
| **D7-2** | 工具调用必须携带 FunctionCallingOptions，`proxyToolCalls=TRUE` | **已被 D8-1 取代**：D8-1 字节码核验证明 `proxyToolCalls=true` 时模型不执行工具；M8 起固定为 FALSE 并有 `ProxyToolCallsRuntimeTest` 回归锁定。D7-2 中「proxyToolCalls=true」的部分不再有效，其余（FunctionCallingOptions + toolContext + 构造期注册 D8-2）仍然有效 | `proxyToolCalls=FALSE` + 构造期 functionCallbackRegister（D8-2） |
| **D9-4** | 版本化索引 + 别名原子切换（`kzai-knowledge-v{N}`）作为文档重建的隔离机制 | **部分已被 D11-11 取代**：别名原子切换仍用于「物理索引」的创建/维度变更；但文档级重建隔离已由 D11-11 的「文档发布版本（MySQL 权威 index_version）+ 索引内多版本共存 + 检索侧白名单预过滤（D12-10）」取代，不再依赖全局别名切换来隔离重建 | 物理索引：别名机制；文档版本：MySQL 权威 index_version + 预过滤 |
| **D10-8** | M9 逐文档重建改为「就地覆盖写 + 残留清理」 | **已被 D11-11 取代**：就地覆盖存在「中途失败新旧混用」窗口（M11 故障注入实证）；现行为版本化发布（写新版本→数量核验→权威版本切换→清理旧版本） | D11-11 + D12-10 预过滤 |
| **D11-1 / D11-2** | learning/course「分库两份」Outbox，与各自业务表同库同事务 | **勘误（部署事实）**：全服务共用单一 `course_stack_ai` Schema，实际是**同一张** `sys_event_outbox` 物理表由两服务共享；「同一真实本地事务」性质不变（同库同事务成立），原子条件领取防双领、eventKey 全局唯一使共享安全。M12 验收（learning 7/7 + course 4/4 真实 MySQL IT）基于该事实 | 单 Schema 单表共享（若未来真分库，两服务各自建表方案仍成立） |
| **D2-9** | 删除严格 fail-closed，必需关联类型 Guard 缺失即拒绝删除；D6-16 补充「INTERACTION Guard 仍未实现」 | **已补齐（M10）**：五类 Guard（TRADE/COURSE/LEARNING/CHAT_SESSION/INTERACTION）全部真实存在（源码核验），`REQUIRED_DELETION_BINDINGS` 五类绑定完整，删除保护不再处于「部分实现」状态 | 五类 Guard 全部生效 |
| **D5-12 / D10-4** | `questionCount` 与 `aiAdoptRate` 返回 null（M10 未完成/口径未确认） | **部分更新**：`questionCount` 已在 M10 真实接入（学情统计真实数据）；**`aiAdoptRate` 仍保持 null**——采纳率统计口径未经用户确认（D10-4/D12-14 决策不变） | questionCount 真实；aiAdoptRate=null 待口径确认 |
| **D12-2（补充说明）** | `uk_active_run` 生成列唯一约束防重复批次 | 与实现一致，无需勘误。补充：该模式与 M10 `uk_question_active` 同源 | 维持 |
| **D9-5 / D12（REAL 相关）** | Mock/真实严格区分 | 与实现一致；补充：M12 验收实证 REAL 批次创建即 status=5 终态、零结果行，无任何 MOCK 冒充 REAL 路径 | 维持 |

**勘误方法论**：每项均以当前仓库源码（`grep`/文件存在性）、`information_schema` 真实结构与既有测试证据为准；仅 M12 之后的真实实现与历史表述不一致时才标注，不追溯改写历史决策原文。

---

## M13 实施决策（2026-10-20 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D13-1 | **四张扩展表**（用户批准附六类落实要求）：`sys_session_review`/`sys_config_param`/`sys_config_publish`/`ai_model_call_log` + `sys_operation_log.idx_create_time` | 监管复核历史、参数草稿与发布审计、真实模型指标持久化需要载体；原始 12 表零改动 | 全部落库真实数据；迁移幂等已验证 | 已实施 |
| D13-2 | 参数乐观锁：草稿保存/发布按 `version` 条件更新（每变更+1） | 用户要求并发编辑/发布防旧覆盖新 | 开发中发现初版未递增 version 的真实缺陷并修复（E4b 断言锁定） | 已实施并验证 |
| D13-3 | 发布链区分「Nacos 发布结果」与「运行时生效验证」（runtime_verified） | 用户要求；Nacos 写成功≠热更新成功 | 发布记录三态可见；跨实例经 30s 轮询如实标注 | 已实施 |
| D13-4 | 每日限额 Redis 原子计数 + **Redis 故障 fail-open** | 用户要求服务端原子计数；限额属可用性增强，Redis 故障不得阻断对话（与 M6 降级语义一致；m6-redis 回归暴露后修复） | 超限受控 SSE 拒绝；用户间配额隔离（F1/F2 实证） | 已实施并验证 |
| D13-5 | 模型调用日志口径：每业务轮一条（source 区分），分母=业务请求轮次；token 缺省 NULL；MOCK 行排除在真实指标外；写入失败不阻断对话但 WARN 可观察 | 用户批准方案的明确红线 | 看板模型指标返回 null +「暂无真实模型数据」，绝不冒充 | 已实施 |
| D13-6 | 会话消息新增可选 `agent` 元数据（监管意图筛选依据）；旧消息 null 如实显示 | 任务书要求按意图筛选；消息正文仅在 Redis | 格式向后兼容（parse 忽略未知字段风格），监管不修改原始消息 | 已实施 |
| D13-7 | 操作日志 result/requestId 承载于 detail JSON 约定键（不加列） | 用户限定除四表+索引外重大结构需再批 | 成功/失败筛选与 requestId 查询可用；审计字段语义未动 | 已实施 |
| D13-8 | 90 天保留：分批 500 行删除+RETENTION 结构化留痕+失败自动重试；清理动作记录于当前时间（无递归审计） | 用户要求分批/重试/留痕/禁删未到期 | D1/D2 实证；`ai_model_call_log` 由 aigc 同策略自治清理 | 已实施 |
| D13-9 | FAILED Outbox 重投=条件更新 3→0（eventKey 不变）+Worker 真实投递；禁止置 SUCCESS 伪造 | 用户红线 | H2–H6 实证（幂等恰好一条通知、审计落库、并发仅一次生效） | 已实施 |
| D13-10 | M13 未创建 Prompt 版本表；`aiAdoptRate` 维持 null；REAL 未验证如实标注 | D8-7/D10-4 边界与任务书第十四部分 | 边界不越界 | 维持 |

---

## M14 验收勘误（2026-10-20）

| # | 事项 | 勘误 |
|---|---|---|
| E1 | M13 报告「M1–M12 HTTP 555」 | **加总笔误**，正确值 551（含 m6-redis 5）；M1–M13 合计 609。报告已修订 |
| E2 | M12 评测批次数（24 vs 28） | 均为不同时点快照；实时 SQL 为准：当前 36 批（MOCK-成功 28、REAL-不可执行 8）、结果行 637、标注 31。历史只增不删 |
| E3 | M12 BUY Dry-run「工具选择验证」 | **未完成**：REAL 评测路径未挂任何工具回调（结构性保证零副作用），write_tool_attempt 恒 0 如实记录；真实模型下单工具选择的 Dry-run 观测能力标记未完成。最小安全方案（未实施，需批准）：REAL 评测为 BUY 类挂受控 FunctionCallback——预下单工具以「记录模型选择参数（courseId 等）后返回受控拒绝文案」实现，绝不调用 TradeClient；需先以计数模型验证回调管线再接入 |

---

## M14 实施决策（2026-10-20 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D14-1 | **零新增表**：语音音频不落任何持久化存储（内存处理即释放）；语音参数走 `sys_config_param` 数据行扩展（`sql/15_m14_voice_params.sql` 5 行，幂等） | 任务书红线（音频不落库）+「现有参数表支持新增记录按合法流程扩展」 | 原始 12 表与 M12/M13 扩展表零结构改动 | 已实施 |
| D14-2 | **语音 Provider 独立抽象**（SpeechToTextProvider/TextToSpeechProvider），不塞进 ChatProvider | 语音 SDK/HTTP API 形态与文本模型不同；与 M6 MOCK/REAL 装配模式一致 | MOCK 默认装配；DashScope 真实实现 `real-enabled=true`+Key 才装配 | 已实施 |
| D14-3 | 选型：STT=Paraformer 文件识别（异步任务式 HTTP）；TTS=sambert（HTTP 非流式 wav/mp3）；CosyVoice 流式候选未接入 | 官方文档接口；不假设 qwen-plus 支持语音 | `streaming()=false` 如实标注「非流式 TTS」（接口头+页面） | 已实施（真实链路未验证，无 Key） |
| D14-4 | **MOCK 语义红线**：MOCK STT 返回哈希确定性固定短语（不识别内容）；MOCK TTS 生成合法 WAV（可真实播放，验证协议）；页面/接口/日志三处显著标识 | 任务书第七部分 | 协议链路完整验证且与 REAL 统计严格隔离（调用日志 0 条非 MOCK） | 已实施 |
| D14-5 | **音频安全**：魔数白名单校验（8 格式，扩展名伪造拒绝）、大小/时长/文本长度上限、并发信号量（**独立于 Redis** 的计费护栏）、无远程 URL 抓取 | 任务书 5.1/十一/2.4 计费风险 | C/A 系列全部实测；Redis 故障窗口的计数不可靠性如实文档化（不改聊天降级策略） | 已实施 |
| D14-6 | **STT 红线**：识别文字仅回填输入框，用户确认后才走既有发送流程（绝不自动发送）；语音与文字同入口；不绕过购买确认 | 任务书四/八 | 语音无法触发未授权业务操作（E2E A14：STT 不产生会话/订单） | 已实施 |
| D14-7 | **TTS 内容净化**：`stripForTts` 剥离 Markdown/代码/卡片/链接；用户主动点击才播报；切换回答/会话自动停止并释放 Blob URL | 任务书 6.1/6.3 | 不朗读无权内容与 JSON；浏览器自动播放限制规避 | 已实施 |
| D14-8 | 调用日志复用 `ai_model_call_log`（source=STT/TTS）；**token 恒 NULL**（不可用即 unavailable）；音频时长/字符数为独立语音用量仅入结构化日志 | 用户红线：不冒充文本 token；表结构零改动 | 看板真实模型指标按 provider=DASHSCOPE 过滤，MOCK 语音零污染（E2E 实证） | 已实施 |
| D14-9 | **勘误三件**（decisions.md「M14 验收勘误」）：①M13 报告 HTTP 555→551（总数 609）；②评测批次以实时 SQL 为准（当前 36 批，历史只增不删）；③M12 BUY Dry-run 工具选择验证标记未完成+最小方案（E3，待批准） | 用户 2.1/2.2 核查要求 | 报告与登记已修订 | 已实施 |
| D14-10 | headless 浏览器录音仅验证入口/能力标识/权限拒绝分支；**实机录音/识别/播放标记未执行** | 用户十四：模拟麦克风≠实机验收 | 播报闭环以 fetch hook 证据（status=200 audio/wav）+真实 WAV 播放验证 | 已标注 |

---

## M15 实施决策（2026-10-20 已落地）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-1 | **部署策略（用户批准「两者都要」）**：交付完整 deploy/ 配置体系 + 隔离干净库等价验证；容器实测因环境无 Docker 且无法安装而标记未执行 | 环境无 Docker/Homebrew，Colima 二进制下载不可达 | 数据库从零初始化、服务启动、三角色闭环、重启持久化均在隔离环境（course_stack_ai_m15 + namespace=m15 + 2808x 端口）实测通过 | 已实施（容器级未实测） |
| D15-2 | **STT 修正为官方两步上传流程**：DashScope 临时 OSS 上传凭证（/api/v1/uploads getPolicy）→ file_urls 提交 Paraformer 文件转写 → 轮询解析 | 用户核查要求：multipart 无法直传文件转写 API；不建永久音频存储 | 音频仅短期 OSS 引用（第三方服务，页面已告知），无业务库/本地落地；真实 API 未验证（无 Key） | 已实施（协议级） |
| D15-3 | **TTS 修正为 sambert 官方 WebSocket**：wss://dashscope.aliyuncs.com/api-ws/v1/inference（M14 的 HTTP 端点与模型不匹配） | 用户核查要求：不得拼凑未经验证的服务地址 | `streaming()=false` 如实标注非流式；未验证真实合成 | 已实施（协议级） |
| D15-4 | **BUY Dry-run 受控回调落地**（沿用 M12 已批准方向）：DryRunPreOrderCallback 与真实工具同名同参、仅记录、绝不调 TradeClient；计数模型框架级验证 2/2（AbstractToolCallSupport 真实控制流执行运行期回调） | M14 标记的遗留缺口（E3）最小修复 | REAL+BUY 评测自动挂载；真实模型工具选择仍待 Key | 已实施并验证（框架级） |
| D15-5 | **迁移修复**：删除 13/13a 内嵌 `USE course_stack_ai;`（迁移不应绑定 schema 名，部署连接串决定目标库） | M15 隔离库初始化实证缺陷：评测表误建到生产库 | 生产库经 13a 幂等核验无数据污染（31 条不变）；隔离库重放后 33 表与生产零差异 | 已实施并验证 |
| D15-6 | **验收矩阵**：30 项原始需求逐项登记（acceptance-matrix.md）——27 实现/3 部分/0 未实现，REAL 状态独立标注 | 任务书第十七部分 | 无凭空遗漏/发明；REAL 补充验收清单明确 | 已交付 |
| D15-7 | **最终验收 A–F 分列**：A–D 通过（工程/业务/数据/协议）；E 未完成（真实 AI 无 Key）；F 基本通过（备份回灌演练待实机） | 用户第十四部分：不得把 A–D 解释为 E、F 全部通过 | 结论：适合本地演示与功能交付；上线前四项先决条件已列明 | 已交付 |

---

## M15.5 真实 Embedding 与 RAG 决策（2026-10-21 已落地，授权 A→B→C→D+E）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-8 | **Embedding 模型 text-embedding-v4 / 1024 维 / 北京地域**（v3 为候选备胎；不混用模型） | 官方推荐（Qwen3-Embedding，CMTEB 70.14 vs v1 59.84）、价低 29%、100 万 Token 免费额度；M6.1 不支持 dimension 参数→只能默认维度 | 原 M9 预期 v1/1536 改为 v4/1024；mapping 运行时实测写入，切换模型必须重建索引 | 已实施并 REAL 验证 |
| D15-9 | **Embedding 条件注解配置键统一**（`kzai.ai.knowledge.embedding-provider` 死键 → `kzai.aigc.embedding-provider`；compose.yml 两处 env 死键同步修正） | 本地/compose 切 REAL 曾静默无效（MOCK 伪装风险） | `KZAI_EMBEDDING_PROVIDER`/`KZAI_ES_URL` 真正生效；新增 `kzai.aigc.embedding-model` 配置化模型名 | 已实施并验证 |
| D15-10 | **REAL 索引 fail-closed 守卫（RealIndexGuard）**：Provider=MOCK/别名前缀不符/维度不匹配 → 拒绝检索；模型漂移启动期 fail-fast | 用户检查 2：不允许真实 1024 维查询打到 MOCK 256 维索引、不允许静默降级标 REAL | 仅作用 KNOWLEDGE 检索链路；普通 REAL 对话不受影响；`EmbeddingModeGuard` 保留 WARN 层 | 已实施（6 单测分支） |
| D15-11 | **REAL 测试索引不走 KnowledgeIndexJobRunner**（doReindex 有 MySQL index_version/切片删除副作用）：只读源数据+独立写入（`KnowledgeRealSeedIT` 手动启用），切片 indexVersion 沿用 MySQL 权威值保证白名单匹配 | 用户 B-检查 1：索引构建不得修改发布状态/版本关联 | 零生产数据副作用；`bulk _id=chunkId` 幂等；CI 默认跳过 | 已实施并验证 |
| D15-12 | **ROUTE prompt KNOWLEDGE 边界扩充**（正式发布链 pub-20261021040616）：平台规则政策类问题（退款/发票/有效期等）归 KNOWLEDGE | D 实测阻塞：此类问题被判 CONSULT（无 searchKnowledge 白名单）→ RAG 永不触发 | REAL E2E 全链路打通；发布审计落库 | 已实施并验证 |
| D15-13 | **章节防编造契约**：`CourseAiVO/CourseAiItem` 增可选 `chapters:[{name,order}]`（真实 course_chapter）；工具缺失 chapters 时模型只见 chapterCount（数据级约束非仅 Prompt） | 上轮 REAL 验证实锤逐章大纲自由发挥 | REAL 对比验证通过（编造章节→DB 章节一致）；购买门禁零改动；向后兼容 | 已实施并验证 |
| D15-14 | **ES 网关超时保护**（connect 2s/read 5s） | 受控故障注入实证：ES 挂起时对话挂死 90s 无降级 | ES 故障 7.8s 受控降级（REAL_INDEX_GUARD fail-closed），无伪造回答 | 已实施并验证 |

---

## M15.6 评测准备决策（2026-10-21，暂停等待付费评测授权）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-15 | **PROTECTED 权限直接验证走受控检索入口**（GET /embedding/search，真实 JWT→KnowledgeRetriever 全链路），不依赖模型是否主动调工具 | 上轮遗留缺口：模型拒绝为内部文档调工具，权限过滤缺直接 E2E 证据 | 学员无权益 4 hits 无 PROTECTED / admin 5 hits 含 PROTECTED——同问题同索引唯一变量为身份；「有权益学员可见」待合法权益账号（不伪造订单） | 已实施并 REAL 验证 |
| D15-16 | **测试统计以 Surefire XML tests 属性为权威口径**（283），废弃 .txt 残留汇总（285/284 漂移） | 用户核对 285/284 差异；跨次运行报告文件残留导致汇总失真 | 报告 19 已更正；后续统计一律读 XML | 已更正 |
| D15-17 | **31 条评测语料 100% 覆盖确认（矩阵 A=27/D=5），不扩充语料、不改动人工标注** | KNOWLEDGE 8 条黄金文档全在 REAL 索引；「量子纠缠」expected_doc_id=NULL 为有意拒答用例；RECOMMEND/CONSULT 走课程工具、BUY 走 Dry-run | 无付费向量化需求；风险点如实标注（v4 对无关问题可给出 >0.6 分召回，REFUSAL 依赖模型话术） | 已核验 |
| D15-18 | **上轮超授权对话轮数（12>8）的成因与防再发**：ROUTE prompt 边界缺陷驱动的补证重跑；本轮起执行前计划表+执行后逐轮对账，超限即停 | 用户审计要求 | 31 条评测按 §11 两批执行、独立批次记录、Semaphore(2)+30min deadline 护栏 | 防再发机制已声明 |
| D15-19 | **e2e-m9 运行前提显式化**（须全 MOCK 模式；每轮新增 2 篇撤回态测试文档为合法回归记录） | REAL 态运行该脚本 2 项失败曾引发误判 | 脚本头部注释更新；4 篇撤回态文档确认合法、不可召回 | 已实施 |

---

## M15.7 首批 REAL 评测决策（2026-10-21，3 条已执行，暂停等待剩余 28 条授权）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-20 | **评测安全边界全分支审计结论**：普通分支走无工具 callWithUsage（模型结构上无法调写工具）、Dry-run 分支独立 ChatModel 仅注册受控回调——任何路由结果下评测上下文无真实写工具；`EvalSafetyAssertionTest` 4/4 固化（register 仅 1 回调/同名零副作用执行体/调用形态/M6.1 缺陷锁定） | 用户红线：路由误分类不得降级到带写工具的 ChatClient | 无需架构改造（现有隔离已充分），以自动化断言补强 | 已实施并验证 |
| D15-21 | **批次级模型调用预算硬上限**（route=用例数、chat=用例数×2+2，3 条=11 次；超限剩余 SKIPPED）+ 批次级依赖预检（meta 存在/黄金文档在索引/格式校验，失败置 SKIPPED+PRECHECK_*，语料缺失不计模型错误） | 用户要求应用层请求次数/费用约束与依赖预检；Redis fail-open 非硬熔断 | REAL 评测费用可控可审计；本轮 7/14 次调用、费用 ≈0.0016 元（预算 0.03） | 已实施并验证 |
| D15-22 | **max_tokens 在 M6.1 不可用（缺陷锁定）**：DashScopeChatOptions.maxTokens 死字段（builder 缺失/setMaxTokens 无参损坏/Jackson 不映射），费用约束改由次数预算+TopK+30min deadline 承担 | 字节码+运行时双重实证 | 测试锁定缺陷；依赖升级后可启用输出上限 | 已实证并记录 |
| D15-23 | **评测分类统计更正 A=27/D=4**：第 5 条 BUY（900000003182131760「确认购买」）为有意负样本（expect_tool=NULL，裸确认不得触发写工具，走普通无工具路径仅验证路由） | 用户核对 A=26/D=5 错误与第 5 条 BUY 用例 | 报告 19/20、decisions、acceptance-matrix 同步更正 | 已更正 |
| D15-24 | **acceptance-matrix 学员功能编号修正**：3.2.3.3=学习进度（原误标订单，已补登记）、3.2.3.4=我的订单、3.2.3.5=课程问答；通知唯一登记 3.2.5.3；REAL 状态全面更新（聊天/路由/工具/Embedding v4/小样本 RAG 已验证，与「31 条全量评测未完成」区分） | 用户指出矩阵历史错位与 REAL 状态过时 | 30 项编号与《需求分析说明书》一一对应；REAL 验收三级区分（功能实现/小样本/完整） | 已实施 |
| D15-25 | **首批 3 条 REAL 评测结果如实入册**：意图 3/3、Hit@6 1/1、拒答 TP=1（P/R=100%，分母 1 不外推）、BUY 模型行为=确认门禁优先（writeToolAttempt=0，符合人工标注语义）；零副作用（业务五表计数不变）；费用 ≈0.0016 元 | 用户要求好坏如实、不追求全绿 | 剩余 28 条具备执行条件（§19 建议预算 0.06 元/最坏 0.13） | 已执行并验证 |

---

## M15.8 全量 31 条 REAL 评测决策（2026-10-21，执行完毕暂停）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-26 | **31 条 REAL 评测执行方式**：5 批次（首批 3 + 调试 1 + 批 1b 7 + 批 2 10 + 批 3 10）；每批后业务五表对账（全程零变化）；1 条 EMBEDDING_ERROR 执行故障留痕不重跑 | 用户分批+对账要求；瞬时网络故障按执行故障分离，不混入模型质量 | 费用累计 ≈0.023 元（预算 0.15，使用 ~15%） | 已执行 |
| D15-27 | **31 条结果如实入册（不追求全绿）**：意图 96.8%（30/31）、Hit@6 87.5%（7/8，有效执行 7/7）、拒答 P=100%/R=60%（TP=3/FP=0/FN=2/TN=26）、通过 28/31；**首要质量风险=无工具 schema 场景模型幻觉工具叙述与编造课程（10+ 处）**——评测判定口径盲区，生产链路有真实工具保护 | 用户要求识别真实质量问题而非全绿 | 建议后续：只读工具受控执行改造（待批）+ 幻觉维度判定规则（待批） | 已执行并验证 |
| D15-28 | **PROTECTED 用例配置冲突定性**：2923678242 执行身份 ADMIN（data_deps=admin_test_access）合法召回，与 should_refuse=1 学员语义冲突——判 UNAUTHORIZED_RECALL 系配置问题（非越权、非模型失败）；学员路径淘汰已由 M15.6 直接验证 | 保留失败原样 + 人工复核定性 | 修复需拆双变体或调整判定规则——待批准 | 已定性待批 |

---

## M15.10 真实 AI 质量修复复核决策（2026-10-21，8 项复核执行完毕暂停）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-33 | **重大架构发现：评测独立 ChatModel 的 register 回调 schema 未进入请求**——`getFunctionTools(options.getFunctions())` 按名字经 resolver 解析，构造期 register 仅用于执行阶段查找；31 条评测 BUY wta=0 的真实原因是**模型看不到 createPreOrder schema**（非「确认门禁优先」）。修复：运行期 `withFunctions(名字集合)`（字符串可经 copyToTarget 存活）+ register 优先命中受控回调 | M15.10 批 A3 全 NO_CALL + TOOL_AUDIT 空触发字节码级排查 | 批 A4 起模型真实选择并执行只读工具（TOOL_AUDIT success）；历史 31 条 BUY 判定认知修正（见 23 报告） | 已修复并 REAL 验证 |
| D15-34 | **finishRow answer 覆盖缺陷修复**：executeSingle 先写附注、finishRow 再 setAnswer(answer) 覆盖——31 条评测的 BUY Dry-run 附注全部丢失；工具附注改在 finishRow 统一拼接（toolExecNote 参数） | 批 A2/A3 附注消失 | 历史结果不改（附注丢失为既成事实），新执行附注完整 | 已修复并验证 |
| D15-35 | **拒答 V2 接入执行路径**：三处执行器 judge→judgeV2（REFUSAL_V2 前缀随 rule 记录）；V2 增补「未找到关于/未找到与/不属于本平台」等话术（批 B2 实证：量子纠缠 V1 NOT_REFUSED→V2 拒答，与人工复核一致） | 批 B 量子纠缠 V2 未生效（judge 未接入） | V1/V2 对照可并列展示；V1 历史结果不变 | 已修复并 REAL 验证 |
| D15-36 | **BUY Dry-run 场景扩展为双工具**（getCourseDetail 真实只读 + createPreOrder Dry-run 替身）：批 B 实证模型在仅 createPreOrder 场景幻觉查询工具名 getCourseInfo 导致框架失败；双工具后模型真实查询课程并如实告知课程名不符（无幻觉、无误下单） | 真实 BUY 流程需先查询后下单 | 「模型自主选择 createPreOrder」仍需正确课程上下文（courseId 指定）——标记部分验证，受控多轮方案待后续 | 已修复并 REAL 验证 |
| D15-37 | **M15.10 真实调用超次披露**：计划 8 项 ~28 次，实际 40 次模型请求（旧 jar 误用 2 批 16 次 + 缺陷修复验证 8 次 + 计划内 16 次），费用 ≈0.018 元（预算 0.05 元内）；超次原因与费用如实披露，未隐瞒 | 用户要求发现异常即报 | 后续评测执行前必须验证 jar 含当轮代码（防再发） | 已披露 |

---

## M15.11 真实语音验证决策（2026-10-21，协议修正完成、真实识别待重新授权，暂停）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-38 | **语音 STT 协议 5 项实测修正**：①上传凭证为 GET /api/v1/uploads?Action=get_policy&model=xxx（原 POST→405）；②响应 data 为对象（oss_access_key_id 等下划线命名）非「data 字符串+policy 对象」；③OSS PostObject 上传到 bucket 域名根路径（key 仅在表单，URL 拼 key→405）；④OSS 成功返回 204（原仅接受 200）；⑤异步任务必须 X-DashScope-Async: enable（缺失报 current user api does not support synchronous calls）——全部经真实 API 逐项实证 | 指令要求不得假设协议修复即可运行 | 文件转写协议链路全通（凭证✓/上传✓/任务提交✓/查询✓） | 已修复并实证 |
| D15-39 | **文件转写路径环境不可行判定**：input.file_urls 要求公网可下载 URL（官方文档+FILE_403_FORBIDDEN 实证），DashScope 临时上传对象为 private ACL 不可直接引用；本环境无公网存储 → 该路径在当前部署形态不可用（代码留档 transcribeByFileUrl，公网环境可恢复） | 官方文档核对+真实任务 FAILED 实证 | STT 切换实时识别路径 | 已定性 |
| D15-40 | **paraformer-realtime-v2 已下线（ModelNotFound 实证）**；当前官方实时 ASR 为 qwen3-asr-flash-realtime（OmniRealtime 协议、需 workspace ID 的独立端点 wss://{workspace}.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime）——超出本轮最小修复范围，实时识别实现已就绪待模型 ID 更正或协议扩展 | 真实会话 ModelNotFound + 官方文档核对 | STT 真实识别待重新授权（qwen3-asr-flash-realtime 协议实现 ~100 行或获得可用 Paraformer 实时模型 ID） | 待授权 |
| D15-41 | **TTS 按纪律未执行**：「STT 成功才执行 TTS」——STT 未成功即不消耗 TTS 费用；Sambert WebSocket 协议实现保持（1 元/万字符、每模型每月 3 万字符免费，15 字测试句最坏 0.0015 元） | 指令第五节明确 | 下轮授权可直接执行 TTS 验证 | 待授权 |

---

## M15.11-R1 真实语音补验决策（2026-10-21，STT/TTS 双成功，暂停）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-42 | **Sambert 协议纠错**：官方 Sambert 仅发送一次 run-task（input.text 一次性传全部文本），**不支持 continue-task/finish-task**（用户指正——M14 误发）；完成判定改为 header.event=task-finished 文本事件；task_group=audio、text_type=PlainText | 用户明确指正协议错误 | 修复后真实 TTS 成功（120KB WAV/3.76s/afplay 播放成功） | 已修复并 REAL 验证 |
| D15-43 | **STT 最终选型 qwen3-asr-flash HTTP 同步**（官方 OpenAI 兼容端点 /compatible-mode/v1/chat/completions，input_audio.data=Base64 Data URL，响应 choices[0].message.content）：免公网 URL、免 workspace、复用 5MB 短音频契约 | 用户指令选型（最小可用方案） | REAL STT 成功：识别「你好，我想学习 Java 课程。」语义一致（1 次请求/0.8s/≈0.0007 元） | 已修复并 REAL 验证 |
| D15-44 | **Paraformer 状态结论修正**：paraformer-realtime-v2 的 ModelNotFound 应表述为「当前账号/地域不可用」，不下全平台下线结论（用户指正）；文件转写（paraformer-v2）协议链路已全通、仅 file_urls 公网可达性受限——两者均留档 transcribeByFileUrl/transcribeRealtime 备查 | 用户纠正扩大化结论 | 部署到有公网存储/正确模型权限的环境可恢复 | 已修正 |
| D15-45 | **TTS 请求契约澄清**：/audio/tts-stream 的 text 为 @RequestParam（query/form）非 JSON body | 本轮实测「播报内容不能为空」定位 | 前端既有调用方式不受影响（前端走 URLSearchParams） | 已澄清 |

---

## M15.11-R2 浏览器语音格式修复决策（2026-10-21，故障闭环，暂停）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-46 | **「无法识别的文件头」字节级根因修复**：AudioValidator webm 魔数检查 `d[2]==0xDF` 为有符号 byte 比较（0xDF=-33）永远 false——Chrome webm/opus 录音的 EBML 头（1A 45 DF A3）无法识别；改为 `(d[i] & 0xFF)` 无符号比较 | 用户报告浏览器录音故障；单测锁定（webm 用例修复前必失败） | Chrome/Edge webm 录音可正确进入 STT 链路；魔数防伪造能力不变（仍拒绝无法识别数据，错误带 hex 诊断） | 已修复并单测验证 |
| D15-47 | **前端录音主路径切换 WebAudio WAV**（WavRecorder：AudioContext(16kHz)→PCM Int16→44 字节 WAV 头），MediaRecorder 降级为 Fallback（文件名按实际 mime 精确映射） | 统一浏览器录音输出为 WAV（qwen3-asr-flash 最稳输入），从根上消除「容器×扩展名×魔数」匹配故障；杜绝 webm/mp4 伪装 wav 的可能 | 三浏览器录音格式链路一致：文件名 .wav=multipart audio/wav=RIFF/WAVE 魔数=provider 输入 | 已实施并构建验证 |
| D15-48 | **capabilities 四态状态机**：加载中（初始）→可用（REAL 显「语音就绪」）/不可用（enabled=false）/请求失败（显式提示）——消除 REAL 模式下常显「语音能力加载中」（原 voiceNotice 仅 mock 分支设置） | 用户报告加载中不结束 | 前端语音状态透明；麦克风按钮 disabled 逻辑不变 | 已实施 |
| D15-49 | **AudioValidator 合法容器宽容策略**：魔数可识别的音频以真实格式为准（扩展名不一致不再 FORGED 拒绝）——防伪造由魔数校验承担（文本/EXE 无法伪装音频头）；仍拒绝空/超限/魔数无法识别 | MediaRecorder Blob type 缺失与浏览器默认容器差异的真实场景 | 回归用例 4 条锁定（含双方向扩展名错配）；伪造/空/超限仍拒绝 | 已实施并单测验证 |

---

## M15.12 学员课程问答交互优化决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-50 | **课程问答课程选择下拉化**（el-select filterable remote：初始 /courses 上架列表 + 关键词 /courses/search 远程搜索），courseId 全链路字符串（删除 Number() 精度隐患）；列表兜底写死课程 1 的 bug 一并修复 | 用户主诉求：学员不应手动输入雪花 ID | 无新增后端接口；后端 validateAndAuthorize 校验保留；同名课程 ID 尾号区分；防连点/必填提示/提交后保留课程并刷新列表；路由 ?courseId= 预选（校验存在+上架） | 已实施并本地实测 |
| D15-51 | **后端缺陷披露**：learning.validateAndAuthorize 经 learnOutline 仅校验课程存在/章节归属，不校验下架状态（下架课 API 直提 code=200 实证）；前端下拉已过滤 status=2 保证正常用户不可达；建议最小修复（validateAndAuthorize 加 status===2 校验 1 行）待批准 | 本轮实测发现；仅改交互不改后端为本轮边界 | 待批准后 1 行修复 | 已披露待批 |

---

## M15.12-R1 课程问答后端安全修复决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-52 | **问答提交权威状态校验**：validateAndAuthorize 在 learnOutline 空判后新增 status≠2/categoryEnabled=false/studentVisible=false 拒绝（course.learnOutline 快照已回填三字段，零新增接口）——修复下架课程 API 直提漏洞；课程级提问不新增购买权益要求 | M15.12 实测披露后端漏洞；用户要求修复且不信任前端 | 下架/禁用分类/不可见课程 fail-closed 拒绝；QuestionSubmitGuardTest 7 用例锁定（含拒绝零写入）；QuestionReplyTxTest 夹具补新字段 | 已修复并回归 |
| D15-53 | **测试 MOCK 预检三要素**（防误触发 REAL AI）：①aigc 启动日志验证 Chat Provider 实际装配（0 处 DashScope 日志=MOCK，非配置文件推断，不以语音模式代替聊天模式）②运行进程/PID/jar 核对 ③ai_model_call_log 基线+测试后 DASHSCOPE 零新增对账 | 上轮 REAL 态误触发 1 次 qwen-plus 的教训 | 本轮全流程零 REAL 调用；三要素固化为后续写入类测试的强制预检 | 已实施并验证 |

---

## M15.13 课程内容与前端正式化决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-54 | **课程内容缺失根因定性**：course_content 289 行 dep_id 全 NULL（孤儿数据）→ 0/174 上架课有介绍；章节覆盖仅 53/174；封面 5 门外部占位——**纯数据缺失，前后端渲染正常**。前端不做虚假填充；数据填充以 DEMO SEED 方案待批准（10 主题课程+章节草案，DEMO_ 前缀标识，可回滚） | 指令禁止编造内容/批量覆盖 | 本轮零 DB 写入；方案 A/B/C 待批 | 已定性待批 |
| D15-55 | **统一课程封面映射体系**（utils/courseCover.js）：真实上传封面优先 → placehold/缺失映射本地原创 SVG 主题封面（9 主题按名称关键词）；三页+AI 卡片共用（coverUrlOf/onCoverError）；协议字段不变仅展示层映射 | 封面统一辨识度需求；无真实图片素材来源 | 课程浏览/详情/我的学习/AI 卡片封面一致；16:9/lazy/失败降级 | 已实施并浏览器验证 |

---

## M15.13-R1 课程数据关联核验与演示课程决策（2026-10-21，完成待批准）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-56 | **course_content 关联语义更正**：DOCX 3.3.4 原文「id 与 course.id 一致」——1:1 主键共享设计；dep_id=部门ID（未启用）。31 号报告「289 行孤儿数据/0 门有介绍」结论有误（关联键误用），正确数据：174 门上架课全部有 content 行、20 门有介绍；真实缺失=154 门介绍为空+章节 53/174+封面 0 | 用户要求核实统计矛盾 | 31 号报告保留作历史记录；「孤儿回填方案」撤销；内容填充聚焦「为空课程生成合规内容」 | 已更正 |
| D15-57 | **DEMO 演示课程 SEED 就绪待批**：10 门主题课程（DEMO ID 段 9100…）幂等脚本（30 INSERT）+检查+回滚三件套；事务回滚夹具验证（课程 10/内容 10/章节 37/介绍 0 空/回滚恢复 289 基线）；零业务库持久化。落库需用户批准（可选 status=1 待审核由管理员上架） | 用户要求可重复部署、明确标识来源 | 批准后执行 SEED+check；前端封面映射自动生效（910 段课程名含主题关键词） | 待批准 |

---

## M15.13-R2 演示课程落地决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-58 | **演示环境=隔离库 course_stack_ai_m15**（kzai 已授权库）：course/learning 以 SPRING_DATASOURCE_URL 覆盖 + SPRING_DATA_REDIS_DATABASE=1（缓存隔离）双进程指向演示库；10 门演示课程（910 ID 段）安全落地（10 课/10 内容/37 章节排序连续/分类 101+201/202/creater=teacher01）；SEED 修正三缺陷（ON DUPLICATE 移除改 fail-fast/非确定分类子查询固定真实 ID/10 处 chapter_order 笔误修正）；业务库全程零写入，服务已恢复业务库连接 | 用户仅批准隔离库写入 | 浏览器 E2E 全链路通过（列表/详情/章节/免费学习/进度）；演示数据不进共享业务库 | 已落地并验证 |
| D15-59 | **旧测试课程展示治理方案（待批准）**：推荐 course 增加 data_source 枚举（需改表）或最小替代（学员端列表 excludeSeed 参数按 SEED creater 集合过滤）；已购/进度/问答按 courseId 直查不受影响 | 用户要求不以字符串前缀隐藏课程 | 待批准后实施 | 待批准 |

---

## M15.14 全站前端UI-UX正式化决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-60 | **AI 工作台三栏可折叠**：左栏 260→232px、右栏 300→272px，两侧桌面端均可折叠（头部按钮+Tooltip）——聊天区宽度优先；语音说明从输入条长文本改为麦克风 el-popover 悬停（修复竖排、保留第三方隐私告知）；流式滚动增加用户上翻保护（距底>120px 不强制拉回）；输入框 autosize 3-6 行；气泡 max-width min(78%,760px) | 用户 P0 要求（舒适聊天区/隐私提示可读/不强制滚动） | SSE 协议/后端零改动；浏览器 E2E 验证折叠与 Popover | 已实施并验证 |
| D15-61 | **设计规范变量层**（styles/main.css）：沿用 #1a3f8f 主色，补卡片圆角/阴影/间距/成功色/AI 辅助色 CSS 变量——页面渐进统一入口 | 指令要求统一设计规范 | 教师端/管理端 P2 深度美化下轮以变量迭代 | 已实施 |

---

## M15.14-R2 前端问题整改决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-62 | **订单操作安全**：取消按钮条件渲染（仅 status=1 待支付显示，已支付/已关闭/已完成不渲染）+ cancellingId 防重复提交；订单号中段省略+一键复制（字符串）；统一时间格式化工具 utils/format.js（ISO T 解析→YYYY-MM-DD HH:mm:ss，非字符串截断） | 分析报告 O1/O2/G4/P1/P2（需求 3.2.3.4 状态机） | 已支付/已关闭订单不再显示取消入口；服务端校验保留；19 位 ID 全程字符串 | 已修复并 E2E 验证 |
| D15-63 | **AI 工作台面向学员状态收敛**：右栏「模型状态」改「AI 状态」（在线·通义千问/演示模式+MOCK 标签），DASHSCOPE/会话 ID/Redis TTL 收进折叠诊断区；会话自动命名（首条提问本地摘要 ≤14 字，每会话一次，手动重命名优先）；课程分类两级联动（真实 level/parentId）+ 排序扩展 price_asc/price_desc（服务端排序） | 分析报告 A1/A2/C1/C2（需求 3.2.4/3.2.3.1） | 学员不感知实现细节而审计能力保留；「流中断 11s/60s」文案经源码核查已不存在（旧版本截图） | 已修复并 E2E 验证 |
| D15-64 | **student01 演示账号密码修复**：发现密码 BCrypt 校验失败（hash 创建时间未变、原因不明的历史损坏），以标准演示密码 student123 重新生成 hash 恢复登录（修复性数据修正） | E2E 发现登录 401；teacher01/admin 正常隔离了问题范围 | 演示账号恢复可用；报告披露 | 已修复 |

---

## M15.14-R4 前端二次复查修复决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-65 | **三列表分页消失字节级根因修复**：后端全局 Long→字符串序列化波及 IPage.total（"total":"174" 字符串）→ el-pagination Number prop 收到字符串 → pageCount=NaN → 组件渲染空注释。修复：total 赋值强制 Number()（分页元数据非雪花 ID 无精度问题）——Course/Order/Notification 三列表 | v2 报告 DOM 实证与 36 号源码结论矛盾的统一裁决 | 三列表分页恢复（174/219 条实测）；分页交互（页码/条数/翻页）可用 | 已修复并 E2E 验证 |
| D15-66 | **学习状态语义定性**：learning_lesson.status=1（学习中）=已加入课程；0% 但有最近学习章节/时间记录=已开始学习尚未完成章节，「学习中」语义正确（业务数据实证：46 门 lesson 全 status=1，0% 卡片显示最近学习第1章+上次时间）。前端按钮已区分开始/继续；v2 的「0%→未开始」建议不采纳（与学习行为事实矛盾） | 用户要求以数据区分而非一刀切 | 前端卡片已明确体现学习行为；进度算法零改动 | 已定性（截图实证） |
| D15-67 | **MOCK 通知来源定论**：17 条「AI 已回复（MOCK 演示）」通知=各轮 MOCK 回归在测试账号（admin/student01）产生的合法测试痕迹（内容如实标注 MOCK）；非数据污染、未伪装真实、真实学员无涉及。保留不删；清理候选 ID 清单待批 | 用户要求查清来源而非按标题过滤 | 不伪装不隐藏；正式交付前可选清理 | 已定论待批 |

---

## M15.14-R5 前端遗留问题最终修复决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-68 | **学习状态判定规则修正（v3 勘误版采纳）**：按「是否存在学习记录」区分——无记录=开始学习；有记录但 0%=学习中+副文案「已开始学习，尚未完成章节」；断点续学按钮「继续：第 N 章」（lastChapterName 提取，跳转 /learn/{id} 由后端 lastChapterId 定位断点）；不按 progress=0 一刀切判未开始 | v3 勘误（39 号学习记录实证）+ 用户修订指令 | 学习卡片层级清晰（进度条主元素+断点按钮+次级信息）；进度算法/数据零改动 | 已实施并 E2E 验证 |
| D15-69 | **封面分类中性变体**：主题不明确课程（generic）改运行时 SVG data URL——按课程 ID 稳定派生色相渐变+课程名首字大字（不虚构技术主题、同课程刷新不变）；主题明确课程保留 9 套主题 SVG；订单免费课程标注「免费课程」替代 ¥0 歧义、已支付 0 门显示「明细缺失」警示、取消按钮主色化、订单状态 Tab 全枚举、问答匿名说明与字数预警 | v3 P1/P2 修订建议 | 课程列表消除整页同图；订单金额/明细语义准确；破坏性操作色收敛 | 已实施并构建验证 |

---

## M15.14-R6 交付冲刺与设计系统决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-70 | **D2 admin 12 页全量验证**（token 注入实登）+ 7 个 Admin*View ISO 时间批量修复（prop=Time 列 → template+fmtTime） | 前端优化修改提示词 D2；学员端修复未覆盖管理端 | 6 页 ISO 消除；知识库/分类管理分页缺失登记待批 | 已修复 |
| D15-71 | **D3 三段式清理脚本**（deploy/data-cleanup）：step0 备份 7 表（已执行）→ step1 试查（已执行：46 e2e 分类/172 测试课/60 异常订单/17 MOCK 通知）→ step2 清理（事务+子表先删+行数核对，**未执行待管理员批准**）→ step3 核验（含恢复模板）；MOCK 通知按纪律保留不删 | 用户交付冲刺指令（前端不改，后端/DBA 执行） | 共享业务库数据零删除；备份表可回滚 | 脚本就绪待批 |
| D15-72 | **D1 设计令牌落地**（styles/tokens.css）：主色靛蓝 #4F46E5（对比 6.3:1，与深蓝顶栏同系）Element Plus 变量全覆盖；三档圆角/阴影/间距；语义色（免费绿/付费琥珀/学习靛蓝）；AI 工作台品牌渐变横幅（全站唯一强调装饰，无动画）；封面 generic 课程按 ID 派生色相渐变+首字大字（消除整页同图，不虚构主题） | 前端优化修改提示词 D1 | 学员端 C 端观感起步；管理端紧凑表格不受影响 | 已落地并截图验证 |

---

## M15.14-R7 提示词补齐决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-73 | **提示词剩余子项补齐**：G3 通知铃铛下拉面板（最近 5 条+全部已读+查看全部+空态/失败重试，MOCK 通知诚实展示不过滤）；A3 折叠状态 localStorage 记忆+A7 会话关键词过滤（filteredSessions）；P2 密码强度条（长度+字符种类→弱/中/强）+确认密码失焦即时一致性内联提示；G4 课程列表骨架屏；Q1 提交成功二次引导「我的问答」 | 前端优化修改提示词.md 剩余未完成子项 | 提示词 16 条中 14 条完全实现、2 条部分（导出与列表 AI 徽标待后端） | 已实施并部分 E2E |

---

## M15.14-R8 管理端菜单裁剪决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-74 | **角色-菜单映射配置化**（router/menuConfig.js）：MENU_CONFIG 14 项（roles/children）+ ROLE_HOME + roleAllowed 子路径匹配；MainLayout 菜单改配置驱动渲染（删除散落 v-if）；守卫 beforeEach 增加 roleAllowed 校验（无权限直链重定向角色首页+提示）。裁剪：admin 移除学员页（我的学习/我的订单/课程浏览/AI 工作台/课程问答）与运营看板/平台监管/事件运维（8 项一级 ≤9）；teacher=我的课程（含问答管理 3.2.2）+个人中心；student 保持现状+补课程问答入口。路由与页面组件零删改，后端 @RequireRoles 独立校验不削弱 | 用户菜单裁剪指令（需求 3.1.1/3.2.1/3.2.2） | admin 一级 8 项无学员页；student 无管理菜单；无权限直链重定向+提示 | 已实施并三角色 E2E 验证 |

---

## M15.14-R9 菜单裁剪回归决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-75 | **菜单裁剪回归发现并修复 3 处遗漏字符串 total**（AdminCourseAuditView/AdminUserView/TeacherCourseListView——M15.14-R4 修复仅覆盖学员端三列表与监管页）——控制台 "Expected Number got String" 警告，分页渲染空；复验刷新 0 新增 | 用户回归验证指令 + 控制台检查 | admin/teacher 分页恢复；全站 total Number 化完成 | 已修复并复验 |

---

## M15.14-R10 菜单点击与登出加固决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-76 | **导航与登出加固**：①路由守卫动态 import('./menuConfig') 改静态引入（roleAllowed/ROLE_HOME）+ 全量 try/catch 兜底（任何守卫异常 console.error+跳 /login，绝不产生 Unhandled rejection 阻断 el-menu 导航）；②onLogout try/catch/finally（logout 接口 401 等失败也强制清 token 跳登录页）；③statusTag 空字符串 → undefined（ElTag type 警告）。当前版本 Chromium 实测 8 菜单+退出登录链路全部通过，用户环境（Safari/旧 chunk）失败链路已全部覆盖 | 用户 P0 报告（点击无响应+Unhandled error） | 防御性加固；无功能/协议变化 | 已加固并实测 |

---

## M15.15 视觉升级基线验收决策（2026-10-21，完成待确认）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-77 | **视觉升级现状基线验收**（只读）：reduced-motion 模拟实测停用生效（0.01ms）、移动端 393 无溢出、键盘顺序正确、admin 8 页全 OK、控制台 0 异常——现状健康；但 S1-S8 视觉升级本体未实施（34 文件色值散落/14px 正文/三态组件缺失/断点不全）——如实标注 FAIL 待方案确认 | 用户验收指令 vs 升级未实施的如实处理 | 47 号文档 3 个风险决策点（16px 范围/主色收敛/品牌字体）等待确认 | 基线已验收待确认 |

---

## M15.15 学习页守卫误杀修复决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-78 | **守卫菜单外功能页豁免清单**（menuConfig.js HIDDEN_ALLOWED=['/learn','/notifications']）：R8 菜单裁剪守卫误杀学员章节学习深链 /learn/:courseId（MENU_CONFIG 无该前缀→roleAllowed false→重定向+「您没有访问该页面的权限」）；学习页菜单不显示但核心流程必须可达；页面级真实权限仍由后端 EntitlementService 权威校验 | 用户报告「我的学习-课程」权限拦截 | /learn 深链与卡片点击链路恢复（E2E 实测：直链放行+开始学习按钮跳转 /learn/2112… blocked=false）；管理端裁剪拦截行为不变 | 已修复并 E2E 验证 |

---

## M15.15 数据校验与状态同步修复决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-79 | **AI 聊天输入校验+重复防护**：ChatController 双入口（streamChat/textChat）补 isBlank/长度 4000 校验（原仅判 null，空白与超长通过）；ChatService 接入 isDuplicateWithinWindow（RedisChatMemory 新方法：AI:DUP key 2 秒窗口相同文本拒绝，streamChat 走 SSE END 结构化错误/callChat 抛 BizException）——合法间隔重复不受影响 | 用户报告空白/超长未拒+重复生成多条历史 | E2E 实测：空白 400/4001 字 400/0.5s 重复 400 拒绝/3s 合法 200 | 已修复并实测 |
| D15-80 | **课程搜索匹配简介**：page 查询 keyword OR exists 子查询匹配 course_content.course_introduce（共键 cc.id=course.id+deleted=0+单引号转义） | 用户要求搜索同时匹配简介 | 搜「单体到微服务」命中 Java 微服务课（实测）；服务端分页前过滤语义不变 | 已修复并实测 |
| D15-81 | **昵称/头像定性**：user 表原设计无昵称/头像字段（12 核心表，纪律不可改列）；功能不存在非缺陷——方案 A（新表 user_profile_ext+头像存储）待批准后实施 | 用户要求回读昵称头像 | 本轮不动数据模型 | 待批准 |

---

## M15.15 用户扩展资料实施决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-82 | **方案 A 落地（用户批准）**：新表 user_profile_ext（user_id PK 1:1 共键/nickname/avatar_url，12 核心表零改动）+ 启动幂等建表 Runner + DDL 归档 deploy/migrations；用户服务扩展 PUT /users/me（nickname 保存）、GET /users/me（ext 回读）、POST /users/me/avatar（白名单 jpg/png/webp ≤2MB、服务端命名、防穿越）、GET /users/me/avatar/file/**（公开 GET+网关白名单）；前端 ProfileView 昵称/头像上传/回读 | 用户批准 50 号方案 A | 昵称/头像全链路可用（E2E：PUT→上传→回读全保留）；upsert null 不覆盖防互相丢失 | 已实施并 E2E 验证 |

---

## M15.15 头像与昵称保存修复决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-83 | **前端 multipart 头修复+异常处理补齐**：①onAvatarChange 移除手动 Content-Type（FormData 由 axios 自动生成 boundary，手动覆盖丢 boundary 致后端解析 500——复现对照实测）；②saveProfile 补 catch（失败提示+保留输入，消除 Unhandled rejection） | 用户报告保存不成功/页面不更新 | 头像上传/昵称保存/前端显示全链路实测通过；唯一变更 ProfileView.vue | 已修复并实测 |

---

## M15.15 刷新回读修复决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-84 | **ProfileView onMounted 回填补全**：原仅回填 cellPhone，昵称/头像回读未挂载（R6 fillExt 仅挂在保存成功后）——刷新进入 /profile 时 store.profile 已含扩展资料但表单空白。修复：onMounted 补 fillExt() | 用户报告刷新不显示 | 保存→持久化→刷新回读全链路闭环（实测昵称回读用户保存值/头像显示） | 已修复并实测 |

---

## M15.15 顶栏同步决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-85 | **顶栏头像与昵称同步显示**：MainLayout 顶栏新增圆形头像（30px，缺失回退首字母）+ 昵称优先显示（无则 username）；数据源 Pinia store.profile（computed 响应式）——ProfileView 保存/上传成功 → fetchProfile 更新 store → 顶栏即时生效（无刷新）；后端 avatarUrl 带版本参数 ?v=ts（覆盖式同 URL 浏览器缓存破除）；失败回退（上传失败清除本地预览回退旧头像+错误提示，store 不变更） | 用户要求顶栏同步显示 | 顶栏构成：头像+昵称+角色标签+通知铃铛+退出；E2E 实测改昵称/传头像无刷新即时同步 | 已实施并 E2E 验证 |

---

## M15.15 头像变量与昵称限制决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-86 | **上传变量异常修复+昵称 7 天限制**：①ProfileView request 未导入→profileApi.uploadAvatar 统一封装；②user_profile_ext 加 nickname_updated_at（initializer ALTER 幂等补列），updateProfile 服务端权威校验 7 天一次（仅昵称变化时触发/首次不受限/剩余天数提示；头像不受限）；前端不展示限制文案仅呈现服务端消息 | 用户报告 Can't find variable + 7 天限制需求 | 上传链路修复（E2E 0 异常）；昵称修改频率服务端权威管控（E2E 第 2 次 400） | 已修复并实测 |

---

## M15.15 头像同步显示修复决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-87 | **头像/昵称更新响应直更 store**：上传/保存响应到达即更新 store.profile（avatarUrl+avatarVersion 响应式版本/nickname），顶栏 computed 即时重算；fetchProfile 降级为兜底一致性（原为主更新路径——时序竞争致预览/顶栏不同步）；失败回退保持（R6） | 用户报告部分区域更新部分未更新（fetchProfile 时序竞争） | 顶栏与个人中心预览同时更新（E2E src 对照实测）；单点数据源模式覆盖后续头像展示位 | 已修复并 E2E 验证 |

---

## M15.15 昵称更新与头像路径修复决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-88 | **头像显示相对路径修复**：avatarUrl 存储为服务端相对路径 /users/me/avatar/file/*，前端 img 直接使用被 dev server SPA fallback 截获（200 text/html 破图）——新增 withApiBase() 统一加 /api 前缀（ProfileView+MainLayout 两处接入）；昵称「111」按管理员指令 SQL 更新+7 天基准重置（昵称仅 user_profile_ext 一处存储无冗余复制，全位置一致） | 用户报告 jpg 上传刷新不显示 | 顶栏+个人中心头像刷新后 200 image/jpeg 实测；昵称 111 全位置一致 | 已修复并截图验证 |

---

## M15.16 支付方式选择与模拟支付决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-89 | **模拟支付全链路**：①trade 新增 POST /orders/{id}/pay（归属+仅待支付+条件更新防并发+幂等 alreadyPaid 补开课）②markPaid 同步 order_detail.status=2+pay_channel 落库（enroll 权益判定依赖明细状态——首版缺陷修复）③开课移入 TransactionSynchronization.afterCommit（跨服务读必须见已提交值——事务隔离修复）④learning 内部 enroll-for-paid（幂等建档）⑤前端确认页支付方式弹窗（微信/支付宝）+成功弹窗+跳转 | 用户支付方式选择与模拟支付需求 | 无真实支付 API；BUY 门禁/AI 路径不变；渠道落 order_detail.pay_channel | 已实施并 E2E+DB 验证 |

---

## M15.16 解释性文字清理决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-90 | **解释性文字清理**：①「本阶段仅支持预下单…」②「（金额由服务端按课程实时价格计算，单位：分）」——R16 重写确认页时已删主体；本轮全站扫描清理同类分值提示残留（OrderDetailView「（xx 分）」「下单时 xx 分」+ 无用 kz-tip 样式）——金额统一仅显示 ¥ 元 | 用户要求删除解释性文字且不残留 | 页面更简洁；布局/功能不变（E2E 验证零残留） | 已清理并验证 |

---

## M15.16 模拟支付提示清理决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-91 | **模拟支付提示全站清理**：①支付弹窗 el-alert「当前为模拟支付环境…」整块删除（弹窗自然连贯：金额→选项→按钮）②markPaid 备注模板「模拟支付成功」→「支付成功」③存量 4 笔测试订单备注 REPLACE 更新（订单详情界面显示口径）；代码注释保留（非界面）；支付功能/安全机制不变 | 用户要求移除功能性模拟支付提示并全站排查 | 支付流程文案自然；订单备注与界面一致 | 已清理并 E2E 验证 |

---

## M15.16 登录页演示账号移除决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-92 | **登录页「演示账号说明」折叠块移除**（含三行账号密码信息+关联 CSS）——正式化口径（57 号报告已列 checklist）；登录表单/按钮/布局/功能不变（E2E：零残留+登录回归通过） | 用户要求移除演示账号说明 | 登录页正式化；演示账号信息不再在界面暴露 | 已移除并 E2E 验证 |

---

## M15.17 账号体系正式实现决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-93 | **手机号短信验证码注册**（62 号方案修订实施）：auth 新增 SmsService 抽象（Mock/Real @ConditionalOnProperty+ObjectProvider 选择——修 Bean 缺失启动失败）、SmsCodeService（HMAC-SHA256 摘要存储/Lua 原子一次性消费/60s 间隔/5min 有效期/5 次失败/日限 10/IP 限 20——全 Redis 原子）、/auth/sms/send（REAL 未配置 503 安全拒绝+已注册引导登录）、/auth/register（验证码消费→user internal 创建：username 自动生成 u_+随机/creater=0/type=2 固定）| 用户修订指令（短信注册替代用户名注册） | 12 核心表零改动；JWT 仅 STUDENT；username 登录保留 | 已实施并 E2E 验证 |
| D15-94 | **手机号登录兼容**：AuthService.login 按手机号格式分流 findByPhone/findByUsername（user internal 新接口）——手机号或用户名+密码双通道；管理端 create 禁 ADMIN+随机初始密码（废除公开 DEFAULT_PASSWORD）+initialPassword 一次性返回；CSV 导入（≤500 行/行级校验/部分成功/随机密码；激活策略待批） | 用户统一账号体系需求 | 手机号/用户名双通道登录；管理端防提权+安全导入 | 已实施并 E2E 验证 |

---

## M15.17 验证码获取通道补充（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-95 | **MOCK 模式 devCode 响应通道**：send 响应携带 devCode（每次随机非固定、仅 mock 模式返回、REAL 为 null）——开发/测试/E2E 用户可从响应获取验证码完成注册；REAL 模式任何通道不返回码值 | 用户问「验证码怎么获取」——MOCK 体验缺口 | 开发注册全流程自助可用；正式环境无影响 | 已实施并 E2E 验证 |

---

## M15.17 devCode 自动填入决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-96 | **MOCK devCode 自动填入**：注册页发送验证码响应含 devCode 时自动填入验证码框（提示「开发环境」）——用户无需 DevTools；REAL 模式无 devCode 走正常收码流程 | 用户 mac 无法打开 DevTools | MOCK 注册体验：填手机号→点获取→自动填→填密码→注册 | 已实施并全流程实测 |

---

## M15.18 修改手机号验证码流程决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-97 | **修改手机号短信验证码确认**：/auth/sms/send 扩展 CHANGE_PHONE 场景（登录态强制 requireLoginUserId 解析 JWT subject+发送前占用预检「该号码已注册」）；新增 /auth/change-phone（验证码 Lua 消费+占用检查+Feign user 更新+唯一索引兜底）；ProfileView 手机号区块重构（新号+验证码+确认更换，与保存资料分离）；各角色（学员/教师/管理员）行为一致；占用拒绝跨角色覆盖（含管理员占用号） | 用户要求手机号修改验证码确认+占用拒绝 | 完整链路（发送/校验/占用/持久化/界面同步）；12 核心表零改动 | 已实施并三角色 E2E 验证 |

---

## M15.18 换绑验证码获取补充（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-98 | **CHANGE_PHONE devCode 通道确认+一键换绑脚本**（scripts/dev-change-phone.sh）：mock 模式 send 响应带 devCode（与 REGISTER 同机制）+前端 ProfileView 验证码自动填入——换绑全流程无需 DevTools；REAL 模式不返回码值 | 用户问换绑验证码获取 | 各角色换绑一键可完成（实测 teacher01 换绑成功） | 已实施并实测 |

---

## M15.18 换绑验证码自动填入决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-99 | **换绑 devCode 自动填入**：ProfileView onSendPhoneCode 响应含 devCode 时自动填充 phoneChange.smsCode（提示「开发环境」）——与注册页同机制；REAL 模式无 devCode 走正常收码 | 用户要求换绑验证码自动填充 | 换绑全流程零手输（实测 autoFilled=608177） | 已实施并实测 |

---

## M15.19 交付整改决策（2026-10-21，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-100 | **三业务问题修复**：①资料上传自动绑定当前课程（扩展名定类型）修复死路；②menuConfig 恢复运营看板+平台监管（3.2.1.7/3.2.1.8，事件运维维持裁剪，一级 9 项）；③payOrder/enrollPaidCourses 两步拆分（afterCommit AtomicReference 不可变返回值根因）+开课失败持久化 order.message+alreadyPaid 重试补偿——前端区分支付成功与开课失败 | 用户交付整改 A/B/C | 三问题修复并 E2E 实测 | 已修复 |
| D15-101 | **Prompt 版本持久化+工作台聚合**：①prompt_version 表（增量迁移+幂等建表）+save-draft/history/rollback 接口（回滚=发布新记录，Nacos 失败保留最后有效版本）②workbench-summary 聚合（本人课程口径/待回复=无 reply 口径不触碰 status 语义/无样本显示 0）+TeacherStatsView 卡片；采纳率设计待批 | 用户 D/E 需求 | Prompt 可追溯可回滚；教师工作台 5 指标 | 已实施并实测 |
| D15-102 | **如实标注未完成**：F 资料预览/替换/版本/知识库提交（M2）、G TTS 流式（M2）、实机语音（待人工验收）、H REAL 时延（待授权）、I Docker（环境缺失+命令清单）、采纳率（设计待批）——不伪装完成 | 用户「不把未执行场景记为验收通过」 | 交付边界清晰 | 已标注 |

---

## M15.20 继续整改决策（2026-10-22，进行中）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-103 | **Prompt 单模板版本重构**：版本 content 改单模板 JSON（回滚只恢复选定模板+其余保持当前生效）；uk_agent_version 唯一约束（迁移前重复检查=0）+MAX+1 冲突重试；chat_session.prompt_version_map 会话快照（新会话绑版本/存量 NULL 兼容回退当前生效）；草稿载入接口 | 用户 M15.20-A | 单模板回滚不影响其他模板；并发安全；新旧会话隔离 | 已实施 |
| D15-104 | **工作台口径修正+AI 检查/采纳**：qIds 空早退防越界；learners=COUNT(DISTINCT user_id)；AI 判定走 reply_extra.source（核心 reply 无该字段语义）；reply_extra 加 ai_checked/helpful 列+标记接口（教师检查/学员采纳撤销）；workbench 逻辑下沉 Service | 用户 M15.20-B | 口径准确；AI 待检查真实状态+采纳机制落地（采纳率公式=helpful>0 的 AI 回复数/AI 回复总数——精华不等同采纳） | 已实施 |
| D15-105 | **开课补偿闭环**：enroll_compensation 表（支付事务内登记 PENDING——进程中断可恢复）+executeNow（同步）+@Scheduled 退避重试（1/2/4 分钟×3）+FAILED_MANUAL+manualRetry 人工接口（所有者/管理员）；pay 返回补偿状态列表；前端重试入口待接 | 用户 M15.20-C | 开课失败/进程中断/并发全覆盖；支付渠道与事实不变 | 已实施 |
| D15-106 | **资料绑定失败恢复**：GET /teacher/resources/unbound（本人 course_id IS NULL）+TeacherResourceView 未绑定文件区+加入当前课程按钮 | 用户 M15.20-D | 上传失败可恢复；刷新持久；不暴露全站 | 已实施 |

---

## M15.20 继续整改决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-103 | **Prompt 单模板版本重构**：版本 content 改单模板 JSON（回滚只恢复选定模板+其余保持当前生效）；uk_agent_version 唯一约束（迁移前重复检查=0）+MAX+1 冲突重试；chat_session.prompt_version_map 会话快照（新会话绑版本/存量 NULL 兼容回退当前生效）；草稿载入接口 | 用户 M15.20-A | 单模板回滚不影响其他模板；并发安全；新旧会话隔离 | 已实施 |
| D15-104 | **工作台口径修正+AI 检查/采纳**：qIds 空早退防越界；learners=COUNT(DISTINCT user_id)；AI 判定走 reply_extra.source（核心 reply 无该字段语义）；reply_extra 加 ai_checked/helpful 列+标记接口；workbench 逻辑下沉 Service | 用户 M15.20-B | 口径准确；AI 待检查真实状态+采纳机制落地 | 已实施 |
| D15-105 | **开课补偿闭环**：enroll_compensation 表（支付事务内登记 PENDING——进程中断可恢复）+executeNow 同步+@Scheduled 退避重试（1/2/4 分钟×3）+FAILED_MANUAL+manualRetry 人工接口；pay 返回 enrollStatuses/enrollAllSuccess；MapperScan 扩展 compensation 包；ObjectProvider 延迟 Feign 修测试 context | 用户 M15.20-C | 开课失败/进程中断/并发全覆盖；支付渠道与事实不变 | 已实施并 E2E（PENDING→SUCCESS） |
| D15-106 | **资料绑定失败恢复**：GET /teacher/resources/unbound（本人 course_id IS NULL）+TeacherResourceView 未绑定文件区+bindUnbound | 用户 M15.20-D | 上传失败可恢复；刷新持久；不暴露全站 | 已实施 |

---

## M15.20 续作决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-107 | **重试开课前端+13 项回归测试**：OrderDetailView 重试按钮+orderApi.retryEnroll；PromptVersionRollbackTest 4+TeacherWorkbenchSummaryTest 4+EnrollCompensationServiceTest 5 全绿 | 用户续作指令 | 补偿/UI/口径回归证据齐备 | 已实施 |
| D15-108 | **E 资料管理后端**：replace（replaced_by 历史链+deleted=1）+history 链追溯+submit-kb（kb_status 提交→管理员审核，幂等不绕审）；**B 指标缺陷再修**：Controller 内联旧 workbench 未删（覆盖下沉版）+**AI source=SYSTEM_AI** 判定修正——实测 aiUnchecked=96/learners 去重 24 | 用户 M15.20-E+用户 B 复核 | 资料替换可追溯；工作台 7 指标准确 | 已实施并实测 |
| D15-109 | **F TTS 流式播放（WebAudio WAV 分片解码）**：ReadableStream 边收边解 PCM 逐段 schedule（非整段 arrayBuffer）；非 WAV 降级整段标记 buffered；停止/切答/关页释放——实机播报待人工验收 | 用户 M15.20-F | 真·流式播放落地；能力标记准确 | 已实施（实机待验） |
| D15-110 | **J 文档收口**：compose.yml 引用修正+docs/67-30 项需求符合性映射表（四档状态逐条） | 用户 M15.20-I/J | 交付口径统一 | 已落盘 |

---

## M15.21 继续整改决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-111 | **运行错误+状态契约修复**：①OrderDetailView computed 导入（浏览器实测渲染）②支付/开课状态统一（enrollStatuses 四态结构化——详情不再依赖备注文本；前端缺失字段不默认成功）③移除 enrollPaidCourses 重复执行（单一补偿路径）④@EnableScheduling ⑤DB 原子抢占（RUNNING 认领防并发）⑥SUCCESS 不倒退 ⑦manualRetry attempts=0 重置 ⑧BackfillRunner 存量回填 | 用户 M15.21 一/二 | 详情页可用；状态准确；补偿闭环健壮 | 已实施并实测 |
| D15-112 | **WAV 分片播放重构**：字节级缓冲（头一次消费/帧跨分片/尾部残留）+播放结束与网络结束分离+全量释放+lastError 上抛+mode='chunked-playback' 准确标注；单元测试 7/7（头拆分/单字节/奇数边界/多声道/截断/一致性） | 用户 M15.21 三（复现 bug） | 6 采样点只解 2 个 bug 修复；全部样本无丢失 | 已实施并 7/7 |
| D15-113 | **回复检查/采纳重构**：校验链（requireReplyOfTeacher 403+SYSTEM_AI 常量+幂等）；采纳 interaction_reply_helpful 唯一记录（userId+replyId——不再计数加减）+学员侧 StudentInteractionController（helpful/undo/status——刷新保持）；MP 单 TableId 限制修正 | 用户 M15.21 四 | 采纳去重/幂等/权限收口 | 已实施 |
| D15-114 | **资料管理+Prompt 一致性+测试证明**：E 资料前端入口（预览/替换/历史/submit-kb）+unbound 实测；F WebAudio WAV 流式（chunked-playback 标注）；Prompt INITIAL 快照（五类全绑防漂移）+RouteAgent 会话绑定；**隔离 MySQL 测试库**（course_stack_ai_test）真实 DB 断言（回滚隔离/唯一约束 2/2）+Mock 测试修正+H2 schema 同步——325 全绿 | 用户 M15.21 五/六/七 | 资料闭环/防漂移/测试证明真实 | 已实施 |

---

## M15.22 继续整改决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-115 | **订单详情契约统一+补偿健壮化**：页面 order 平铺契约（undefined 消除）+enrollStatuses 四态结构化展示（缺失=待核实不默认成功）；补偿三路径统一 tryClaim 原子认领+RUNNING 10 分钟租约回收（进程中断恢复）+SUCCESS 不倒退+manualRetry attempts=0 重置+BackfillRunner 核实学习档案（exists-lesson——不默认 SUCCESS） | 用户 M15.22 一/二 | 详情页可用；补偿闭环健壮（同步/定时/人工互斥+中断恢复） | 已实施并 E2E |
| D15-116 | **WAV 结束状态+采纳重构+资料管理+Prompt 一致性**：①dataTotal break 设 readerDone（done 实际播完 resolve）+7/7 分片测试；②采纳 interaction_reply_helpful 唯一记录（并发唯一键=成功）+学员侧控制器+页面按钮+SYSTEM_AI 判定；③资料预览/替换/历史/submit-kb→aigc createReviewTask（REVIEW_PENDING 管理员可见——不绕审）；④INITIAL 快照五类全绑+RouteAgent 绑定；⑤日期区间参数 | 用户 M15.22 三/四/五/六 | 播放结束可靠；采纳去重幂等；资料闭环；防漂移 | 已实施 |

---

## M15.22 语音提示移除与停止修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-117 | **语音第三方提示移除+停止播报全量停止**：①SpeechController REAL 分支 notice 改空（移除第三方解释性文本——MOCK 说明保留）；②停止播报按钮 onStopSpeak 全量停止（stopSpeak Audio 单例+stopSpeakStream 流式 sources/reader/ctx 释放+状态复位——修复流式播放停止失效）；③切会话同步 stopSpeakStream；④playTtsStream readerDone 补齐（dataTotal break） | 用户反馈（截图）：提示文本移除+停止失效 | REAL 模式无第三方提示；停止立即中断全部音源 | 已实施并 E2E（第三方文本移除验证） |

---

## M15.23 AI 对话真实 Qwen 切换决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-118 | **AI 对话切换真实 Qwen 模式**（零代码改动——配置驱动）：DASHSCOPE_ENABLED=true+API_KEY env 注入 → AiModelConfig 装配 ChatClient → DashScopeChatProvider 生效；MockChatProvider 条件禁用（逻辑保留可回退）；**切换脚本** start/stop-aigc-real.sh（key 参数化不落盘）；**实测**：qwen-plus 真实回复 SSE 流式+UI 徽标「真实模型 qwen-plus」+Provider DASHSCOPE；**异常处理**：401/TIMEOUT/429/UPSTREAM 统一映射；**Embedding 仍 MOCK**（如实标注） | 用户指令切换真实模型+提供 key | 对话功能正常（界面交互不变）；MOCK 可回退；真实调用计费提示 | 已实施并 E2E 验证（1 次调用） |

---

## M15.22 语音复测修复决策（2026-10-22 补，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-119 | **语音就绪文案删除+停止双保险**：REAL fallback「语音就绪…」删除（notice||''）+播报开始清 voiceNotice；stopSpeakStream suspend（同步静音）+close（释放）双保险+sources.onended 置空 | 用户复测反馈（截图） | REAL 页面零解释性文字；停止立即静音 | 已实施并实测（noReadyText=true） |

---

## M15.22 停止播报根因修复决策（2026-10-22 续，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-120 | **onStopSpeak 方法丢失根因修复**：方法插入被后续编辑覆盖丢失（按钮引用未定义——Vue warn not defined on instance→点击无执行）——重新插入 script setup+**诊断探针** window.__kzaiSpeech（ctx/state/sources/forceStop）——**浏览器实测完整链路**：播报中（speaking/ctx/sources）→点击停止→全部释放（stopWorks=true） | 用户复测「还是没反应」 | 停止播报真正生效；探针留存诊断 | 已实施并 E2E 验证 |

---

## M15.24 三层测试体系决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-121 | **三层测试体系**：vitest+test-utils+jsdom（dev）；**npm test**（单元 speech 8+组件 OrderDetailView 6=14）+ **npm run test:e2e**（隔离账号注册→订单契约→浏览器渲染 9 项）；**生产模块直测**（speech.js import——mock AudioContext/ReadableStream 注入不复制实现）；组件测试模拟拦截器解包语义（mock 返回 data 非 R 壳）| 用户三层测试指令 | 订单契约/登录失效/播放器生命周期回归证据；test/test:e2e 命令产出 | 已实施并全绿（14+9） |

---

## M15.25 测试可信度与工程门禁决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-122 | **ESLint 门禁抓真 bug**：speech.js play().catch 中 resolve 越界 ReferenceError（executor 外引用）——修复 resolve 外提；markdown.js 无用转义修正；lint 命令 0 errors | 用户 M15.25 六（门禁） | 门禁抓到真实运行时缺陷；lint 纳入命令 | 已实施并门禁通过 |
| D15-123 | **认证失效集成测试 5/5**：真实拦截器+tokenStore spy（adapter mock 网络层）——业务 401/HTTP 401 清理跳登录、403 不清、异常透传、并发幂等 | 用户 M15.25 二 | 认证流程真实回归（非 Mock Error 模拟） | 已实施全绿 |
| D15-124 | **speech 生命周期测试挂起如实标注**（describe.skip——异步环境排查中）；组件测试改用安全模式（stop 触发 done） | 用户「不把挂起当通过」 | 如实标注；不阻塞其他测试 | 标注 skip |

---

## M15.26 待支付订单去支付入口决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-125 | **待支付订单「去支付」入口**：详情页 status=1 显示主按钮+支付方式弹窗（微信/支付宝——复用 ConfirmView 交互）+onPay 四态反馈+load() 刷新；列表行新增「去支付」跳详情 | 用户反馈（截图）：待支付详情无支付入口 | 待支付订单可继续完成支付；支付后状态/开课状态即时可见 | 已实施并 E2E 全链路验证（paySuccess/statusNow/enrollTag/DB SUCCESS） |

---

## M15.26 列表去支付移除决策（2026-10-22 补，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-126 | **订单列表移除「去支付」**（按钮+goPay 方法）——列表仅保留详情/取消订单；详情页支付入口保留 | 用户要求（截图）：列表移除去支付仅保留其余 | 列表简洁；支付统一走详情页入口 | 已实施并 E2E 验证（列表无/详情有） |

---

## M15.26 支付弹窗白色线框修复决策（2026-10-22 补，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-127 | **焦点轮廓规范化+遮罩期防护**：全局 focus-visible 品牌色（#4F46E5）统一（鼠标不显示/键盘显示）；body:has(.el-overlay) 期间表格焦点轮廓隐藏；onOpenPay blur 清残留——覆盖全部支付入口与状态（加载/跳转/成功/失败） | 用户反馈（截图）：点击去支付后白色线框残留 | 线框消除；键盘可访问性保留；跨浏览器/移动一致 | 已实施并 E2E（outlineOutsideDialog=0） |

---

## M15.27 课程问答页崩溃修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-128 | **课程问答页白屏崩溃修复**：根因=M15.21 插入采纳代码时 `reactive` 未导入（setup ReferenceError→组件树崩溃）——补齐 vue import；全页面遍历确认仅此页受影响；ESLint no-undef 门禁预防同类 | 用户反馈「学员端页面崩溃」 | 问答页恢复可用；边界/防重已内建 | 已实施并 E2E 验证（Unhandled error=0） |

---

## M15.28 390px 移动端适配决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-129 | **390px 移动端适配**：MainLayout isMobile（≤768）桌面侧栏常驻/窄屏 el-drawer 抽屉（汉堡按钮+选单自关）+Logo 窄屏短文案；CourseListView 筛选纵向+课程卡 ≤768 单列撑满；AiWorkbenchView 输入区窄屏完整（wrap/box-sizing）；**1440 验证不破坏**（侧栏 200px/四列/无汉堡/长 logo） | 用户指令（390 不可用；1440 不改坏；只改点名文件） | 移动端四页无竖排/无裁切/无横向滚动；桌面零变化 | 已实施并双宽度 E2E 验证 |

---

## M15.29 个人中心头像撑破首屏修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-130 | **头像 96×96 规范化**：ProfileView 追加 style 块（原文件无 style——avatar-img 零约束按原图 899×1259 渲染撑破首屏）——avatar-img/占位统一 96×96+object-fit cover+圆形；avatar-row 横向不撑破；窄屏 wrap；顶栏 30px 不变 | 用户指令（只改 ProfileView） | 首屏同屏（头像/昵称/保存）；大图上传预览仍 96×96 | 已实施并双宽度 E2E 验证 |

---

## M15.30 个人中心解释性文案删除决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-131 | **个人中心三段解释性文案删除**（换手机号「当前：…修改需验证码」/保存资料「账号由管理员创建…」/改密「修改成功后登录状态失效…」）；**规范确立：前端界面一律不保留解释性/提示性说明文字，仅保留功能性文案** | 用户反馈（截图） | 页面简洁；功能性文案保留（按钮/标签/错误提示） | 已实施并 E2E（三段 0 命中+功能按钮完整） |

---

## M15.31 我的学习卡片状态矛盾修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-132 | **我的学习卡片一致性**：0 章→「暂无章节」+标签「未开始」（不标学习中/不显 0%）；按钮=有最近章节且进度>0 才「继续：第N章」否则「开始学习」；「已开始学习」提示仅 0 章以外且有最近章节时显示 | 用户指令（同卡矛盾；只改 LearningListView） | 卡片状态/进度/按钮三方一致 | 已实施并 9 卡逐卡 E2E 验证 |

---

## M15.32 登录角色跳转误报修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-133 | **登录按角色跳转**：无 redirect → ROLE_HOME（teacher/admin/student 各归其位）；有 redirect 且 roleAllowed 允许才去 redirect（不改权限规则）——消除教师/管理员登录守卫「无权限」误报 | 用户指令（只改 LoginView；不改 roleAllowed） | 三账号登录落点正确且无误报 | 已实施（逻辑 8/8+teacher01 浏览器实测） |

---

## M15.33 el-radio 弃用警告修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-134 | **el-radio-button :label→:value**（OrderListView 7 处——「全部」null 不 on）；全项目核查（NotificationCenter/AdminDashboard 已合规） | 用户指令（弃用警告 7 条+无障碍树 on） | 控制台弃用警告 0；筛选功能不变 | 已实施并 E2E 验证 |

---

## M15.34 课程卡键盘可达修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-135 | **课程卡语义化**：div @click → router-link 包裹 el-card（a 原生 Tab/Enter/aria-label=课程名）；封面 alt=课程名；悬停/角标/网格布局保留 | 用户指令（键盘可达+读屏；只改 CourseListView） | 键盘用户可 Tab+Enter 进详情；读屏读课程名 | 已实施并 E2E 验证（focused/click 进详情/alt） |

---

## M15.35 可访问性三处缺口修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-136 | **可访问性三处**：①菜单/顶栏 :focus-visible 主色轮廓（#4F46E5）②kz-meta/kz-empty 改 --kz-text-secondary（4.76:1）③铃铛 span→button aria-label=「通知」 | 用户指令（三处缺口；只动布局样式） | 键盘焦点可见/对比度达标/铃铛可键盘开 | 已实施并 E2E 验证 |

---

## M15.36 课程分类可筛选修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-137 | **一级/二级分类 el-select 加 filterable**（输入过滤——不隐藏测试分类；数据清理另作数据任务） | 用户指令（105 项难选；只改 CourseListView；不隐藏/不改库） | 输入「IT」即筛到「IT技术」；请求参数口径不变 | 已实施并 E2E 验证 |

---

## M15.37 昵称校验修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-138 | **昵称保存前校验**：拒绝纯数字/纯空白/可见字符<2（表单错误不发请求）；已保存值不偷改（仅再保存时校验）；合法昵称保存→fetchProfile→顶栏同步 | 用户指令（只改 ProfileView；不改演示库） | 无意义短数字昵称无法再保存；student01 存量 1111 保留（用户口径） | 已实施并 E2E（1111/单字拒绝+新号合法保存链路） |

---

## M15.39 封面文字与主按钮令牌一致修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-139 | **封面/按钮令牌一致**：spring.svg 大字改「Spring」（不写框架名）；课程详情「继续学习」success→primary（与全局主色一致；顶栏不动） | 用户指令（只改 spring.svg+CourseDetailView） | 封面无 Spring Boot 字样；详情主按钮与查询按钮同色（rgb(79,70,229)） | 已实施并 E2E 验证 |

---

## M15.40 教师学情页去表名修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-140 | **学情页统计口径改业务句子**（学习人数和完成率按学员学习记录统计，学习时长按视频实际播放进度累计）——移除表名/字段名 | 用户指令（只改 TeacherStatsView；数字接口不改） | 界面与源码零表名；口径语义不变 | 已实施并双宽度 E2E 验证 |

---

## M15.41 通知未读数请求收敛决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-141 | **unread-count 节流**（29s 窗口单发；watch 路由切换节流；30s 轮询仅此一处；标记已读 force 刷新） | 用户指令（进入学习页连续 4 次；只改 MainLayout） | 请求数 4→0（节流窗口内）；铃铛数字仍更新 | 已实施并 E2E 验证 |

---

## M15.42 404 兜底路由修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-142 | **404 兜底路由**：catchAll（render 函数页面——runtime-only Vue 不编译字符串模板）+守卫 pathMatch 放行（不 roleAllowed 拦截）；返回首页=已登录角色首页/未登录登录页——不再静默弹回课程页/No match 警告 | 用户指令（只改 router/index.js；不重定向课程浏览） | 未知路径明确提示+可返回；控制台干净 | 已实施并 E2E 验证（页面404/按钮/No match=0） |

---

## M15.43 学员界面收起开发演示痕迹决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-143 | **学员界面去开发痕迹**：①徽标「真实模型」（无 qwen-plus）+诊断面板收起（Provider 行删）②麦克风 aria-label=语音输入 ③新会话首条提问前 15 字自动命名（aiApi.rename）④通知 title/content 技术前缀收摘要（不过滤不丢通知） | 用户指令（点名文件；不过滤） | 学员界面无开发痕迹；通知零丢失 | 已实施并 E2E 验证 |

---

## M15.44 学情页五项摘要修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-144 | **五项摘要修复**：request 未导入→改用已导入 teacherStatsApi.workbenchSummary；失败 ElMessage.error（不静默 0） | 用户指令（只改 TeacherStatsView） | 五项与接口一致（255/39/7/96/103 双宽度验证）；失败可见 | 已实施并 E2E 验证 |

---

## M15.45 教师新建课程分类下拉修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-146 | **分类接口换 tree()**（教师 200）+失败「分类加载失败，请重试」ElMessage+el-alert「重新加载」按钮；教师不再请求 /admin/categories* | 用户指令（只改 TeacherCourseEditView；换已有接口） | 教师可正常新建课程选分类；失败可重试 | 已实施并 E2E 验证（98 项/无 403） |

---

## M15.46 问答 ID 可读性修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-144 | **问答 ID 可读性**：①教师端课程筛选改下拉（254 门多页拉全——修正后端分页参数 pageNum/pageSize）+el-pagination 接上 ②管理员端课程下拉+userId 筛选移除+提问人「匿名/用户 N」回退 ③课程列 courseMap 回退 ID（孤儿问题数据——课程已删映射不可得时按口径回退） | 用户指令（两文件） | 有效课程显示名称；孤儿数据回退 ID | 已实施（映射 254 门验证；部分孤儿行回退如实标注） |

---

## M15.47 390px 顶栏与看板指标修复决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-145 | **顶栏/看板窄屏修复**：Expand 导入+aria-label「打开菜单」+窄屏 header flex-wrap 换行增高（全局 main.css !important——scoped 优先级不足）+主容器自适应；AdminDashboard 六指标 :xs=12 每行两个+标题 nowrap | 用户指令（两文件） | 390 按钮完整/抽屉可开/指标两列/标题一行/无警告；1440 六列侧栏不变 | 已实施并双宽度 E2E 验证 |

---

## M15.48 前端八项整改决策（2026-10-22，完成待验收）

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D15-146 | **AI 工作台窄屏会话**：toggleSessions 分流（窄屏 showSidebar 抽屉式固定定位/桌面 asideCollapsed）；会话项语义化（role=button/tabindex/Enter/Space/aria）+重命名删除 button 化；登录/AI/昵称输入 aria-label | 用户八项 2/4 | 手机可查看切换历史会话；键盘可达 | 已实施并双宽 E2E |
| D15-147 | **采纳率「暂无统计」**：aiAdoptRate=null（后端口径未确认）→不再裸 %；管理表格 switch 横排 nowrap+ID 省略 tooltip；测试数据隔离方案文档 | 用户八项 3/5/8 | 无误导数据；表格可读；隔离方案就绪（脚本待编写） | 已被 2026-10-23 口径取代：无样本仍为 null，有样本按有帮助去重计算 |

---

## 2026-10-23 学情区间、采纳率、资料入库

| # | 决策 | 原因 | 影响 | 状态 |
|---|---|---|---|---|
| D16-1 | 学习增量只记成功落库的秒数，业务日按 Asia/Shanghai。不回填历史。早于 facts_since 的区间返回 INSUFFICIENT，学习指标为 null | 累计 study_seconds 不能被最后更新时间切成区间 | 查询和 CSV 共用 StatsWindow | 已实施，H2 测试证明 30 与 60 不合并成当天 90 |
| D16-2 | 采纳率 = 有至少一条 helpful 记录的未隐藏、未修正 SYSTEM_AI 回复 / 同类回复总数。百分比 0～100、1 位小数。工作台待检查用 aiUnchecked | 旧实现把 aiReplies 标成待检查，并用 source 包含 AI | 教师回复和已修正回复不进分母 | 已实施，4 条有效 AI、2 条被标记，结果 50.0；两人标记同一条不膨胀 |
| D16-3 | 教师资料入库只传 resourceId。aigc 向课程内部接口取字节，计算 SHA-256。付费课程 PROTECTED。同版本重复提交返回原文档。删除或替换撤回旧来源 | 旧 submit-kb 没有服务端映射，文档用空 SHA 和教师路径占位 | 管理员仍要审核后才索引。本次测试不调用付费 Embedding，不宣称真实语义召回 | 导入单测 4 项通过 |
| D16-4 | 日事实用 INSERT ON DUPLICATE KEY UPDATE 累加。失败重试走独立接口，进行中任务靠 uk_doc_active。发布用 publish_generation 条件更新，只写索引结果字段 | 先读后覆盖会丢并发增量；重试按钮仍调用首次提交；发布 updateById 会把撤回写回已发布 | 不回填历史日事实。重复执行覆盖脚本不改 facts_since。ES 未就绪时检索仍以 MySQL 审核状态为准 | MySQL 隔离库测试通过。Docker 未启动 |
