# 接口扩展登记（api-extension.md）

原始《系统设计说明书》5.2.3 定义了 27 条接口。本文件登记各阶段**新增**的接口，
说明所属服务、权限、用途与登记阶段。**未登记的接口不得上线**。

统一约定：
- 返回结构 `R<T>{code,msg,data,requestId}`；业务异常 HTTP 200 + `code≠200`；鉴权异常 HTTP 状态码跟随业务码（401/403）。
- `/internal/**` 为服务间接口，网关拒绝外部访问，且必须携带内部签名。
- 身份只取网关注入头，不接受请求参数中的 `userId`/`role`。

---

## M2 已登记

### kzai-user（用户服务）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/admin/users` | ADMIN/STAFF | 用户列表分页 + 账号/手机号/类型/角色/状态组合搜索 |
| GET | `/admin/users/{id}` | ADMIN/STAFF | 用户详情 |
| POST | `/admin/users` | ADMIN | 新增账号（账号/手机号唯一、角色白名单） |
| PUT | `/admin/users/{id}` | ADMIN | 编辑（仅手机号） |
| PUT | `/admin/users/{id}/roles` | ADMIN | 角色分配，同步 `user.type`，旧 token 失效 |
| PUT | `/admin/users/{id}/status` | ADMIN | 启停；禁用后旧 token 失效；禁止禁用自己 |
| PUT | `/admin/users/{id}/password` | ADMIN | 重置密码，旧 token 失效 |
| DELETE | `/admin/users/{id}` | ADMIN | 删除（严格策略：必需关联类型全部接入后才允许） |
| PUT | `/users/me` | 登录 | 修改个人资料（手机号） |
| PUT | `/users/me/password` | 登录 | 修改密码（校验旧密码），旧 token 失效 |

### kzai-course（课程服务）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/internal/courses/count-by-creator/{userId}` | 内部（签名） | 统计创建人名下课程数，供用户删除前关联校验 |

### 变更说明

- `POST /auth/login` 响应新增 `roles` 字段（权限角色编码），`GET /users/me` 同步返回 `roles`。
- `DELETE /admin/users/{id}` 语义变更（M2 遗留修复③）：关联校验未覆盖全部必需类型时**拒绝删除**。

---

## M3 已登记（2026-10-19 实施完成）

### kzai-course（课程服务，M3 增量）

| 方法 | 路径 | 权限 | 说明 | 状态 |
|---|---|---|---|---|
| GET | `/categories/tree` | 登录 | 分类树（仅启用） | 已实施 |
| GET | `/admin/categories/tree` | ADMIN/STAFF | 分类树全量（含禁用） | 已实施 |
| POST | `/admin/categories` | ADMIN/STAFF | 新增分类（父子有效性校验，限两级） | 已实施 |
| PUT | `/admin/categories/{id}` | ADMIN/STAFF | 修改分类名称/排序/父级 | 已实施 |
| PUT | `/admin/categories/{id}/status` | ADMIN/STAFF | 启用/禁用（禁用后学员端不可见） | 已实施 |
| DELETE | `/admin/categories/{id}` | ADMIN/STAFF | 删除（存在关联课程或子分类则拒绝） | 已实施 |
| GET | `/teacher/courses` | TEACHER/ADMIN | 教师课程列表（仅本人） | 已实施 |
| POST | `/teacher/courses` | TEACHER/ADMIN | 创建课程（价格以分存储） | 已实施 |
| GET | `/teacher/courses/{id}` | TEACHER/ADMIN | 教师课程详情（所有权校验） | 已实施 |
| PUT | `/teacher/courses/{id}` | TEACHER/ADMIN | 编辑课程（状态流转约束） | 已实施 |
| POST | `/teacher/courses/{id}/submit` | TEACHER/ADMIN | 提交审核 | 已实施 |
| GET | `/teacher/courses/{id}/audit` | TEACHER/ADMIN | 审核状态与意见 | 已实施 |
| GET | `/teacher/courses/{courseId}/chapters` | TEACHER/ADMIN | 章节列表 | 已实施 |
| POST | `/teacher/courses/{courseId}/chapters` | TEACHER/ADMIN | 新增章节 | 已实施 |
| PUT | `/teacher/chapters/{id}` | TEACHER/ADMIN | 编辑章节 | 已实施 |
| DELETE | `/teacher/chapters/{id}` | TEACHER/ADMIN | 删除章节（逻辑删除） | 已实施 |
| PUT | `/teacher/courses/{courseId}/chapters/order` | TEACHER/ADMIN | 章节排序 | 已实施 |
| POST | `/teacher/courses/{courseId}/resources` | TEACHER/ADMIN | 绑定资料 | 已实施 |
| GET | `/teacher/courses/{courseId}/resources` | TEACHER/ADMIN | 资料列表 | 已实施 |
| DELETE | `/teacher/resources/{id}` | TEACHER/ADMIN | 解绑资料 | 已实施 |
| POST | `/files/upload` | TEACHER/ADMIN | 文件上传（类型/大小校验） | 已实施 |
| GET | `/files/{id}/download` | 登录（归属校验） | 受控下载 | 已实施 |
| GET | `/admin/courses/audits` | ADMIN/STAFF | 待审核课程列表 | 已实施 |
| GET | `/admin/courses/{id}/preview` | ADMIN/STAFF | 课程 + 章节预览 | 已实施 |
| POST | `/admin/courses/{id}/audit/approve` | ADMIN/STAFF | 审核通过（status→2 已上架） | 已实施 |
| POST | `/admin/courses/{id}/audit/reject` | ADMIN/STAFF | 驳回（填写原因） | 已实施 |
| POST | `/admin/courses/{id}/offline` | ADMIN/STAFF | 强制下架（status→3 下架） | 已实施 |
## M4 已登记（2026-10-19 实施完成）

> 原文仅有 `POST /orders/pre-place`（系统设计说明书 5.2 / AI Tool 预下单）。其余为新增路径，按需求登记。

| 方法 | 路径 | 角色 | 说明 | 状态 |
|---|---|---|---|---|
| POST | `/orders/{id}/pay` | STUDENT/ADMIN | `{channel: WECHAT\|ALIPAY}` | 创建支付单。模拟渠道在服务端验签通知后才入账，返回 `paymentStatus`。`mode=MOCK` 表示模拟，`sandboxAccepted=false` 表示没有沙箱验收。按钮和跳转本身不是支付成功 | 非待支付；验签失败；金额不一致 | trade_payment、order | 2026-10-23 |
| GET | `/orders/{id}/payment` | STUDENT/ADMIN | 订单 ID | 查询支付单状态，不把前端成功页当结果 | 401；越权 | trade_payment | 2026-10-23 |
| POST | `/orders/payments/notify/{channel}` | 无登录，网关白名单 | 渠道表单参数 | 验签后入账。核对商户、金额、币种和交易状态。重复通知不重复开课 | 验签失败；金额不符 | trade_payment | 2026-10-23 |
| POST | `/orders/{id}/details/{detailId}/refunds` | STUDENT | `{reason, requestId, amount?}` | 按明细实付申请退款。`amount` 为空则退剩余可退金额。同一 requestId 幂等。申请中暂停该课程权益 | 超过可退金额；已有处理中申请 | trade_refund、order_detail | 2026-10-23 |
| GET | `/orders/{id}/refunds` | STUDENT/ADMIN | 订单 ID | 退款进度：原因、金额、状态 | 401；越权 | trade_refund | 2026-10-23 |
| GET | `/admin/refunds` | ADMIN/STAFF | 无 | 退款审核列表 | 401；403 | trade_refund | 2026-10-23 |
| POST | `/admin/refunds/{id}/approve` | ADMIN/STAFF | 无 | 批准后走渠道退款。当前无沙箱密钥时走模拟退款，`sandboxAccepted=false` | 状态不允许 | trade_refund | 2026-10-23 |
| POST | `/admin/refunds/{id}/reject` | ADMIN/STAFF | `{reason}` | 驳回并恢复该课程学习权益 | 状态不允许 | trade_refund | 2026-10-23 |
| POST | `/admin/coupons` | ADMIN/STAFF | 模板字段，金额为分 | 创建草稿 | 参数不合法 | mkt_coupon_template | 2026-10-23 |
| PUT | `/admin/coupons/{id}` | ADMIN/STAFF | 同创建 | 只编辑草稿 | 非草稿 | mkt_coupon_template | 2026-10-23 |
| POST | `/admin/coupons/{id}/publish` | ADMIN/STAFF | 无 | 发布后可领取 | 非草稿 | mkt_coupon_template | 2026-10-23 |
| POST | `/admin/coupons/{id}/stop` | ADMIN/STAFF | 无 | 停止发放，已领券仍按快照使用 | 非发放中 | mkt_coupon_template | 2026-10-23 |
| GET | `/admin/coupons` | ADMIN/STAFF | 无 | 领取量、使用量、剩余量 | 401 | mkt_coupon_template | 2026-10-23 |
| GET | `/coupons/center` | STUDENT/ADMIN | 无 | 可领取的公开券 | 401 | mkt_coupon_template | 2026-10-23 |
| POST | `/coupons/{id}/claim` | STUDENT/ADMIN | 无 | 公开领取。库存和每人限领由条件更新与唯一键保证 | 领完；超限 | mkt_coupon_user | 2026-10-23 |
| POST | `/coupons/redeem` | STUDENT/ADMIN | `{code}` | 兑换码领取，同一用户同一券只能兑换一次 | 重复兑换 | mkt_coupon_user | 2026-10-23 |
| GET | `/coupons/mine` | STUDENT/ADMIN | 无 | 可使用、使用中、已使用、已过期、未生效 | 401 | mkt_coupon_user | 2026-10-23 |
| GET | `/coupons/mine/{id}` | STUDENT/ADMIN | 券 ID | 单张券快照，含适用范围 | 不存在 | mkt_coupon_user | 2026-10-23 |
| POST | `/coupons/mine/{id}/prices` | STUDENT/ADMIN | `{courseIds}` | 按服务端课程现价计算单课券后价。不可用时只返回状态 | 不存在 | 无落库 | 2026-10-23 |
| POST | `/coupons/quote` | STUDENT/ADMIN | `{courseIds}` | 服务端按课程现价计算可用券、不可用原因和最优单券。不支持叠券 | 课程不存在 | 无落库 | 2026-10-23 |
| POST | `/orders/pre-place` | STUDENT/ADMIN | 可增加 `userCouponId` | 服务端计算优惠并锁定该券。不接受前端金额 | 券不可用 | mkt_order_coupon、order | 2026-10-23 |
| POST | `/orders/pre-place` | STUDENT/ADMIN | 购买预下单（userId 取自网关上下文，金额服务端计算） | 原文接口，已实施 |
| GET | `/orders` | STUDENT/ADMIN | 我的订单（分页/状态筛选/创建时间排序） | 新增，已实施 |
| GET | `/orders/{id}` | 订单所有者/ADMIN | 订单详情（含课程价格快照） | 新增，已实施 |
| POST | `/orders/{id}/cancel` | 订单所有者/ADMIN | 取消待支付订单（重复取消幂等） | 新增，已实施 |
| GET | `/internal/courses/batch` | 内部（签名） | 批量课程快照：状态/分类/价格/有效期/可购买性 | 新增，已实施 |
| GET | `/internal/trade/orders/count-by-user/{userId}` | 内部（签名） | 用户订单数（删除前关联校验） | 新增，已实施 |
| GET | `/internal/trade/entitlements` | 内部（签名） | 是否已取得课程学习权益 | 新增，已实施 |
| GET | `/internal/trade/paid-courses` | 内部（签名） | 批量已购课程ID | 新增，已实施 |

网关新增路由：`/orders/**` → `kzai-trade`（18084）。

**M4 行为变更登记**

| 接口 | 变更 | 原因 |
|---|---|---|
| 全部接口响应 | `Long`（含所有雪花 ID）序列化为**字符串** | 19 位 ID 超出 JS 安全整数，前端按数字解析会静默丢精度，导致按 ID 编辑/下单命中错误记录 |
| GET `/admin/courses/audits` | 待审核列表改为**按每门课程最新一条审核记录**判定 | 修复历史待审核记录残留，已通过课程不再出现在待审核列表 |
| GET `/files/{id}/download` | 付费课程资料需**已支付学习权益**（免费课程公开） | 修复未购学员可通过资源 URL 下载付费内容的安全缺口 |
## M5 已登记（2026-10-19 实施完成）

> 学习域接口全部由新增的 `kzai-learning`（18085）承载，经网关 `/learning/**` 与 `/teacher/**` 暴露。
> 学习权益一律由服务端判定：只取网关注入的用户身份，不接受请求参数中的 `userId`/`paid`/`orderId`/`status`。

### kzai-learning（学习服务）

| 方法 | 路径 | 角色 | 请求参数 | 响应要点 | 错误状态 | 调用关系 | 状态 |
|---|---|---|---|---|---|---|---|
| GET | `/learning/lessons` | STUDENT/ADMIN | `pageNum`/`pageSize`/`keyword`/`status`（0全部 1学习中 2已完成） | 分页列表：课程名称、封面、章节数、进度百分比、已完成章节数、最近学习章节、最近学习时间、累计有效学习时长 | 401 未登录；403 非学员 | 无（仅本库 + course 名称冗余） | 新增，已实施 |
| POST | `/learning/lessons` | STUDENT/ADMIN | `{courseId}` | 入课结果；重复入课返回既有记录，不产生重复行 | 400 参数缺失；403 无权益（付费未购/已过期/已退款）；404 课程不存在 | Feign → trade `/internal/trade/entitlements`、course `/internal/courses/{id}` | 新增，已实施 |
| GET | `/learning/lessons/{courseId}` | STUDENT/ADMIN | 路径参数 | 课程学习详情：章节列表（含状态/进度/断点/试看标识）、`lastChapterId`、`lastVideoPositionSec`、`finishedChapterCount`、`chapterCount`、`progress` | 401；403 无权益；404 未入课 | 本库 | 新增，已实施 |
| PUT | `/learning/lessons/{courseId}/progress` | STUDENT/ADMIN | `{chapterId, videoPositionSec, videoDurationSec, completed?}` | 上报后回读详情；断点与有效时长服务端计算 | 400 参数非法；403 无权益/章节不可访问；404 章节不存在或不属于该课程 | 本库 + Feign 权益校验 | 新增，已实施 |
| GET | `/learning/courses/{courseId}/access` | STUDENT/ADMIN | 路径参数 | `{courseId, free, allowed, source, sourceText, reason, expireTime, enrolled, courseVisible}` | 401；403 课程不可见 | Feign → trade 权益、course 课程快照 | 新增，已实施 |
| GET | `/learning/courses/{courseId}/chapters` | STUDENT/ADMIN | 路径参数 | 学习用章节大纲（含 `freePreview`） | 403 无权益且非试看；404 | Feign → course 大纲 | 新增，已实施 |
| GET | `/learning/courses/{courseId}/chapters/{chapterId}` | STUDENT/ADMIN | 路径参数 | 章节内容 + 受控资源列表；试看章节对未购学员开放，其余需权益 | 403 无权益（未标记试看的付费章节）；404 | Feign → course + 权益校验 | 新增，已实施 |
| POST | `/learning/courses/{courseId}/chapters/{chapterId}/complete` | STUDENT/ADMIN | 路径参数 | 标记完成；回写章节状态、课程进度、`learning_lesson.status` | 403 无权益/章节越界；404 | 本库 | 新增，已实施 |
| GET | `/learning/plans` | STUDENT/ADMIN | 无 | 本周计划。`weekStart` 到 `weekEndExclusive` 为 Asia/Shanghai `[周一, 下周一)`。`summary` 为 `NONE`/`PAUSED`/`NOT_STARTED`/`PARTIAL`/`REACHED`/`INSUFFICIENT`。完成量来自 `CHAPTER_FINISH`，覆盖不足时 `finishedThisWeek` 为 null | 401 | learning_plan + learning_first_event | 2026-10-23 |
| POST | `/learning/plans` | STUDENT/ADMIN | `{courseId, weeklyGoal}` | 加入计划。身份取认证上下文。已有进行中或暂停计划时返回原计划且 `alreadyJoined=true`，不改本周目标。每周目标 1–20 | 401；403 无权益；课程无章节或已全部完成 | learning_plan、learning_plan_goal | 2026-10-23 |
| PUT | `/learning/plans/{id}/goal` | STUDENT/ADMIN | `{weeklyGoal}` | 新目标从下周一生效，不改本周和历史周 | 401；计划不存在或不属于当前用户 | learning_plan_goal | 2026-10-23 |
| POST | `/learning/plans/{id}/pause`、`/resume`、`/quit` | STUDENT/ADMIN | 路径中的计划 ID | 暂停区间内的首次完成不计入当周。退出后可重新加入，生成新计划 | 401；状态不允许 | learning_plan、learning_plan_pause | 2026-10-23 |
| GET | `/teacher/learning-stats` | TEACHER/ADMIN | `pageNum`/`pageSize`/`courseId?`/`dateFrom?`/`dateTo?` | 日期为 YYYY-MM-DD，Asia/Shanghai，两端同有或同无。无日期为累计口径。有日期时学习人数、时长、首次完课来自 `learning_activity_fact`，区间 `[开始日00:00, 结束日次日00:00)`。早于 `learning_fact_coverage.facts_since` 的区间 `learningCoverage=INSUFFICIENT`，学习指标为 null。`aiAdoptRate` 为 0～100 的 1 位小数；`aiSampleCount=0` 时为 null。提问数与采纳率在区间内按创建时间筛选，反馈是当前状态 | 401；403；日期缺一端、格式非法或开始晚于结束 | 进度累计或日事实 + 问答表 | 2026-10-23 口径落地 |
| GET | `/teacher/learning-stats/export` | TEACHER/ADMIN | 与查询相同的 `courseId`/`dateFrom`/`dateTo`，不分页 | CSV 含口径、覆盖说明、提问数、AI 样本数、被采纳数、采纳率 | 同上 | 与查询同一套过滤 | 2026-10-23 |
| GET | `/teacher/learning-stats/workbench-summary` | TEACHER/ADMIN | 无 | `aiReplies` 为可见 `SYSTEM_AI` 回复数；`aiUnchecked` 为其中未检查且未人工修正的数量。失败不把接口错误写成 0 | 401 | reply_extra | 2026-10-23 修正待检查字段 |
| POST | `/questions/{replyId}/helpful` 及 `/undo`、GET `/status` | 登录学员 | replyId | 校验回复、问题和课程可见性。只允许未修正的 SYSTEM_AI。重复标记幂等，撤销只删本人记录 | 401；不可见或非 AI 回复 | interaction_reply_helpful | 2026-10-23 补权限 |
| POST | `/internal/ai/knowledge/submit-kb` | 内部签名 | `{resourceId}` | 首次提交。课程服务按资源 ID 提供字节。aigc 校验 PDF/Markdown、大小、SHA-256，付费课程 PROTECTED。同资源版本重复提交返回原文档。状态为待审核，不直接发布 | 资料不存在、格式不支持、正文为空、超过 10MB | ai_knowledge_document | 2026-10-23 替换空 SHA 占位 |
| POST | `/internal/ai/knowledge/retry-resource` | 内部签名 | `{resourceId}` | 失败重试。按资源 ID 和来源版本找回原文档并入队，不新建文档、不改访问级别、不改来源版本。已有待处理或执行中任务时返回同一 `jobId`，`alreadyQueued=true`，`phase=INDEXING` | 无签名 403；待审核、已撤回、已删除、来源无效 | ai_knowledge_index_job | 2026-10-23 |
| POST | `/teacher/resources/{id}/retry-kb` | TEACHER/ADMIN（课程归属且本人上传） | 路径中的资源 ID | 教师端重试。服务端解析文档，不接受前端传入 docId。成功返回 `jobId`、`phase=INDEXING`、`alreadyQueued`、`accessLevel` | 403 其他教师；未审核、已撤回、已替换或已删除不能重试 | 转发 aigc retry-resource | 2026-10-23 |
| POST | `/internal/ai/knowledge/withdraw-resource` | 内部签名 | `{resourceId}` | 资料删除或替换后撤回旧来源，审核轮次加 1，取消未完成索引任务 | | | 2026-10-23 |
| GET | `/internal/courses/resources/{id}/content` | 内部签名 | 资源 ID | 返回 Base64 字节和课程是否免费。不接受调用方路径，不按学员身份公开付费资料 | 资料不存在 | course_resource 文件 | 2026-10-23 |
| GET | `/internal/learning/lessons/count-by-user/{userId}` | 内部（签名） | 路径参数 | 用户学习记录数（删除前关联校验）；服务不可用返回负数 | 403 无内部签名 | 无 | 新增，已实施 |

### kzai-course（课程服务，M5 行为扩展）

| 方法 | 路径 | 角色 | 说明 | 状态 |
|---|---|---|---|---|
| GET | `/files/{id}/download` | 登录 | **新增 HTTP Range 支持**：合法 Range → 206 + `Content-Range`；越界/倒置/非法表达式 → 416；无 Range → 200 全量 | 行为扩展，已实施 |
| PUT | `/teacher/chapters/{id}/preview` | TEACHER/ADMIN（课程归属） | 设置章节试看标识与视频时长（写入 `course_chapter_extra`） | 新增，已实施 |
| GET | `/internal/courses/{id}/outline` | 内部（签名） | 学习所需课程 + 章节 + 资源大纲（含试看标识） | 新增，已实施 |

### kzai-trade（交易服务，M5 行为扩展）

| 方法 | 路径 | 角色 | 说明 | 状态 |
|---|---|---|---|---|
| GET | `/internal/trade/entitlements` | 内部（签名） | 权益判定**增强**：联合订单主体状态 + 订单明细状态 + 退款状态 + 有效期 + 课程可访问性；退款/过期/历史歧义状态一律不授予 | 行为扩展，已实施 |

**M5 行为变更登记**

| 接口 | 变更 | 原因 |
|---|---|---|
| 全部接口响应 | `Long` 继续序列化为字符串 | 沿用 M4 决策 D4-8，雪花 ID 精度 |
| `GET /files/{id}/download` | 支持 Range / 206 / 416；视频在线播放与拖动 | 断点续学需要真实分段读取，且非法 Range 必须受控拒绝 |
| `GET /learning/**` | 付费资源访问重新校验订单主体与退款状态 | 仅凭订单明细状态无法排除已取消订单/已退款明细 |

---

## M6 新增接口：kzai-aigc（AI 服务，端口 18086）

网关路由：`/chat/**` → `lb://kzai-aigc`，`/session/**` → `lb://kzai-aigc`。
所有接口要求登录（网关统一鉴权），角色限定 `STUDENT/TEACHER/STAFF/ADMIN`。
消息正文仅存 Redis（`CHAT:{userId}_{sessionId}`，TTL 7 天）；`chat_session` 只存元数据（8 原始字段零改动）。

### 会话管理

| 方法 | 路径 | 请求 | 响应 | 失败 |
|---|---|---|---|---|
| POST | `/session` | 无 | `{sessionId, id, createTime}`；真实写入 MySQL | 401 |
| GET | `/session/history` | 无 | 数组，按 `updateTime` 倒序，**最多 30 条** | 401 |
| GET | `/session/{sessionId}` | 路径参数 | `{sessionId,title,createTime,updateTime,messages:[{role,content}]}`，messages 来自 Redis | 401；403 非本人会话 |
| PUT | `/session/history` | `{sessionId, title}` | `{sessionId, title}`；重命名落 MySQL | 400 空标题；401；403 |
| DELETE | `/session/history` | `{sessionId}` | `{sessionId, deleted:true}`；MySQL + Redis 同步清理 | 401；403 |
| GET | `/session/hot` | 无 | 热门问题字符串数组（**配置预置，非模型生成**） | 401 |

### 对话

| 方法 | 路径 | 请求 | 响应 | 失败 |
|---|---|---|---|---|
| POST | `/chat` | `{sessionId, message, messageId?}` | `text/event-stream`；`1001` 文本增量、`1002` 终态（`{terminal:true, code, message, mock, provider, stopped, persisted, content}`） | 401；403；终态带 `UNAUTHORIZED/RATE_LIMIT/TIMEOUT/UPSTREAM_ERROR` |
| POST | `/chat/stop` | `{sessionId}` | `{stopped:true, sessionId}`；写 `AI:STOP:{sessionId}`（TTL 5 分钟） | 401；403 |
| POST | `/chat/text` | `{sessionId, message, messageId?}` | `{reply, mock, provider}`；非流式（M10 课程问答自动回复基础） | 401；403 |
| POST | `/chat/templates` | `{}` | `{welcome, mock, provider}`；欢迎语来自配置，不冒充模型生成 | 401 |

### 内部接口（签名保护）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal/ai/sessions/count-by-user/{userId}` | 用户 AI 会话数；供用户服务 `ChatSessionAssociationGuard` 删除前校验（403 无签名） |

### Redis 结构

| Key | 结构 | TTL | 用途 |
|---|---|---|---|
| `CHAT:{userId}_{sessionId}` | **Redis List**（M7 恢复原始设计；每条消息一个元素，助手消息可带 `cards`；兼容旧 String 格式并自动无损迁移） | 7 天 | 会话记忆 |
| `AI:STOP:{sessionId}` | String "1" | 5 分钟 | 停止标识，生成结束清理 |
| `AI:MSG:{messageId}` | String "1" | 7 天 | 请求幂等，防重复写用户消息 |
| `AI:PENDING_CLEAN:{sessionId}` | String userId | 30 天 | 删除补偿重试队列 |
| `AI:PROPOSAL:{userId}_{sessionId}` | JSON（购买确认提案：proposalId/requestId/courseIds/confirmed） | 10 分钟 | 预下单确认门禁（M7） |
| `AI:MIG:CHAT:*` | String 迁移锁 | 10 秒 | 旧格式→List 无损迁移互斥（M7） |

### M6 边界声明（不在本阶段实现）

- ~~M7：CourseTools / OrderTools / Tool Calling / 课程卡片 / 订单卡片~~（**M7 已实现，见下节**）
- M8：RouteAgent 与多智能体路由、Prompt 版本管理后台
- M9：RAG、Embedding、Elasticsearch 知识库
- M10：课程问答 AI 自动回复（`/chat/text` 仅提供基础能力）
- M12：AI 批量评测
- M14：STT / TTS 语音能力

---

## M7 接口扩展（2026-10-19）

### SSE 协议变更（`POST /chat`）

| 事件 | 变更 | 说明 |
|---|---|---|
| 1001 | 不变 | 文本增量 |
| 1003 | **新增发送** | 工具结果卡片：`{"code":1003,"data":{"type":"COURSE_CARD"\|"ORDER_CARD","cardId":"...","payload":{...}}}`；按 cardId 去重，必在唯一 1002 之前 |
| 1002 | **字段调整** | `content`（全文重复传输）→ **`contentLength`**；新增 `cardCount`；真实模型时可选 `promptTokens/completionTokens/totalTokens` |

**1003 COURSE_CARD payload**（全部来自课程服务真实数据）：
`{courseId(String), name, coverUrl, price(分), priceYuan("199.00"), introduce, chapterCount, free, purchasable, reason}`

**1003 ORDER_CARD payload**（全部来自交易服务真实返回）：
`{orderId(String), status, statusText, totalAmount(分), totalAmountYuan, requestId, idempotentHit, items:[{courseId,name,coverUrl,price,priceYuan,statusText}]}`

未知 `type` 前端静默忽略（向前兼容）。

### 会话详情响应变更

`GET /session/{sessionId}` 的 `messages[]` 中助手消息新增可选字段 `cards`（即上述 1003 载荷数组），用于历史回放恢复卡片；回放不触发任何工具调用。

### 新增内部接口（kzai-course，签名保护，网关不可达）

| 方法 | 路径 | 请求 | 响应 | 失败 |
|---|---|---|---|---|
| GET | `/internal/courses/ai-search` | `?keyword=&limit≤10` | `R<List<CourseAiVO>>`；仅学员可见课程（上架+分类启用+未删除）；空结果为空数组 | 403 无签名 |
| GET | `/internal/courses/{courseId}/ai` | 路径参数 | `R<CourseAiVO>`；不可见/不存在返回 `data=null`（AI 按「未找到」处理，不泄露内部状态） | 403 无签名 |

`CourseAiVO`：`id(String), name, coverUrl, price(分), priceYuan, introduce, chapterCount, courseType, free, status, validDuration, purchasable, reason`。
原始系统设计中的 `/courses/{id}/ai` 按内部契约适配实现（不新开网关入口）。

### 服务间 Feign（kzai-aigc → 内部签名）

| 目标 | 客户端 | 调用 | 说明 |
|---|---|---|---|
| kzai-course | `CourseClient` | `GET /internal/courses/ai-search`、`/{id}/ai`、`/batch` | 课程工具只读查询 |
| kzai-trade | `TradeClient` | `POST /orders/pre-place`（复用 M4 契约） | 预下单：身份取签名头 UserContext；requestId 由服务端确认提案派生；不调用任何支付接口 |

### 非功能性变更

- `/chat/text` 与 `/chat` 共享同一会话单飞锁：已有生成任务时 `/chat/text` 返回业务码 400「该会话已有进行中的生成任务」。
- 自动标题改为条件更新（`title IS NULL OR title=''` 才写入），重命名不再被异步标题竞态覆盖。
- 新增配置：`kzai.aigc.mock-tool-script`（默认 true）、`tool-search-limit`（默认 5）、`proposal-ttl-minutes`（默认 10）。

---

## M8 接口扩展（2026-10-20）

### SSE 终态字段增补（`POST /chat` 1002）

新增可选字段 `agent`：本轮路由命中的智能体标识（`RECOMMEND/CONSULT/BUY/KNOWLEDGE`），
寒暄/无关直接回复为 `OTHER`；多智能体开关关闭时缺省。仅展示名称，不暴露路由 Prompt 内容。
1001/1003/1002 顺序与去重语义不变。

### Prompt 管理接口（kzai-aigc，管理员专用，`@RequireRoles(ADMIN)`）

方案为用户确认的「内置默认 + Nacos 直发」——**无草稿/历史/回滚持久化表**（暂不建表）。

| 方法 | 路径 | 请求 | 响应 | 失败 |
|---|---|---|---|---|
| GET | `/admin/ai/prompts` | - | 五类生效模板（systemPrompt/welcomeText/hotQuestions/source/version）+ Nacos 状态（available/hasConfig/inSync/appliedSource/appliedVersion） | 401；403 |
| GET | `/admin/ai/prompts/{agentType}` | 路径参数 | 单类生效模板 | 401；403；400 未知类型 |
| POST | `/admin/ai/prompts/{agentType}/preview` | `{systemPrompt, welcomeText?, hotQuestions?}` | `{valid, errors[], rendered?}`；纯校验无副作用、不执行工具 | 401；403 |
| POST | `/admin/ai/prompts/{agentType}/publish` | `{systemPrompt, welcomeText?, hotQuestions?}` | `{published, agentType, version, error?}`；与其余四类当前生效内容合成完整文档发布 | 401；403；published=false 时附 error |
| GET | `/admin/ai/prompts/audit` | - | 发布审计计数 | 401；403 |

### Gateway 路由

新增 `kzai-aigc-admin-prompts`（`Path=/admin/ai/prompts/**` → `lb://kzai-aigc`），
置于 `/admin/**` 通配路由之前（精确优先），不影响 `/admin/users/**`、`/admin/courses/**` 等。

### Nacos Prompt 配置约定

- Data-ID：`kzai-ai-prompts.json`；Group：`tj.ai`（对齐原始设计命名风格）
- 结构：`{"version":"pub-yyyyMMddHHmmss","prompts":{"ROUTE|RECOMMEND|CONSULT|BUY|KNOWLEDGE":{"systemPrompt","welcomeText","hotQuestions":[...]}}}`
- 发布链：校验（长度/`${}` 注入/密钥字样/ROUTE 枚举完整性）→ Nacos v1 OpenAPI 写入 → **有界回读一致性校验**（≤5s，容忍 Derby 持久化延迟）→ 原子热生效 + Redis 缓存（`AI:PROMPT:EFFECTIVE`，重启恢复用）
- 30s 轮询兜底检测进程外变更；配置非法或 Nacos 不可用时保留最后有效配置
- 运行中的生成使用启动时读取的 Prompt 快照，不因发布/回滚中途切换

### AI 服务多智能体配置

`kzai.aigc.multi-agent-enabled`（默认 true，关闭则保持 M7 单 Agent 行为）；`kzai.aigc.route-audit-verbose`（默认 false）。

---

## M9 接口扩展（2026-10-20）

### 知识库管理接口（kzai-aigc，`@RequireRoles(ADMIN)`）

| 方法 | 路径 | 请求 | 响应 | 失败 |
|---|---|---|---|---|
| POST | `/admin/ai/knowledge/documents` | multipart：file(PDF/MD≤10MB)+title+courseId?+accessLevel? | 文档元数据（含 sha256/charCount/chunkCount/indexStatus/reviewStatus） | 401；403；400 类型/魔数/大小/SHA-256 重复/课程不可见 |
| GET | `/admin/ai/knowledge/documents` | page/size/keyword/status | 分页列表 | 401；403 |
| GET | `/admin/ai/knowledge/documents/{id}` | - | 文档详情 | 401；403；400 |
| POST | `/admin/ai/knowledge/documents/{id}/review` | `{publish:true\|false}` | 审核发布/撤回；发布触发解析+索引任务 | 401；403 |
| POST | `/admin/ai/knowledge/documents/{id}/reparse` | - | 重解析任务入队 | 401；403；400 已有任务 |
| POST | `/admin/ai/knowledge/documents/{id}/reindex` | - | 重建索引任务入队 | 401；403；400 已有任务 |
| DELETE | `/admin/ai/knowledge/documents/{id}` | - | 逻辑删除 + ES 向量清理任务 | 401；403 |
| GET | `/admin/ai/knowledge/jobs/{id}` | - | 任务状态 | 401；403 |
| POST | `/admin/ai/knowledge/search-test` | `{question, topK?}` | 真实 ES TopK 片段+score+来源 | 401；403 |

### 向量接口（原始设计保留）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/embedding` | ADMIN | `{docId}` 触发重建索引（真实解析/切片/Embedding/ES 链路） |
| GET | `/embedding/search` | 登录用户 | `{question, topK?}`：以可信上下文检索；ES 不可用返回 `degraded=true` 与降级原因；不泄露无权文档 |

### SSE 1003 新增卡片类型（M9）

`RAG_SOURCE`：`{docId, chunkId, chunkIndex, docTitle, courseId, score, snippet, provider}`。
随 assistant 消息持久化（Redis List cards 字段），历史回放免检索恢复；1001→1003→1002 顺序不变。

### Gateway 路由

`kzai-aigc-admin-knowledge`（`/admin/ai/knowledge/**`→aigc，置于 `/admin/**` 前）；`kzai-aigc-embedding`（`/embedding/**`→aigc）。

### Elasticsearch 约定（M9）

- 物理索引 `kzai-knowledge-v{N}` + 别名 `kzai-knowledge-alias`（读写均走别名；重建=新版本索引 bulk→数量核验→别名原子切换→核验成功前旧索引不动）
- 切片文档：`docId/chunkId(=docId_chunkIndex)/chunkIndex/indexVersion/docTitle/courseId/accessLevel/reviewStatus/text/vector`
- `vector`: `dense_vector`，`dims` 以真实 Embedding 返回实测（DashScope text-embedding-v1 为 1536；Mock 为 256），`similarity: cosine`
- 归一化口径：cosine 下 ES `_score = (1+cos)/2 ∈ [0,1]`，阈值 0.6 直接与 `_score` 比较
- 检索过滤：ES 预过滤（reviewStatus=1 + accessLevel/courseId 候选）→ MySQL 实时权威复核（已发布/未删除/权益 fail-closed）→ TopK6

---

## M10 课程问答与 AI 异步回复（2026-10-20）

### 对外接口（Gateway）

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/questions` | 学员 | 创建问题（课程存在且已上架；同事务创建 AI 任务） |
| GET | `/questions` | 学员 | 课程问答列表（`courseId`） |
| GET | `/questions/mine` | 学员 | 我的问答 |
| GET | `/questions/{id}` | 学员 | 详情（回复树 + AI 来源标识；隐藏仅教师可见） |
| POST | `/questions/{id}/replies` | 学员 | 追问/回答（`targetReplyId` 须属本问题且未隐藏） |
| GET | `/teacher/questions` | TEACHER/ADMIN | 问答管理列表（本人课程；ADMIN 全量；`courseId`/`hidden` 过滤） |
| GET | `/teacher/questions/{id}` | TEACHER/ADMIN | 管理详情 |
| POST | `/teacher/questions/{id}/reply` | TEACHER/ADMIN | 教师回答 |
| POST | `/teacher/questions/{id}/regenerate` | TEACHER/ADMIN | 重新生成 AI 回复（已人工修正则拒绝） |
| PATCH | `/teacher/questions/{id}/visibility` | TEACHER/ADMIN | 问题隐藏/恢复 |
| POST | `/teacher/replies/{id}/correct` | TEACHER/ADMIN | 人工修正（留 `prev_content` + 操作日志） |
| PATCH | `/teacher/replies/{id}/visibility` | TEACHER/ADMIN | 回复隐藏/恢复 |
| POST | `/teacher/replies/{id}/feature` | TEACHER/ADMIN | 精华标记（≠AI 采纳事件） |

### 内部接口（内部签名保护，网关拒绝外部访问）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/ai/generate` | aigc：课程问答 AI 生成（RAG + 生成；返回 `content/modelCode/sources`） |
| GET | `/internal/learning/interactions/count-by-user/{userId}` | learning：提问 + 回答计数（含 AI 系统回复按 `user_id` 归属），供 user 删除保护 |

### Gateway 路由（顺序敏感）

`kzai-learning-questions`（`/questions/**`）、`kzai-learning-teacher-questions`（`/teacher/questions/**`）、`kzai-learning-teacher-replies`（`/teacher/replies/**`）均置于 `kzai-course-teacher`（`/teacher/**`）之前。

### 数据表

- `ai_question_reply_task`：状态机 0/1/2/3，`attempt≤5`，租约 `worker_id/lease_until`，`UNIQUE(question_id, active_flag)`（生成列）。
- `interaction_reply_extra`：`reply_id` PK；`source`(TEACHER/STUDENT/SYSTEM_AI)、`model_code`、`ai_task_id`、`corrected/corrector/correct_time`、`prev_content`、`featured`、`sources`。
- 原 `interaction_question`/`interaction_reply` 零改动。

### 学情统计

`questionCount` 真实接入；`aiAdoptRate` 维持 `null`（口径未确认）。

---

## M11 站内通知与跨服务事件（2026-10-20）

### 对外接口（Gateway：`/notifications/**` → kzai-user）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/notifications` | 列表：`page/size/type/readStatus`；创建时间倒序；仅本人未删除；Long ID 以字符串返回 |
| GET | `/notifications/unread-count` | 真实未读数量（前端铃铛使用） |
| GET | `/notifications/{id}` | 详情；首次打开自动标记已读 |
| PUT | `/notifications/{id}/read` | 单条已读（幂等，不覆盖首次 `read_time`） |
| PUT | `/notifications/read-all` | 全部已读（仅本人未读且未删除，返回更新条数） |
| DELETE | `/notifications/{id}` | 接收者维度逻辑删除（幂等；重复删除受控 404） |

### 内部接口

| 方法 | 路径 | 服务 | 说明 |
|---|---|---|---|
| POST | `/internal/notifications` | user | 业务服务投递事件（内部签名；无签名 403） |
| POST | `/internal/users/operation-logs` | user | M11 修复：路径由 `/internal//users` 改为 `/internal/users`，审计恢复真实落库 |

### 事件与 eventKey

| 类型 | eventKey | 收件人 | 触发点 |
|---|---|---|---|
| `AI_REPLY` | `AI_REPLY:{replyId}` | 问题提出者 | learning AI 回复事务提交且任务 SUCCESS 后 |
| `QUESTION_REPLY` | `QUESTION_REPLY:{replyId}` | 提问者 / 被追问回复作者 | 教师回答、学员回答或追问（事务内） |
| `COURSE_APPROVED` | `COURSE_APPROVED:{auditId}` | 课程负责人 | course 审核通过事务内 |

### 数据表

- `sys_notification`：`UNIQUE(recipient_id, event_key)`、`idx_recipient_read`、`idx_biz`
- `sys_event_outbox`（learning 库与 course 库各一张）：`UNIQUE(event_key)`、`idx_status_retry`、
  租约 `worker_id/lease_until`、`attempt≤5`、`error`、`request_id`、`payload_json`

### 前端

- `MainLayout`：通知铃铛（真实未读数 + 徽标 + 点击进通知中心；路由切换与 30s 轮询刷新）
- `/#/notifications`：通知中心（全部/未读/已读、类型筛选、分页、查看、已读、全部已读、删除、业务跳转、空态与异常提示）

---

## M12 AI 批量评测（2026-10-20）

### 评测管理接口（kzai-aigc，管理员专用 `@RequireRoles(ADMIN)`，网关 `/eval/**` → aigc）

| 方法 | 路径 | 说明 | 失败 |
|---|---|---|---|
| GET | `/eval/cases` | 用例分页列表（`page/size/category/keyword`；含标注元数据） | 401；403 |
| POST | `/eval/cases` | 新增用例（问题 1–500 字、六类分类严格枚举、期望意图枚举、标注理由必填） | 400 校验失败 |
| PUT | `/eval/cases/{id}` | 编辑用例 | 400；404 |
| DELETE | `/eval/cases/{id}` | 删除用例（连同标注元数据） | 404 |
| POST | `/eval/cases/import` | CSV 导入（≤2MB、表头精确匹配、逐行校验、重复=question 相同跳过并报告行号） | 400 表头/大小非法 |
| GET | `/eval/cases/regression` | 回归用例集列表 | 401；403 |
| POST | `/eval/run` | 创建评测批次（`name/execMode/caseIds?`；`uk_active_run` 生成列唯一约束防重复批次；REAL 无 Key → status=5 不可执行，不退化 MOCK） | 400 模式非法/无用例/已有运行中批次 |
| GET | `/eval/runs` | 批次历史分页（含模型/Embedding/知识库版本/Prompt 快照/执行人） | 401；403 |
| GET | `/eval/runs/{runId}` | 批次详情 + 进度（doneCases/totalCases） | 404 |
| GET | `/eval/runs/{runId}/results` | 结果列表（每用例最新 attempt；`status` 过滤；`allAttempts=true` 返回全部尝试行） | 404 |
| GET | `/eval/runs/{runId}/summary` | 指标汇总（意图准确率/按意图/混淆矩阵/Hit@6/拒答/延迟 P50 P95/token，全部附分子分母与口径说明） | 404 |
| GET | `/eval/summary` | **原始设计接口（5.2.3）**：默认返回最新「有结果数据」批次的汇总（自动跳过 REAL 未配置等不可执行批次），可显式 `runId` | 400 尚无批次 |
| GET | `/eval/runs/{runId}/export` | CSV 导出（UTF-8 BOM + 公式注入防护；含 question/来源/模型/模式/判定依据/失败原因/token 来源） | 404 |
| POST | `/eval/runs/{runId}/retry` | 失败用例重试（仅执行失败/超时；**新 attempt 新行**，历史可追溯） | 400 运行中/无可重试 |
| POST | `/eval/results/{id}/add-to-regression` | 失败用例转入回归集 | 404 |

### 数据表（`sql/13_m12_evaluation.sql`，用户已批准；`sql/13a_m12_eval_seed.sql` 为脚本生成的标注 SEED）

| 表 | 关键结构 |
|---|---|
| `ai_eval_run` | 批次；`active_flag` 生成列 + `UNIQUE`（全局至多一个运行中批次）；状态机 0运行中/1成功/2失败/3部分成功/4已终止/5不可执行；快照 provider/model/embedding/kb 物理索引/Prompt 摘要 |
| `ai_eval_result` | 单条结果；`UNIQUE(run_id, case_id, attempt)`（重试=新行）；status 0待执行/1完成/2执行失败/3超时/4跳过（执行失败与判定失败分离）；token 仅 provider 真实 usage（否则 NULL/unavailable；MOCK=mock_na） |
| `ai_eval_case_meta` | 标注扩展 1:1（should_refuse/needs_citation/expect_tool/refuse_reason/note/data_deps/source/is_regression）；原始 `ai_eval_case` 11 字段零改动 |
| `ai_eval_case`（原始） | `actual_intent/actual_doc_ids/latency_ms/token_usage/passed` 仅作"最近一次执行"镜像；历史事实以 `ai_eval_result` 为准 |

### 执行与安全隔离（任务书第六部分）

- 评测身份服务端设定（`EVAL_USER_ID=1`；默认学员视角，`data_deps=admin_test_access` 用例切换管理员测试视角）；不触碰 RedisChatMemory/会话/通知 Outbox/业务表/知识库状态
- BUY 写工具 Dry-run：验证路由与工具选择记录，绝不调用 TradeClient；MOCK 模式 `write_tool_attempt=NULL`
- 拒答/引用/泄露自动判定基于公开启发式规则（`EvalRefusalRules`）；泄露类仅标记 `leak_suspected` 待人工复核，不宣称自动判定即真实拒答准确率

### 前端

- `/#/admin/ai/evaluations`：管理员评测看板（用例管理/CSV 导入/批次历史/指标看板/混淆矩阵/失败详情/转回归/导出）；MOCK 批次页面显著标识「MOCK 协议验证，非真实 AI 质量评测」；空批次显示「尚无有效数据」不展示虚假百分比

---

## M13 平台监管、审计、参数与看板（2026-10-20）

### 管理接口（@RequireRoles(ADMIN)；网关精确路由前置）

| 服务 | 方法 | 路径 | 说明 |
|---|---|---|---|
| aigc | GET/POST | `/admin/supervision/sessions`、`/{sessionId}`、`/{sessionId}/mark`、`/reviews/{id}/handle`、`/{sessionId}/reviews` | AI 会话监管（分页/筛选/详情/复核标记与处理/历史；消息过期如实标注） |
| learning | GET/PATCH | `/admin/supervision/questions`、`/{id}`、`/questions/{id}/visibility`、`/replies/{id}/visibility` | 问答监管（跨课程查询/隐藏恢复带原因；status 原始语义不变） |
| user | GET | `/admin/operation-logs`、`/{id}`、`/export` | 操作日志（分页筛选/详情/CSV BOM+防注入；result/requestId 承载于 detail JSON） |
| aigc | GET/POST | `/admin/system-settings`、`/draft`、`/publish`、`/publishes` | 系统参数（草稿/乐观锁 version/发布 Nacos+有界回读+热生效+runtime_verified/失败保留最后有效配置/历史） |
| user | GET | `/admin/dashboard/overview?days=1/7/30` | 运营看板（真实聚合；模型指标仅 REAL，MOCK 隔离；指标级降级） |
| learning | GET/POST | `/admin/outbox`、`/{id}/retry` | FAILED Outbox 查询与受控重投（eventKey 不变，Worker 真实投递，审计落库） |
| 内部 | GET | `/internal/ai/stats/overview`、`/internal/learning/stats/questions`、`/internal/courses/stats/count` | 看板跨服务统计（签名） |

### 数据表（`sql/14_m13_management.sql`，用户批准含六类落实要求；幂等已验证）

| 表 | 说明 |
|---|---|
| `sys_session_review`(14 列) | 会话复核（只插入保历史；不存消息正文） |
| `sys_config_param`(16 列) | 参数生效值+草稿+**version 乐观锁**+范围 |
| `sys_config_publish`(14 列) | 发布历史（**runtime_verified 区分发布与运行时生效**；失败保留回退依据） |
| `ai_model_call_log`(17 列) | 真实模型调用日志（REAL/MOCK 严格区分；每业务轮一条；token 缺省 NULL；90 天保留） |
| `sys_operation_log` | 补 `idx_create_time`（查重幂等；不改既有字段/记录） |

### 运行时行为变更

- 消息格式：assistant 消息新增可选 `agent` 元数据（向后兼容，旧消息为 null）
- `chat.daily-limit` 每日限额：Redis 原子计数、服务端身份、Redis 故障 fail-open
- TopK/阈值/历史上限运行时读取 `DynamicAiParams`（Nacos→Redis→默认三层生效链）
- 审计补点：登录成功/失败、课程审核、知识库发布/删除、Prompt 发布、评测启动、会话监管、参数发布、Outbox 重投

---

## M14 语音系统（2026-10-20）

### 接口（kzai-aigc；网关 `/audio/**`；登录用户）

| 方法 | 路径 | 请求 | 响应 | 失败 |
|---|---|---|---|---|
| POST | `/audio/stt` | multipart `file`（wav/mp3/ogg/webm/m4a/mp4/flac/aac，≤5MB） | `R<{text, provider, modelCode, mock, requestId}>` | 400 空文件/超大/格式不支持/**扩展名伪造**/Provider 错误（受控码见 SpeechValidationException） |
| POST | `/audio/tts-stream` | form `text`（≤500 字） | **audio/wav 二进制流** + 头 `X-Tts-Provider/Model/Mock/Streaming`（**非流式如实标注**） | 400 空文本/超长/功能关闭/繁忙（JSON R 结构，前端按 Content-Type 区分） |
| GET | `/audio/capabilities` | - | `R<{enabled, mock, sttStreaming, ttsStreaming, sttMaxBytes, sttMaxSeconds, ttsMaxChars, notice}>` | - |

### 红线与行为
- **音频不落库**：仅内存处理（multipart→byte[]），请求结束即释放；不入 MySQL/Redis/知识库/审计正文/临时缓存目录。
- **MOCK 标识**：无凭据时 Provider=MOCK，页面常显「MOCK 语音演示，非真实语音识别/合成」；真实 STT/TTS 未验证。
- **STT 红线**：识别文字仅回填输入框，用户确认后才走既有 `/chat` 发送流程。
- 语音参数（`sys_config_param` 数据行，`sql/15_m14_voice_params.sql`）：`voice.enabled/stt.max-upload-bytes/stt.max-seconds/tts.max-chars/voice.max-concurrent`（Nacos 热更新生效）。
- 调用日志：`ai_model_call_log` source=STT/TTS；token 恒 NULL；音频时长/字符数为独立语音用量（结构化日志）。

---

## M15 部署与交付（2026-10-20）

### 部署体系（deploy/）
| 文件 | 说明 |
|---|---|
| `deploy/compose.yml` | 12 服务编排（mysql8/redis7/nacos2.2/es8.11.4/7 微服务/nginx），project=kzai，healthcheck 全覆盖，7 持久化卷 |
| `deploy/docker/Dockerfile.service` | 多阶段统一微服务镜像（ARG MODULE；Maven 构建→JRE17；TCP 健康检查） |
| `deploy/docker/Dockerfile.frontend` | node20 构建 → nginx1.27（SPA+SSE 反代+12m 上传） |
| `deploy/nginx/default.conf` | SPA try_files + /api/ 反代 gateway + SSE 长连接 + 语音上传 |
| `deploy/.env.example` | 端口/密码/JWT/内部签名/Nacos 鉴权/AI 开关（默认 MOCK 零计费） |

### 数据库初始化
`sql/` 挂载 mysql `/docker-entrypoint-initdb.d`，首启按字典序自动执行（01→15；13/13a 已修复移除内嵌 USE 语句——迁移不得绑定 schema 名）。

### 干净环境验证（隔离等价）
`course_stack_ai_m15` 独立库（33 表与生产零差异）+ namespace=m15 服务集群：15 迁移全过、三角色闭环、重启持久化——25/25（scripts/e2e-m15-deploy.sh）。容器编排实测未执行（环境无 Docker，用户批准）。

---

## M15.5 内部契约与配置变更（2026-10-21，真实 Embedding + RAG）

### 内部契约（向后兼容）
- `GET /internal/courses/{courseId}/ai`（kzai-course，Feign `CourseClient.aiDetail`）：响应 `CourseAiVO` 新增**可选**字段 `chapters: [{name, order}]`（真实 `course_chapter` 记录，`chapter_order` 升序；仅详情接口返回，搜索列表不填）。旧调用方零影响（`ignoreUnknown` 兼容）。
- 用途：AI 工具 `getCourseDetail` 透传真实章节标题——模型章节目录的唯一合法数据源（防编造约束，D15-13）。

### 配置项（kzai-aigc）
| 键 | 默认 | 说明 |
|---|---|---|
| `kzai.aigc.embedding-provider` | mock | mock / dashscope（条件注解已修复统一读本键） |
| `kzai.aigc.embedding-model` | text-embedding-v1 | 真实 Embedding 模型名（本轮验证 text-embedding-v4） |
| `kzai.aigc.real-index-guard.expected-model` | text-embedding-v4 | REAL 检索守卫期望模型（漂移即启动失败） |
| `kzai.aigc.real-index-guard.real-prefix` | kzai-knowledge-real-test | REAL 检索守卫索引前缀 |
| env `KZAI_EMBEDDING_PROVIDER` / `KZAI_EMBEDDING_MODEL` / `KZAI_ES_URL` | — | compose.yml 已修正（原 `KZAI_AI_KNOWLEDGE_*` 为死键） |

### 运行时行为
- REAL Embedding 模式下检索前置守卫 `RealIndexGuard`（fail-closed）：Provider=MOCK / 别名前缀不符 / mapping 维度≠实测 → 检索降级 `REAL_INDEX_GUARD:*`（结果不伪造）。
- ES 网关 HTTP 超时 connect 2s / read 5s（ES 故障快速降级，不再挂死对话）。
- ROUTE prompt 已更新（KNOWLEDGE 覆盖平台规则政策类问题；version pub-20261021040616）。

---

## M15.7 评测安全与预算机制（2026-10-21）

### 行为变更（kzai-aigc 内部，无外部接口签名变化）
1. **批次级模型调用预算**（EvalRunRunner）：route 上限=用例数、chat 上限=用例数×2+2；超限剩余用例自动置 SKIPPED（fail_reason=SKIPPED:模型调用预算超限），保留已完成结果。
2. **批次级依赖预检**（precheck）：REAL 模式 KNOWLEDGE 用例执行前校验 ①ai_eval_case_meta 存在 ②expected_doc_id 格式 ③黄金文档已在评测索引（countByDocId）——失败置 SKIPPED + `PRECHECK_NO_META / PRECHECK_BAD_DOC_ID / PRECHECK_CORPUS_MISSING / PRECHECK_ES_UNAVAILABLE`（语料缺失不计模型错误、不伪装成功）。
3. **拒答指标补齐**（EvalMetricsService.summary.refusal）：新增显式混淆矩阵 `confusion{tp,fp,fn,tn}`（正类=should_refuse=1）与 `refusalPrecision`（TP/(TP+FP)，分母 0 → null）；`correctRefusalRate` 更名语义为 Recall（保留旧字段名兼容）。
4. **max_tokens 限制说明**：M6.1 DashScopeChatOptions.maxTokens 为死字段（builder 缺失/setMaxTokens 损坏/Jackson 不映射），输出上限不可经 API 设置——费用约束由调用次数预算 + TopK + 批次 30min deadline 承担（EvalSafetyAssertionTest 锁定该缺陷，依赖升级后启用）。
5. **安全断言测试**（EvalSafetyAssertionTest，4 用例）：Dry-run register 仅含受控回调、同名回调零副作用执行体、评测调用形态、M6.1 maxTokens 缺陷锁定。

### 评测操作说明
- 指定子集执行：`POST /eval/run {"name","execMode":"REAL","caseIds":["..."]}`（caseIds 可选；管理员鉴权）。
- 执行前门槛：REAL 装配日志 ×2、RealIndexGuard 启动日志、`active_runs=0`、业务表基线记录（orders/order_detail/idempotent/notification/outbox 计数）、预算声明。
- 执行后核验：业务五表计数与基线对比（零副作用）、TOOL_AUDIT（Dry-run 外应为 0）、ROUTE_AUDIT 全量溯源、批次 summary/results 留痕。

---

## M15.17 账号体系接口扩展（2026-10-21）

| 接口 | 方法 | 权限 | 说明 |
|---|---|---|---|
| /auth/sms/send | POST | 公开（网关白名单） | 发送注册验证码 {phone,scene:"REGISTER"}；限流 60s/10 日/IP 20h；REAL 未配置 503 |
| /auth/register | POST | 公开（网关白名单） | 手机号注册 {phone,smsCode,password,confirmPassword}→{username,phone,message}；type=2 固定 |
| /admin/users/import | POST | ADMIN | CSV 批量导入（≤500 行）{username,cell_phone,type}；行级校验部分成功；随机初始密码一次性返回 |
| GET /internal/users/phone/{phone} | GET | 内部（InternalAuth） | 手机号查用户（手机号登录） |
| POST /internal/users/create-for-register | POST | 内部（InternalAuth） | 注册创建（username 生成/creater=0/type=2） |
| GET /internal/learning/enroll-for-paid | POST | 内部（InternalAuth） | 支付成功开课（幂等） |
| POST /orders/{id}/pay | POST | 登录用户（所有者） | 模拟支付 {channel}→已支付+开课 |

## 学习笔记（2026-10-23）

| 接口 | 方法 | 权限 | 说明 |
|---|---|---|---|
| /learning/notes | POST | 学员 | 创建笔记。courseId、chapterId、title、content；visibility 默认 PRIVATE；videoPositionSec 可选 |
| /learning/notes/{id} | PUT | 作者 | 修改标题、正文、章节、时间点和可见性。公开或更换章节时重新校验学习权限 |
| /learning/notes/{id} | DELETE | 作者 | 删除自己的笔记 |
| /learning/notes/mine | GET | 学员 | 我的笔记。courseId、keyword、pageNum、pageSize。按修改时间倒序，再按 id 倒序 |
| /learning/notes/public | GET | 学员 | 公开笔记。courseId 必填，chapterId 可选，sort=time 或 hot。无权益的付费章节不返回正文 |
| /learning/notes/{id} | GET | 学员 | 详情。私有笔记只有作者能看 |
| /learning/notes/{id}/like | POST / DELETE | 学员 | 点赞和取消。同一用户只计一次 |
| /learning/notes/{id}/comments | GET / POST | 学员 | 评论列表和发表。跟随笔记可见性 |
| /learning/notes/{id}/comments/{commentId} | DELETE | 评论作者或笔记作者 | 删除评论 |
| /admin/notes/{id} | DELETE | 管理员 | 删除并审计，body.reason 必填 |
| /admin/notes/{id}/visibility | PUT | 管理员 | 转为私有并审计，body.reason 必填 |


## M23 AI 课程语义推荐（2026-10-23）

| 接口 | 方法 | 权限 | 契约 |
|---|---|---|---|
| `/chat/recommendations` | POST | 登录且拥有会话 | body=`{sessionId,preferences}`；只读选课，服务保存当前会话偏好，不下单 |
| `/chat/recommendations/{sessionId}` | GET | 会话所有者 | 返回会话已保存偏好；其他用户统一 403 |
| `/admin/ai/recommendations` | GET | ADMIN | 当前独立 namespace、mock 标志、任务状态数量 |
| `/admin/ai/recommendations/retry` | POST | ADMIN | 重投当前 namespace 的 FAILED 任务，返回重投数 |
| `/admin/ai/recommendations/rebuild` | POST | ADMIN | 递增任务版本并重置扫描游标，返回重投数；不清空 ES |
| `/internal/courses/recommendation-source` | GET | 内部 HMAC | afterId/limit 分页；包含不可见墓碑，仅公开课程字段 |
| `/internal/learning/recommendation-context` | GET | 内部 HMAC + 可信用户身份 | 只返回当前用户 enrolled/completed；无 userId 参数 |

preferences：`goal`（最多500字）、`keyword`（最多100字）、`foundation=BEGINNER/INTERMEDIATE/ANY`、`budgetCents`（单门预算，分）、`hoursPerWeek`、`weeks`、`categoryId`（二级分类）、`limit=1..10`、`includeOwned`、`clearBudget/clearTime/clearCategory`。省略字段保留会话已有值；clearTime 先清空旧值，再应用本次明确给出的时间字段。

返回包含 `status/retrievalMode/message/preferences/courses/count`。卡片数据源为 `COURSE_SERVICE_LIVE`，含真实价格/有效期、`recommendationEvidence`、`recommendationWarnings`、`matchedBy`、`owned/completed`。状态与模式定义、索引补偿及限制见 [语义推荐交付与验收](99-AI课程语义推荐.md)。本项目参数校验沿用 HTTP 200 + `R.code=400` 的统一契约；认证/越权为 HTTP 401/403。

模型工具 `recommendCourses` 仅对推荐智能体开放；该智能体不再直接获得 `searchCourses`，关键词融合在新服务内部完成。新工具参数不接受用户身份、价格或任意下单链接。知识问答仍只使用知识检索工具，购买仍复用原提案确认门禁。


## 推荐回答策略与无结果原因（2026-10-24）

`POST /chat/recommendations` 保留原契约，新增：

- `reasonCodes`：仅当前执行实际观察到的原因，可多个并存。包括 `NO_RECALLED_CANDIDATES`、`OWNED_OR_LEARNED`、`BUDGET_EXCEEDED`、`OUTSIDE_COURSE_SCOPE`、`FOUNDATION_MISMATCH`、`TIME_EXCEEDED`、`CURRENTLY_UNAVAILABLE`、`PRICE_UNVERIFIED`、`SEMANTIC_UNAVAILABLE`、`KEYWORD_UNAVAILABLE`、`PROFILE_UNAVAILABLE`、`LIVE_COURSE_CHECK_FAILED`。
- `diagnostics`：本次召回两路状态和候选/筛选计数，scope 为 `RECALLED_CANDIDATES`。不是全平台统计；计数可重叠，达到数量上限时不继续检查所有候选。complete 表示召回渠道是否完整，不替代业务 status。
- `nextAction`：一个相关的调整方向，供页面和推荐回复展示。
- `ownedCourses`：默认为空数组。默认排除已购/已学课程且没有可推荐新课时，单独提供本次召回中已购或已加入学习的相关课程卡片（最多为请求 limit）。仍须通过实时公开状态、价格和用户其他条件的核对；不返回下架课程详情，不放宽条件。它们不计入 `courses/count/returnedCandidates`，不把 `NOT_FOUND` 或 `SEARCH_INCOMPLETE` 改为推荐成功。对话 SSE 同样发送这些 `COURSE_CARD`，携带可信的 `owned/completed`；前端复用课程详情与原学习权益核对入口，禁用重复选择购买。有可推荐新课时不额外展示此数组，显式 `includeOwned=true` 继续沿用原行为。

新增业务状态 `SEARCH_INCOMPLETE` 表示某检索渠道故障且本次未能给出推荐，不能当作正常无匹配。原个人记录/课程服务失败状态继续独立返回。只有正常没有候选时才返回无召回原因。条件错误通过工具返回 `INVALID_CONDITIONS`；页面 JSON 校验仍沿用既有 R.code=400 契约。

推荐 SSE 正文依据实际工具结果生成，不转发模型检索前自述。终态新增 `recommendationServerFallback`：模型正常完成但没调用工具时，服务端实际执行同一个只读工具后返回结果；该字段为开发诊断，不展示给普通用户。工具未执行成功时不声称已查到课程。

完整原因、版本生效及验证证据见 [修复报告](AI推荐回答策略与无结果处理修复报告.md)。
