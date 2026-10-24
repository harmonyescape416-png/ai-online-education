# 70-AI 对话真实 Qwen 模式切换报告（M15.23）

> 执行依据：用户指令「AI 对话从 mock 切换为真实 Qwen 模型」+ 提供 DASHSCOPE_API_KEY（授权真实调用——验证 1 次对话）。**完成后暂停等待验收。**

## 一、切换实施 ✅（零代码改动——配置驱动）

| 项 | 状态 |
|---|---|
| **切换机制** | DASHSCOPE_ENABLED=true + DASHSCOPE_API_KEY 环境变量 → kzai.aigc.real-enabled=true → AiModelConfig 装配 ChatClient → **DashScopeChatProvider 生效**（MockChatProvider 条件禁用——逻辑保留可回退） |
| **切换脚本** | scripts/start-aigc-real.sh <KEY> [model]（打包注入+重启+验证）/ stop-aigc-real.sh（回退 MOCK） |
| **模型** | qwen-plus（默认北京地域 endpoint——多参构造缺陷规避已文档化） |
| **Key 安全** | 运行时环境变量注入——**不落盘/不进 Git/报告脱敏** |

## 二、验证 ✅（1 次真实调用）

| 验证项 | 结果 |
|---|---|
| **Provider 生效** | 启动日志「DashScope 真实模型 Provider 已生效」+ EmbeddingModeGuard 提示（Embedding 仍 MOCK——如实） |
| **真实对话（SSE 流式）** | POST /chat → **qwen-plus 真实回复**：「我是「课栈智伴」的课程推荐助手，依托平台真实课程数据库…」——**逐 chunk SSE 分发** ✅ |
| **浏览器 UI** | **徽标「真实模型 qwen-plus」**✅ + 右栏 Provider：DASHSCOPE/通义千问/可用 ✅ |
| **界面/交互** | 对话界面与交互流程不变（会话/消息/SSE/工具卡片/热门问题全保留） |
| **回退** | stop-aigc-real.sh 随时回退 MOCK |

## 三、异常处理（内置，代码级）

- 401/403 → UNAUTHORIZED（key 无效友好提示，不泄露）
- 超时/网络 → TIMEOUT（提示稍后重试）
- 429 → RATE_LIMIT
- 空响应 → UPSTREAM 异常（不冒充成功）
- 上层 GlobalExceptionHandler 统一处理（不泄露 key/堆栈）

## 四、回归与文件

- **Maven** ✅ 323 tests 全绿（321 通过）
- **前端构建** ✅
- **修改**：scripts/start-aigc-real.sh / stop-aigc-real.sh（新增）；**AI 核心代码零改动**（配置驱动切换）

## 五、使用说明与回退

- **当前状态**：真实 Qwen 模式运行中（qwen-plus）
- **回退 MOCK**：./scripts/stop-aigc-real.sh（演示/开发零计费）
- **切真实**：./scripts/start-aigc-real.sh <KEY>（随时可切）
- **计费提示**：真实调用按 DashScope 计费（qwen-plus）——MOCK 与 REAL 由环境变量控制，不影响其他功能

## 六、如实标注

- **Embedding Provider 仍 MOCK**（启动日志提示——知识库向量检索为 MOCK Embedding；真实 Embedding 切换同理待 Key 配置——下一轮）
- 本轮真实调用 1 次（对话验证）——后续按需
