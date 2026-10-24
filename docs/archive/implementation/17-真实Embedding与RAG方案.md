# 17-真实 Embedding 与 RAG 接入方案（第一阶段：检查与方案，待批准）

> 状态：**方案提交，暂停等待确认**。本文档对应指令《课栈智伴——真实 Embedding + RAG 语义检索接入与安全验收》步骤一~十。
> 在获得批准前：不调用真实 Embedding API、不批量生成向量、不写入 REAL ES 测试索引、不切换正式 Alias、不修改真实知识库数据、不启动 31 条 REAL 评测。

---

## 1. 当前代码基线（步骤一核查结果）

上一阶段 qwen-plus 工具调用修复已全部就位并回归通过（详见 `M15-REAL-AI-验证报告.md`）：

- 运行期 `DashScopeChatOptions.withFunctions(白名单)` 传工具（M6.1 copyToTarget 缺陷规避）✓
- BUY Dry-run 独立 ChatClient（register 中仅受控回调，零副作用）✓
- `DiscoveryBlockingLoadBalancerClient`（事件循环线程 Feign 可用）✓
- 前端四场景 REAL 验证通过；`ai_model_call_log` / `TOOL_AUDIT` 真实审计 ✓

### Embedding 链路现状（哪些已支持 REAL / 仅 MOCK）

| 组件 | 位置 | 现状 |
|---|---|---|
| `KnowledgeEmbeddingService` 接口 | kzai-aigc/knowledge/embedding | ✅ 完备（provider/dimensions/embed/embedAll/mock） |
| `MockKnowledgeEmbeddingService` | 同上 | ✅ 256 维 SHA-256 确定性投影，L2 归一化（cosine 口径），默认生效 |
| `DashScopeKnowledgeEmbeddingService` | 同上 | ✅ **完整实现**（M9 预置，从未真实启用过）；维度运行时实测写入 mapping；本阶段已配置化模型名 |
| `KnowledgeRetriever` | knowledge/retrieval | ✅ TopK×3 预取 + min_score 0.6 + 版本白名单（MAX 500）+ MySQL 权威复核 + PROTECTED hasEntitlement fail-closed |
| 切片/版本 | TextChunker 500/重叠 50；KnowledgeIndexJobRunner 版本化发布 | ✅ |
| ES 网关 | `EsKnowledgeGateway`（Spring RestClient 直连 ES 8，无 ES 客户端依赖） | ✅ mapping Java 内构建，dims=运行时实测，similarity=cosine，`_score=(1+cos)/2` |
| 索引命名 | `kzai-knowledge-v{N}` + 读别名 `kzai-knowledge-alias` | ✅ 前缀/别名均可配置 |
| RAG Prompt/卡片 | PromptManager 内置 KNOWLEDGE 模板；`RAG_SOURCE` 1003 卡片含 provider 字段 | ✅ |
| 测试 | `KnowledgeEsIT`（本地 ES 8.11.4，Mock 链路 8 用例）、版本过滤单测、KnowledgeToolsTest、e2e-m9.sh | ✅ MOCK 协议回归完备；**真实 Embedding 无任何测试** |
| **真实 Embedding 启用** | 条件 `kzai.aigc.embedding-provider=dashscope`（本阶段修复，见 §6） | ⚠️ 从未启用（RAG Hit@6=0% 为 Mock 无语义的真实反映） |

**结论：真实 Embedding 代码链路 M9 已预置完整，从未跑通；本轮是「首次真实启用」而非「重新设计」。**

---

## 2. 阿里云百炼 Embedding 模型对比（步骤二/三，官方文档 2026-10 实测抓取）

北京地域（`https://dashscope.aliyuncs.com`，与现有 qwen-plus 同 endpoint 同 Key）：

| 模型 | 维度（默认） | 可选维度 | 批次 | 单行 token | 单价（元/千 token） | 免费额度 | CMTEB（检索） | M6.1 兼容性 |
|---|---|---|---|---|---|---|---|---|
| text-embedding-v1 | **1536（固定）** | 无 | 25 | 2,048 | 0.0007 | 无 | 59.84 | 枚举内，默认值 |
| text-embedding-v2 | **1536（固定）** | 无 | 25 | 2,048 | 0.0007 | 无 | 62.17 | 枚举内 |
| text-embedding-v3 | **1024** | 1024/768/512/256/128/64 | — | 8,192 | 0.0005 | 各 50 万 Token（90 天） | 68.92 | 名可传；dimension 参数不支持 |
| **text-embedding-v4**（Qwen3-Embedding 系列，官方推荐） | **1024** | 2048/1536/1024/768/512/256/128/64 | 10 | 33,000 | 0.0005 | **100 万 Token**（90 天） | **70.14** | 名可传；dimension 参数不支持 |
| qwen3.7-text-embedding | 1024 | 2560/2048/1536/1024/768/512/256 | 20 | 128,000 | 0.0005 | 100 万 Token | 更强（未列 CMTEB） | 名可传；instruct/sparse/output_type 均不支持 |

M6.1 兼容性实证（本阶段字节码反编译 + 离线单测 `DashScopeEmbeddingRequestShapeTest` 锁定）：

- 请求体 `model` 为 **String 直传**（无枚举强转）→ v3/v4 模型名可传；
- `EmbeddingRequestInputParameters` record 仅含 `textType` → **不支持 `dimension` 参数** → 只能用各模型默认维度；
- `mergeOptions` 的 `textType` 只取 defaultOptions（运行期传入被忽略）→ query/document 区分需**双实例**（已按此实现）。

### 推荐：**text-embedding-v4，1024 维（默认）**

理由：
1. 官方明确推荐（「建议使用 text-embedding-v4，速度更快、成本更低、维度选择更多」），Qwen3-Embedding 系列；
2. 检索质量对 v1 提升一个量级（CMTEB 70.14 vs 59.84），价格反而低 29%；
3. 100 万 Token 免费额度——按本项目规模（§4）可覆盖**全部**验证与多次重建，实际现金支出预计 0 元；
4. 与 qwen-plus 同地域同 endpoint，复用现有 `DashScopeApi`/Key/网络修复；
5. v4 的高级特性（instruct/sparse/output_type）M6.1 用不上——若未来升级 Spring AI 再评估 qwen3.7-text-embedding，不在本轮范围。

候选（备胎）：**text-embedding-v3**——同为默认 1024 维、50 万免费额度、CMTEB 68.92，若 v4 在北京地域出现权限/限流异常则降级。

**明确不推荐**：v1/v2（最旧、无免费额度、1536 维无自定义）；qwen3.7 系列（新参数全部无法经 M6.1 使用，发挥不出优势，引入不确定性最大）。
**不混用**：写入与查询锁定同一模型名（`kzai.aigc.embedding-model` 单一来源），切换模型 = 重建索引，绝不共存于同一索引/别名。

### 维度说明

原 M9 文档预期 v1=1536；实际采用 **v4=1024**。mapping dims 为运行时实测写入（`buildMapping(dimensions)`），**无需改代码**，仅影响新建的 REAL 索引。

---

## 3. 真实文档规模与费用估算（步骤四/六）

MySQL `course_stack_ai.ai_knowledge_document` 实测（2026-10-21）：

| 指标 | 数值 |
|---|---|
| 文档总数 | 39 |
| 有效已发布（review_status=1 且未删除） | **17**（16 PUBLIC + 1 PROTECTED） |
| 已撤回 | 22 |
| 切片总数 | **17**（每篇 1 切片，均未超 500 码点） |
| 有效内容总字符 | **1,724**（其中 11 篇为 41 字符测试占位；真实语义内容为 4 篇 M12 评测文档：退款政策 357 字、发票证书 338 字、有效期规则 307 字、内部风控 PROTECTED 239 字） |

ES 现状：`kzai-knowledge-v9001~v9011`（256 维 Mock 向量），alias → v9001；另有孤儿空索引 v68462（不动）。

### 费用估算（官方价格 × 真实规模）

中文 1 字 ≈ 0.6~1.0 token（DashScope 分词器），1,724 字 ≈ 1.0K~1.8K tokens：

| 项目 | 调用量 | tokens | 按 v4 价格 | 实际现金 |
|---|---|---|---|---|
| 首次全量向量化（17 切片，≤2 批） | 2 次 API | ~1.8K | ~0.0009 元 | **0 元**（免费额度内） |
| 单次查询 Embedding | 1 次 | ~10-30 | ~0.000015 元 | 0 元 |
| 完整 RAG 问答（+qwen-plus 输入 ~1K/输出 ~0.5K） | 1 次 | — | — | ~0.002 元 |
| 重建索引（同全量） | 2 次 | ~1.8K | ~0.0009 元 | 0 元 |
| 失败重试上限（MAX_ATTEMPT=5） | ≤10 次 | ≤9K | ≤0.005 元 | 0 元 |
| 31 条 REAL 评测（后置单独授权） | 31 次 | — | — | ~0.06 元 |

**全部验证与重建在 100 万 Token 免费额度内，预计现金支出 0 元**（qwen-plus 对话费用已在上阶段额度内消耗）。费用以「区间+依据」口径给出，最终以控制台账单为准；每日限额仍非严格费用保障（Redis fail-open 风险保持既有认知）。

---

## 4. REAL 测试索引隔离与回滚方案（步骤五）

### 命名（沿用现有前缀-版本-别名机制，零代码改动）

| 项 | MOCK 现有（不动） | REAL 测试（新增） |
|---|---|---|
| 物理索引 | `kzai-knowledge-v9001...` | `kzai-knowledge-real-test-v1`（起） |
| 读别名 | `kzai-knowledge-alias` → v9001 | `kzai-knowledge-real-test-alias` → real-test-v1 |
| dims | 256 | 1024（实测后确认） |
| provider | MOCK | DASHSCOPE |

切换方式：启动 aigc 时以环境变量指定
`KZAI_EMBEDDING_PROVIDER=dashscope`、`KZAI_EMBEDDING_MODEL=text-embedding-v4`、
`KZAI_ES_URL=http://127.0.0.1:9200`、`KZAI_AIGC_KNOWLEDGEINDEXPREFIX=kzai-knowledge-real-test`、
`KZAI_AIGC_KNOWLEDGEINDEXALIAS=kzai-knowledge-real-test-alias`。
（前缀/别名来自 `AiProperties`，relaxed binding 可用环境变量覆盖；bootstrap 建索引逻辑自动按 `real-test-v1` 命名并挂别名。）

### 隔离保障

1. 维度物理隔离：1024 ≠ 256，不同物理索引，ES 自身拒绝跨 dims 写入/检索；
2. 不触碰：现有 `kzai-knowledge-*` 物理索引、别名、文档、审核记录零改动；
3. 不新增数据库表；原始 12 张核心表不动；
4. 启动期 `EmbeddingModeGuard`：REAL Chat + MOCK Embedding 组合显式 WARN（本阶段已加），REAL 索引阶段将改为要求 REAL Embedding 与 REAL 索引前缀匹配（见 §7 防护项）。

### 回滚

- REAL Embedding 故障 → 切回 `KZAI_EMBEDDING_PROVIDER=mock` + 原前缀/别名重启即恢复原 MOCK 演示环境；
- 原文/版本/审核记录全在 MySQL 与文件存储，索引重建不破坏任何业务数据；
- REAL 测试索引删除属破坏性操作，**单独征求批准**后执行。

---

## 5. 模型混用与索引绑定风险排查（步骤七）

| # | 风险 | 状态 |
|---|---|---|
| 1 | **配置键错位（已修复）**：Embedding 条件注解读 `kzai.ai.knowledge.embedding-provider`（死键），yml/compose 配置 `kzai.aigc.embedding-provider`/`KZAI_EMBEDDING_PROVIDER` 静默无效——「配置了 REAL 实际仍 MOCK」 | ✅ 本阶段修复：条件注解统一为 `kzai.aigc.embedding-provider`；compose.yml `KZAI_AI_KNOWLEDGE_ESURL`/`KZAI_AI_KNOWLEDGE_EMBEDDING_PROVIDER` 两个死键同步修正为 `KZAI_ES_URL`/`KZAI_EMBEDDING_PROVIDER` |
| 2 | compose 容器内 ES 地址死键（同上，回退 127.0.0.1 不可达） | ✅ 修复 |
| 3 | M6.1 运行期 textType 被忽略 → 若用运行期 options 做 query 区分会静默失效 | ✅ 已实证并按双实例实现；`DashScopeEmbeddingRequestShapeTest` 锁定 |
| 4 | REAL Chat + MOCK Embedding 静默组合被标 REAL RAG | ✅ `EmbeddingModeGuard` 启动期 WARN；RAG 卡片 payload.provider 可追溯 |
| 5 | REAL Embedding 写入 256 维旧索引 | ES 层拒绝（dims 不匹配 bulk 失败）+ REAL 阶段将启用独立前缀，双保险 |
| 6 | 写入/查询模型不一致 | 模型名单一配置来源 `kzai.aigc.embedding-model`，双实例同 model；测试索引全生命周期锁定 |

---

## 6. 课程章节信息防编造最小修复方案（步骤八）

**根因**：`getCourseDetail`/`searchCourses` 工具结果只有 `chapterCount`（数量），无章节标题 → 模型自行补全逐章大纲并当作事实输出。

**实测依据**：`course_chapter` 表有真实章节名（`name`+`chapter_order`，1001=4 章、1002=3 章…）；`CourseAiVO`（kzai-course 内部契约）未包含章节列表。

**最小修复方案（跨 kzai-course + kzai-aigc，属内部契约变更，待批准后实施）**：

1. `kzai-course`：`CourseAiVO` 增加 `chapters: [{name, order}]`（`course_chapter where deleted=0 order by chapter_order`，仅名称与顺序，不含未审核内容）；
2. `kzai-aigc`：`CourseTools`/`MockToolScriptService` 工具结果 JSON 同步携带 `chapters`；工具 description 补「章节名称以 chapters 数组为准，不得自行编写章节名」；
3. 行为约束：工具返回 `chapters` 时模型才可展示逐章标题；仅有 `chapterCount` 时只允许说「共 N 章」；RAG 检索为空时如实告知（现有 Prompt 已有，补充针对章节的显式约束）；
4. 回归测试：
   - 单测：`CourseTools` 结果含真实 `chapters` 断言；
   - eval 场景（Mock 脚本）：仅 `chapterCount` 无 `chapters` 时，断言回答不出现编号章节目录（第 1 章/Chapter 1 等）；
   - REAL 前端验证（上轮场景 3 重跑）：章节标题与 DB 一致；
5. 不触碰购买确认门禁（createPreOrder 语义不变）。

---

## 7. 本阶段已完成的免付费离线修复与离线测试（步骤九）

全部不联网、不触碰业务数据，kzai-aigc 全量 **123 单测通过（0 失败）**：

| # | 修复/新增 | 文件 |
|---|---|---|
| 1 | Embedding 条件注解配置键错位修复（`kzai.ai.knowledge.*` → `kzai.aigc.*`） | `MockKnowledgeEmbeddingService`、`DashScopeKnowledgeEmbeddingService` |
| 2 | Embedding 模型名配置化（默认 `text-embedding-v1` 行为不变，批准后配置切 v4） | `DashScopeKnowledgeEmbeddingService`、`application.yml`（新增 `kzai.aigc.embedding-model`） |
| 3 | `embedQuery`（检索侧 query 类型）+ 双实例实现（M6.1 textType 语义实证） | `KnowledgeEmbeddingService`、`DashScopeKnowledgeEmbeddingService`、`KnowledgeRetriever` |
| 4 | compose.yml 两处死键修正 | `deploy/compose.yml` |
| 5 | REAL/EMBEDDING 模式混用启动期警示 | `EmbeddingModeGuard`（新） |
| 6 | M6.1 Embedding 请求形态离线锁定测试（4 用例：v1 形态、v4 可传、textType 忽略语义、query 实例形态） | `DashScopeEmbeddingRequestShapeTest`（新） |

**注**：surefire 默认 fork 模式在本机出现「forked VM terminated」环境性问题（退出码 0），本轮以 `-DforkCount=0` 执行验证（结果一致），后续 clean install 再复核。

### 环境清理核查（指令 §16）

- `/etc/hosts`：`39.96.213.166 dashscope.aliyuncs.com` 仍在，官方 DNS 已轮换（8.140.217.18/8.152.159.24），上阶段实测直连真实 IP 返回 200 → **可安全移除**，建议命令：`sudo sed -i '' '/dashscope\.aliyuncs\.com/d' /etc/hosts`（系统级文件，未代为执行）；
- `-Dhttp.nonProxyHosts=*`：为绕过本机系统代理 127.0.0.1:15732 所必需；最小替代 `-Dhttp.nonProxyHosts='localhost|127.*|192.168.*|10.*|*.aliyuncs.com'`（保留内网与 DashScope 直连，其余走代理）；建议批准后统一写入启动脚本，开发期保留 `*` 亦可；
- API Key：仅存 `~/.dashscope_key`（仓库外）与服务进程环境变量，日志/源码无泄露（持续核查）。

---

## 8. 小规模 REAL 测试计划（待批准后执行）

**测试文档选择（3-5 篇，全部来自现有已审核发布数据，普通学员身份验证）**：

| 文档 | 用途 |
|---|---|
| M12评测·平台退款政策（PUBLIC 357 字） | 中文语义检索主验证 |
| M12评测·发票与证书说明（PUBLIC 338 字） | 同义词/改写查询 |
| M12评测·课程有效期与学习规则（PUBLIC 307 字） | 跨课程问题与无召回对照 |
| M12评测·内部风控与运营手册（PROTECTED course 1001） | 权限过滤（学员不可见/购买后可见） |
| （可选）m9web 占位文档（PUBLIC 41 字） | 弱内容召回下限观察 |

**执行序列**：
1. 单次真实调用（1 句短文）：记录模型名/Provider/成功/维度/数值有效性/requestId/token/耗时/费用；失败即停；
2. 批准后建 `kzai-knowledge-real-test-v1`（dims=实测）→ 上述文档走真实 `KnowledgeIndexJob` 切片+向量化+bulk 写入；
3. REAL RAG E2E（同会话、SSE 1001/1003/1002、来源卡片=真实命中）；
4. 语义检索测试矩阵（同义词/改写/跨课程/无关/INTERNAL/PROTECTED/撤回/删除/多版本/ES 不可用/Embedding 超时/429）；
5. Tool Calling 回归 + 章节防编造专项 + SSE 回归 + Maven clean install + 前端构建。

**M9 500 边界**：现规模 17 篇远未触及；保持 `MAX_VERSION_PAIRS=500` 防护与回退语义不变（`KnowledgeRetrieverVersionFilterTest` 已覆盖 500/501）。

---

## 9. 待批准清单（步骤十）

| # | 事项 | 费用 | 可逆性 |
|---|---|---|---|
| A | 单次真实 Embedding 调用（text-embedding-v4，1 句短文，北京地域） | ~0.00002 元（免费额度内） | 无副作用 |
| B | 创建独立索引 `kzai-knowledge-real-test-v1`（1024 维）+ 别名 | 0（ES 本地） | 可删（删除前再批准） |
| C | 4-5 篇文档真实向量化写入 REAL 测试索引 | <0.001 元（免费额度内） | 不动原 MOCK 索引 |
| D | REAL RAG 小规模 E2E + 语义检索测试矩阵（含 embedding 失败/超时/429 模拟） | <0.05 元 | 只读检索+对话 |
| E | 章节防编造契约变更（CourseAiVO+chapters，跨 kzai-course/kzai-aigc） | 0 | 代码回滚 |
| F | 31 条 REAL 批量评测（本轮不执行，后续单独授权） | ~0.06 元 | 只读评测 |
| G | /etc/hosts 清理（由你执行或授权 sudo） | 0 | 可再加回 |
| H | nonProxyHosts 收紧为最小替代配置 | 0 | 可回退 |

**默认执行口径**：A→B→C→D 顺序，每步成功才进下一步；任何一步失败即停并报告；E 与 A-D 并行开发但不部署验证；F/G/H 按你指示。

---

*方案提交人：CodeBuddy；等待确认模型（text-embedding-v4/1024 维）、预算（免费额度内）与索引方案（kzai-knowledge-real-test-*）后继续。*
