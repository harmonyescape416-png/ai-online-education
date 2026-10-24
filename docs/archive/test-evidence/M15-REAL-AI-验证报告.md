# kzai M15 真实 AI（qwen-plus）工具调用修复与前端验证报告

## 一、根因结论（场景 2 阻塞的最终定位）

**DashScope 流式 Function Calling 失效不是「Prompt 形态」问题，而是 1.0.0-M6.1 的运行期 options 序列化缺陷。**

排查证据链：
1. curl 直连 DashScope 流式 + tools → 返回标准 `tool_calls`（finish_reason=tool_calls）→ **API 层正常**
2. 反射探测 `DashScopeChatModel.createRequest`（不联网）→ 三种运行期形态 tools 全为 null：
   - `FunctionCallingOptions.functionCallbacks(回调对象)` → tools=null
   - `FunctionCallingOptions.functions(工具名)` → tools=null
   - `defaultOptions.withFunctions(工具名)` → tools 正确生成 ✓
3. 字节码核验：`createRequest` 用 `ModelOptionsUtils.copyToTarget`（Jackson JSON 复制）把运行期
   options 转为 DashScopeChatOptions，`functionCallbacks` 对象与 `functions` 名字集合**全部丢失**；
   只有 defaultOptions 的 functions 集合参与 `getFunctionTools(Set)` → `resolveFunctionCallbacks(register 优先)`
4. 运行期直接传 **DashScopeChatOptions.withFunctions(白名单名)** → createRequest 生成 tools ✓
   （复现程序真机验证：工具实际执行 1 次、toolContext 正确送达、回复基于工具结果）

此前「ChatClient 链式形态修复」无效的原因：链式只改变了传参位置，运行期 options 仍在 copyToTarget 处丢失。

## 二、代码修复（最小变更，2 个文件 + 1 个新类）

| 文件 | 变更 |
|---|---|
| `kzai-aigc/.../provider/DashScopeChatProvider.java` | `streamWithTools` 运行期改传 `DashScopeChatOptions.withFunctions(白名单名)` + toolContext + proxyToolCalls=false；`callWithDryRunTools` 改用 Dry-run 独立 ChatClient（见下） |
| `kzai-aigc/.../config/AiModelConfig.java` | 新增 `DryRunChatClientFactory` Bean 与 `DiscoveryBlockingLoadBalancerClient` Bean |
| `kzai-aigc/.../config/DiscoveryBlockingLoadBalancerClient.java`（新） | 无 Reactor 的 Feign 负载均衡客户端 |
| `kzai-aigc/.../tools/CourseTools.java` | 工具异常日志补全堆栈（排障用） |

### 附带发现并修复的两个深层问题

1. **事件循环线程阻塞检查**：真实模型流式链路中，工具在 `reactor-http-nio-*`（NonBlocking）线程被内联执行，
   默认 `BlockingLoadBalancerClient.choose` 内部 `Mono.block()` 被 Reactor 3.6 BlockingChecks 拒绝
   → 工具审计 `success=false/SERVICE_UNAVAILABLE`。修复：aigc 侧用 DiscoveryClient（Nacos）
   轮询实现的 `DiscoveryBlockingLoadBalancerClient`，全程无 Reactor，仅作用于 aigc。
2. **BUY Dry-run 无法运行期覆盖回调**：`resolveFunctionCallbacks` 为 register 优先（真实 createPreOrder
   会真实下单），且运行期回调过不了 copyToTarget。修复：每次评测用 Dry-run 回调独立构造
   ChatModel/ChatClient（register 中只有 Dry-run 回调，天然零副作用）。

## 三、四场景前端验证结果（全部通过，provider=DASHSCOPE, mock=false）

| 场景 | 结果 | 证据 |
|---|---|---|
| 1. 普通中文问答 | ✅ | 真实回复（此前已验证） |
| 2. 查真实 Java 课程 | ✅ | TOOL_AUDIT `searchCourses success=true resultCount=1`（76ms）；回复=数据库事实「Java 微服务架构实战 299 元 可购买」；cards=1，1003 卡片帧 1 个；真实 usage 687 tokens |
| 3. 追问价格/章节 | ✅ | TOOL_AUDIT `getCourseDetail success=true`（16ms）；ID 1002「Spring Cloud Alibaba 进阶」399 元 / 3 章来自 DB（逐章大纲为模型自由发挥，关键字段为数据库事实） |
| 4. 同会话上下文追问 | ✅ | 「打九折多少钱」未重复调工具，基于历史算出 399×0.9=359.10；promptTokens 585→954；Redis `CHAT:3_{sessionId}` 持久化 USER/ASSISTANT 消息，TTL≈7 天；MySQL 会话回放 6 条消息 |

## 四、环境检查结论

1. **/etc/hosts 临时 IP 可移除**：hosts 里 `39.96.213.166 dashscope.aliyuncs.com` 是旧 IP，
   官方 DNS 已轮换（8.140.217.18 / 8.152.159.24），实测绕过 hosts 直连真实 IP 返回 200。
   建议执行：`sudo sed -i '' '/dashscope.aliyuncs.com/d' /etc/hosts`（需管理员权限，未代为执行）
2. **JVM `-Dhttp.nonProxyHosts=*`**：建议保留。系统代理 127.0.0.1:15732 会拦截 DashScope 与内网
   服务调用；全量绕过在此开发环境语义正确，无泄露面。
3. **API Key 无泄露**：kzai-wrap 日志与 aigc 源码均无 `sk-` 明文；key 仅存于仓库外 `~/.dashscope_key`
   与服务进程环境变量。

## 五、遗留事项（下一步：Embedding + RAG）

- 场景 3 中模型对课程大纲逐章展开属自由发挥——RAG 接入知识库后可由检索片段约束
- BUY Dry-run 评测路径已修复但本轮未跑批量评测（按要求暂缓）
- Embedding Provider 核查（v1/v2/v3 对比）、ES REAL 独立测试索引、费用估算待汇报授权后执行
