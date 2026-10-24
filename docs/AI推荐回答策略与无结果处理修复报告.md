# AI 推荐回答策略与无结果处理修复报告

日期：2026-10-24。项目：`/Users/djanjo/CodeBuddy/20261018213055`。

本报告保留首次修复记录；随后针对重复追问及已购课程无法继续操作的修复，见文末“重复追问与已购课程入口补充”。

本次修复回答策略与结果解释，复用现有推荐工具、独立课程索引、实时课程复核和购买确认门禁。没有新增 Python 课程、硬编码推荐课程 ID、调低相似度门槛或重建现有检索索引。项目及已检查的父目录未发现适用的 AGENTS.md。

## 实际定位

1. 当前课程名称、公开简介、适用人群中包含 Python 的记录数为 **0**。推荐命名空间 `kzai-course-recommend-18df39c315a6fe85` 有 **289 个 READY 任务**，其中包括不可见墓碑，不代表 289 门可购买课程。实际 Python 请求中，语义和关键词查询均正常完成，候选数均为 0；此次无结果不是预算、已购排除或已知检索故障。
2. 发布前 Nacos 版本 `pub-20261021110446` 的推荐模板仍要求调用 `searchCourses/getCourseDetail`，与当前推荐白名单不一致。源码内置模板并不是当时的生效来源。
3. 旧会话读取自己的 `prompt_version_map`。仅修改内置模板或发布新模板，不能自动修正旧会话；之前附加约束被放在另一个前置 SystemMessage，随后又出现旧模板，存在冲突。
4. 模型生成的检索前自述会直接通过 SSE 展示；即使最终工具没有返回课程，已输出的追问、默认条件和原因猜测也无法撤回。工具参数也没有核对可选条件是否来自用户明确表达。
5. 推荐服务对多个空结果路径使用泛化说明，缺少渠道状态、过滤原因和统计，模型无法据此准确区分无召回、条件排除和故障。

用户提供的长回答作为问题样例保留。本轮修复前的两次独立基线重放均遇到模型上游超时，没有重新取得该长回答；不将其记为成功复现。上述配置冲突、缺少原因和直接输出路径由实际代码/配置核查确认。

## 实现与生效方式

- **先查询，再展示结果。** 推荐智能体继续使用真实模型路由和现有工具。推荐分支读取工具流，但不逐字转发模型自述；用户文本由本轮实际工具结果中的 message、nextAction 和课程事实组织。读取完整正常流，以保留模型用量统计；停止操作仍检查停止标志。
- **可选参数必须有原文依据。** `RecommendationTurnGuard` 检查本轮用户原文，丢弃模型擅自填写的基础、预算、时间、范围和已购开关。没有提及的字段省略，交由原偏好服务保留本会话已有值。明确撤销则使用原有 clear* 机制。“不要默认零基础/预算300元”不会当作个人条件。
- **模型未查课不冒充成功。** 模型正常结束但未执行推荐时，服务端调用同一个只读推荐工具，再根据真实返回回答。终态通过 `recommendationServerFallback` 标记来源。本轮真实成功记录均为 false，即实际由模型调用推荐工具；补偿路径由单测验证。
- **原因可验证。** 返回 `reasonCodes`、`diagnostics` 和 `nextAction`，区分无候选、已购/已学、预算、课程范围、基础、时长、当前不可用、价格无法核实、语义/关键词渠道故障和个人记录/课程复核失败。多个条件可同时计数，不强行归为一个原因。
- **故障不等于没有课程。** 部分检索失败且未能返回课程时使用 `SEARCH_INCOMPLETE`；个人记录和课程复核失败保留独立故障状态。正常无召回才返回 `NO_RECALLED_CANDIDATES`。下架等不可用情况只说明粗粒度原因，不返回隐藏课程名称、正文或其他用户记录。
- **保留版本隔离。** 不改写历史会话绑定或历史模板内容。在单个 SystemMessage 中保留版本快照，并在末尾附加当前业务约束；服务端参数与展示约束对推荐分支统一生效。
- **实际发布。** 通过现有管理员 preview/publish 接口发布推荐模板，Nacos 写后回读并同步 Redis。当前生效版本为 **`pub-20261024075445`**，对应推荐 PUBLISH 版本 **59**。其余四类模板正文、欢迎语、热门问题逐项比对未变。旧测试会话仍绑定推荐版本 52，修改前后映射一致；新会话绑定推荐版本 59。
- **前端。** 继续显示真实课程卡片和元价格；空结果标签显示“已完成语义查询”，不把执行成功暗示成有匹配课程。故障标签、下一步建议与正文使用服务端结果，不展示内部原因码或统计。

`diagnostics.scope=RECALLED_CANDIDATES`：统计只覆盖本次召回范围。`mergedCandidates` 与 `evaluatedCandidates` 可以不同，因为达到返回数量上限后停止筛选；各排除计数可能重叠。`diagnostics.complete` 仅表示召回渠道是否完整，业务是否成功仍由 status 判断。它不是全目录统计或整体准确率。

## 修复后的实际回答

新旧会话输入均为：

> 我要学 Python

实际输出：

> 暂时没有找到与当前学习目标匹配的可推荐课程。
>
> 你主要想用它做什么？我可以按用途再找一次。

两者基础、预算、每周时间和周期都保持 null，没有写入默认零基础或默认预算。实际直接接口诊断为：语义查询成功、关键词查询成功、两路候选均为 0，原因 `NO_RECALLED_CANDIDATES`。没有声称平台永远不存在 Python 课程，也没有把结果解释为缺少标题关键词。

真实条件变更案例：

| 输入 | 实际行为 |
|---|---|
| 我想学 Java 微服务，预算500元，每周5小时，4周学完 | 保存预算50000分、每周5小时、4周；基础保持未限定；返回当前课程 Java 微服务架构实战，展示 ¥299.00 |
| 预算改成1元 | 保留学习目标和时间，仅将预算改为100分；说明候选超出预算，不说没有相关课程 |
| 取消预算，时间不限 | 清空预算和时间，保留学习目标，重新返回真实课程 |
| 另一个会话输入“我要学 Python” | 基础、预算、时间均未继承前一个会话 |

## 实际测试结果

| 验证 | 结果 |
|---|---|
| AI 服务完整回归及打包 | **210 项：208 通过、2 跳过，0 失败/错误**。真实 ES/Embedding 专用测试未在该默认回归命令中开启；本次另行进行了真实服务验收 |
| 购买门禁回归 | 原 OrderTools 10 项、PurchaseProposalStore 6 项通过；实现和权限边界未改 |
| 前端测试 | **31 通过、7 跳过** |
| 前端检查与构建 | lint 0 错误、23 条既有警告；生产构建成功 |
| 新增针对性覆盖 | 纯主题不设置可选条件、明确零基础、预算排除、已购排除、无匹配、语义/关键词故障、课程/个人记录核对失败、多个排除原因、条件撤销与会话隔离、仅补充基础时保留目标、学习计划不误当经验、旧快照兼容、拦截检索前猜测、模型未调用工具时补偿、上游失败不冒充空结果 |
| 第一次真实流程验收 | 旧绑定 Python、新绑定 Python、明确条件、预算调整、条件撤销共 **5 个场景满足各自断言**；不是推荐准确率统计 |
| 最终部署后的脚本验收 | 两个独立临时会话的首次推荐、明确条件、会话隔离、预算调整、条件撤销共 **5 个场景满足各自策略断言**；另以最终部署验证“我要学 Python → 我没有编程经验 → 不限制基础”3个续问步骤，原 Python 目标保持不变；模型终态为真实 DASHSCOPE，未启用服务端补偿路径 |

第一次真实流程中旧 Python 会话约 4.288 秒、新 Python 会话约 3.272 秒。最终部署后的实际耗时、全文、协议终态和条件值以 `final-live-results.json` 为准；两轮执行策略相同，最终版本保留完整正常流的用量统计。

证据目录：[evaluation/recommendation-policy](evaluation/recommendation-policy/)，逐项修改清单见 [changed-files.txt](evaluation/recommendation-policy/changed-files.txt)。包括生效前后模板、发布回读结果、旧会话/新会话完整 SSE、真实接口诊断、条件变更记录、最后一次脚本结果和验证摘要。测试仅使用独立临时会话并通过产品接口清理；没有向课程、学习、订单或现有向量索引插入评测样本。正常会话版本快照和操作审计按既有机制保留。

## 修改文件

| 文件 | 修改 |
|---|---|
| `kzai-aigc/.../recommendation/RecommendationService.java` | 结构化状态、原因、渠道与排除统计，针对实际原因给出说明和一个调整方向 |
| `.../RecommendationTools.java` | 参数来源校验、实际结果暂存、受控条件错误/服务错误、工具描述 |
| 新增 `.../RecommendationTurnGuard.java` | 本轮原文条件校验与明确撤销，防止模型默认条件 |
| 新增 `.../RecommendationReplyPolicy.java` | 跨版本推荐业务约束和基于实际工具事实的正文生成 |
| `kzai-aigc/.../chat/ChatService.java` | 单 SystemMessage 合并约束、推荐流展示控制、必要时执行真实只读补偿 |
| `.../tools/ToolResultHolder.java` | 请求内原文、最后实际推荐结果及补偿来源；重试清理过时课程卡片 |
| `.../tools/ToolRegistry.java`、`ToolContextKeys.java` | 补偿入口和上下文说明，不变更可信身份来源 |
| `.../prompt/PromptManager.java` | 内置默认与本次业务约束一致；现有环境另经正常发布接口实际生效 |
| `kzai-frontend/src/components/RecommendationPanel.vue` | 空结果/故障标签和下一步建议展示 |
| `RecommendationServiceTest`、新增 `RecommendationTurnPolicyTest`、`ChatServiceStreamTest` | 原因、条件、流式过程、旧会话及故障测试 |
| `kzai-frontend/tests/component/RecommendationPanel.test.js` | 不完整查询与内部字段不展示测试 |
| 新增 `scripts/eval-recommendation-policy-live.py` | 可重复执行的真实对话与条件隔离验收，自动清理临时会话 |
| 本报告、API/部署/验收文档与证据 | 实际版本、结果和复现方法 |

`RecommendationRequest`、`RecommendationPreferences`、`RouteAgent`、`AgentToolPolicy` 已检查，现有验证、部分偏好合并、路由分类、只读工具白名单足以复用，未为本次修复重复新增相同能力。真实案例均路由至 RECOMMEND。

## 复现与限制

```bash
source scripts/dev-env.sh
mvn -o -pl kzai-aigc -am test -DforkCount=0 -Dkzai.aigc.recommendation-indexing-enabled=false
cd kzai-frontend
npm test -- --run
npm run lint
npm run build
```

真实对话验收先通过环境注入 `KZAI_EVAL_USERNAME`、`KZAI_EVAL_PASSWORD`，然后在项目根目录运行：

```bash
python3 scripts/eval-recommendation-policy-live.py
```

默认报告写到 `/tmp/kzai-recommendation-policy-live.json`。可用 `KZAI_POLICY_EVAL_OUTPUT` 改输出位置；需要现有服务、ES 与真实模型环境，会产生正常模型用量。脚本输出异常也保留报告，不将其改写为成功。既有会话版本绑定不要通过直接修改数据库来“更新”；新发布影响新会话，旧会话通过统一业务约束兼容；已生成的历史消息不回写，验证的是旧会话后续新回答。

限制：自然语言可选条件采用保守的原文规则，支持本次验证及常见金额、每周小时和周数；复杂否定、多目标组合和月制日程未做全面语言评测，可使用结构化表单设置。现有历史偏好未批量清空，无法追溯的旧值没有擅自断言其来源，用户可明确撤销。真实服务故障与已购等部分场景使用受控单测，不为验收去中断业务服务、下架课程或制造订单；没有执行真实购买、支付或新开课交易，也没有做全目录推荐质量评估或生产压测。

## 重复追问与已购课程入口补充

2026-10-24 08:21 完成真实复测。此节为本报告后续修复记录。

根因是共享推荐服务的无召回分支固定输出用途追问；即使当前目标已是“我要靠Python就业”，也会重复询问。已购排除分支仅保留排除统计，未将可核对的相关已购课程交给正文、SSE 卡片和推荐面板，用户无法顺着结果打开已有课程。

改动范围：

- `RecommendationService.java`：无匹配改为简短调整建议；新课为空时，单独返回 `ownedCourses`。这些卡片继续接受当前公开状态、价格、基础、预算、时间、课程范围核对；不会混入新课计数，不修改 includeOwned，不返回下架详情。有可推荐新课时仍优先展示新课。
- `RecommendationReplyPolicy.java`、`RecommendationTools.java`、`MockToolScriptService.java`：统一展示已购/已完成状态、真实名称、当前标价、公开简介和下一步；真实对话及 MOCK 协议均沿用共享结果。MOCK 不计入语义质量验收。
- `RecommendationPanel.vue`、`CourseCard.vue`：显示独立的已购课程区域；复用课程详情入口并标注“查看学习入口”，由原页面核对学习权益；重复购买按钮保持禁用。
- `RecommendationServiceTest.java`、`RecommendationTurnPolicyTest.java`、`RecommendationPanel.test.js`：覆盖就业目标不重复追问、已完成与付费记录分别识别、所有权不绕过其他条件、不与新课混合、SSE 卡片及清理、前端跳转和重复购买禁用。
- 本报告、`api-extension.md` 及 `docs/evaluation/recommendation-policy/owned-followup-*.json` 保存契约与实际证据。未修改 Nacos、Prompt 或会话版本映射，未修改购买门禁、索引、阈值及业务数据。

实际验证：

| 验证 | 本次结果 |
|---|---|
| AI 服务完整回归、打包 | 214 项：212 通过、2 跳过，0 失败/错误；默认跳过 ES/真实模型专用测试，另做下述真实服务小样本验收 |
| 前端相关组件测试 | RecommendationPanel 4 项通过，含真实组件按钮禁用与课程详情路由断言 |
| 前端生产构建 | 成功；本次未重新执行全量前端测试及 lint |
| 部署 | 仅重启本地 aigc，使用独立 JAR 快照；原模型和索引配置继续使用 |
| 修复前真实数据 | admin 可推荐 1003；student01 的同一候选因为已购/已学被排除。两者语义与关键词渠道正常；临时会话均已清理 |
| 修复后真实对话 | student01 同一临时会话顺序重放用户四句输入，4 项策略断言通过，provider=DASHSCOPE、mock=false、recommendationServerFallback=false；临时会话已清理 |
| 学习入口只读核对 | 返回的 1003 在课程详情可查询；student01 的 access 为 allowed=true、enrolled=true、source=PAID、courseVisible=true，可沿原详情页“继续学习”进入 |

四句实际结果：

1. “我要学Python。”与“我要靠Python就业。”：语义与关键词渠道正常、两路候选均为 0，仍如实说明本次无匹配，下一步为“可以换一个学习方向或关键词再查找。”；不再询问用途。就业目标原文保存，未增加零基础、预算或时间条件。
2. “给我推荐学学AI的课程。”与“我要学大模型。”：展示已购的《大模型应用开发入门》，当前标价 ¥499.00，公开简介为“面向应用开发者的大模型入门课，讲透 Prompt、工具调用与流式输出。”；各产生 1 张真实课程卡片，提供查看学习入口。新课 courses 仍为空，count=0，includeOwned 未被自动改为 true。

证据：[修复前不同账号结果](evaluation/recommendation-policy/owned-followup-before.json)、[四句真实对话及诊断](evaluation/recommendation-policy/owned-followup-after.json)、[学习权益核对](evaluation/recommendation-policy/owned-followup-access.json)。这是固定样例策略验收，不是推荐准确率或就业效果评估。

演示：使用 student01 打开 AI 工作台，在新会话依次输入上述四句；AI/大模型结果应带已购课程卡片，点击“查看学习入口”进入原课程详情，再按实际权益使用“继续学习”。推荐面板查询同一主题应显示独立的已购课程区域，选择购买保持禁用。已生成的历史回答不回写。

限制：未新增 Python 课程、未放宽检索阈值；Python 本次仍没有真实召回。未实际播放、上报学习进度或执行购买/支付/重新开课；仅核对学习权益 API、组件跳转与现有详情页实现。未覆盖全平台课程、所有自然语言或浏览器视觉回归。
