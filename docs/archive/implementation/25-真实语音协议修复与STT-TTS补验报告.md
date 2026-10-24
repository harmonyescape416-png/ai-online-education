# 25-真实语音协议修复与STT-TTS补验报告（M15.11-R1）

> 执行依据：用户《M15.11-R1 真实语音识别与合成补验专项》（预算 0.10 元；最多 1 次 STT + 1 次 TTS）。
> **结果：真实 STT 成功 + 真实 TTS 成功（双闭环），零业务副作用，费用 ≈0.0025 元。本轮完成后暂停，不启动 Docker 部署。**

## 1. 官方模型和协议核对结果

| 模型 | 状态 | 端点/协议 | 计费 |
|---|---|---|---|
| qwen3-asr-flash（本轮 STT 选型） | 可用 | POST /compatible-mode/v1/chat/completions（OpenAI 兼容），input_audio.data=Base64 Data URL | 0.00022 元/秒（北京） |
| paraformer-v2（文件转写） | 模型可用但需公网 file_urls | POST transcription + X-DashScope-Async | 0.00008 元/秒（36K 秒/月免费） |
| paraformer-realtime-v2 | 当前账号/地域 ModelNotFound（不下全平台下线结论，D15-44） | run-task 实时协议 | 0.00024 元/秒 |
| sambert-zhichu-v1（本轮 TTS） | 可用 | WebSocket run-task 单指令（task_group=audio） | 1 元/万字符（3 万字符/月免费） |

## 2. Paraformer ModelNotFound 的准确结论（D15-44 修正）

M15.11 曾表述「已下线」——修正为「当前账号/地域不可用」（不下全平台结论）。官方文档仍列出该系列；可能原因：账号模型权限/地域路由/模型 ID 更新。文件转写与实时识别实现均留档（transcribeByFileUrl/transcribeRealtime），环境具备条件后可恢复。

## 3. 短音频 STT 最终选型与实现（D15-43）

qwen3-asr-flash HTTP 同步识别（用户指令的最小可用方案）：
- 端点：POST /compatible-mode/v1/chat/completions（默认工作空间，无需 workspace ID）
- 请求：messages[].content=[{type:"input_audio",input_audio:{data:"data:audio/wav;base64,{b64}"}}]，asr_options.enable_itn=false
- 响应：choices[0].message.content = 识别文本
- 免公网 URL/免音频分片 WebSocket；复用现有 5MB 短音频上传契约（前端零改动）

## 4. Sambert run-task 协议修复（D15-42，用户指正）

| 项 | 修复前（违规） | 修复后（官方协议） |
|---|---|---|
| 指令序列 | run-task → continue-task → finish-task（Sambert 不支持） | 仅一次 run-task |
| 文本传入 | continue-task 的 input.text | run-task 的 input.text 一次传全部 |
| task_group | audio/tts | audio |
| parameters | format/sample_rate | + text_type=PlainText |
| 完成判定 | ws.isOutputClosed() | header.event=task-finished 文本事件 |
| 关闭 | abort | sendClose(NORMAL_CLOSURE)+abort |

## 5. 实际代码变更清单

DashScopeSpeechProvider.java 单文件：transcribe() 委托 → transcribeByQwenAsr()（新增，qwen3-asr-flash Base64 同步识别）；synthesize() Sambert 协议修复（单 run-task + task-finished 判定 + sendClose 正常关闭）；transcribeByFileUrl/transcribeRealtime 留档；STT_REALTIME_MODEL/STT_QWEN_ASR_MODEL 常量。

## 6. 真实 STT 请求与识别结果（成功）

- 请求：1 次（0.80 秒，经网关 JWT → AudioValidator → 内存 Base64 → DashScope）
- 识别文本 =「你好，我想学习 Java 课程。」——与 3.0 秒测试音频（macOS say Eddy 中文本地生成，无隐私）语义完全一致
- 返回：provider=DASHSCOPE、modelCode=qwen3-asr-flash、mock=False、requestId=9749100c… 可追踪
- 审计：ai_model_call_log source=STT success=1 入库；token=NULL 不伪造
- 费用：3 秒 × 0.00022 ≈ 0.0007 元

## 7. 真实 TTS 请求与音频结果（成功）

- 请求：1 次合成会话成功（0.55 秒；首调因请求体字段名误用 JSON body 返回「播报内容不能为空」——按 controller 契约 @RequestParam text 修正调用即成功，非协议缺陷、0 消耗）
- 音频：120,444 字节有效 WAV（RIFF/WAVE、Microsoft PCM 16bit mono 16000Hz）、时长 3.76 秒（15 字合理）
- 响应头：X-Tts-Provider=DASHSCOPE、X-Tts-Model=sambert-zhichu-v1、X-Tts-Mock=false、X-Tts-Streaming=false（如实保留非流式标注）、Content-Type=audio/wav
- afplay 本机扬声器播放执行成功（真实音频可解码播放）；WebSocket 正常关闭（sendClose NORMAL_CLOSURE）
- 审计：ai_model_call_log source=TTS success=1、total_latency_ms=544、token=NULL
- 费用：18 计费字符 × 1 元/万字符 ≈ 0.0018 元（或月度 3 万字符免费额度内=0）

## 8. 请求次数与预算执行

| 项 | 次数 | 说明 |
|---|---|---|
| 真实 STT | 1 | 授权 1 次（无失败重试、无模型切换尝试） |
| 真实 TTS | 2 次请求（第 1 次为本地参数名错误 0 消耗未建 WebSocket，第 2 次成功） | Sambert 合成会话实际 1 次 |
| qwen-plus | 0 | 未触发 |
| 费用 | ≈0.0025 元（STT 0.0007 + TTS 0.0018） | 预算 0.10 元，使用 2.5% |

本轮未发生超次（独立请求计数执行；每次真实调用前确认 jar SHA/进程/配置）。

## 9. MOCK/REAL 分离与隐私

- 真实调用：provider=DASHSCOPE、mock=false 双向标识（响应体+X-Tts-* 头）
- MOCK 路径完整保留（MockSpeechProvider），历史 MOCK 流量不计入 REAL 成本
- 音频隐私：内存处理+临时文件删除；不入 MySQL/Redis/知识库/业务文件；Base64 不写日志；识别正文不进日志
- 上传凭证流程（文件转写留档）不暴露 Key/凭证给前端

## 10. 前端验收状态

- 回填红线（代码级）：识别文字仅回填输入框、绝不自动 /chat（AiWorkbenchView L528「防误识别触发业务操作」）
- 播报用户点击触发、卡片不朗读（stripForTts）、X-Tts-Streaming:false 如实
- 实机麦克风/扬声器：需要用户手动验收（本机 afplay 扬声器播放成功仅证明音频文件有效；浏览器 MediaRecorder 录音链路需 HTTPS/localhost 实机验证）——如实标记
- 完整同次真实语音对话链路（录音→识别→发送→SSE→播报）：本轮不授权 qwen-plus 调用，未验证（可复用历史 SSE 结果联调，须标注非同次）——如实登记

## 11. 数据零破坏与回归

- 业务五表全程 218/212/163/35/205 不变；12 核心表结构零改动；31 条评测/历史批次完整
- mvn clean install -DforkCount=0：297 tests（XML 权威）failures=0 errors=0 skipped=2 → 通过 295（含 SpeechConcurrencyTest 3/3 与语音修复回归）
- 真实 MySQL 8 IT：13/13（上轮执行本轮无相关变更，结论沿用并复验计数零残留）
- ES IT 随构建 ✅；Redis 回归 ✅；MOCK 语音回归 ✅；前端生产构建 2.73s ✅
- AI SSE/BUY 门禁：M15.10 实测沿用（本轮未改相关代码）

## 12. R1–R5 清理

删除：/tmp/kzai-stt-test.aiff、/tmp/stt_r*.json、/tmp/tts_out*.wav、/tmp/tts_headers*.txt；保留全部测试/SEED/迁移/审计/历史评测/语音双 Provider（DashScope+Mock）。清理后 clean install 全绿。

## 13. 文档修订

- acceptance-matrix.md #27：REAL STT/TTS 双成功状态（区分 REAL 成功/前端联调/实机/完整链路四级）
- decisions.md：D15-42~D15-45（Sambert 纠错/STT 选型/Paraformer 结论修正/TTS 请求契约澄清）
- api-extension.md：无接口签名变更（TTS text=@RequestParam 为既有契约澄清）
- README：语音 REAL 状态段更新（下轮统一修订）

## 14. 失败与未解决问题

1. 本轮 TTS 首调参数名错误（本地调用方式，非代码缺陷）——0 消耗
2. 实机麦克风/扬声器、完整同次真实语音对话链路未验证（需用户环境/额外 qwen-plus 授权）
3. Paraformer（文件转写公网 URL 依赖 + 实时模型账号不可用）——留档待环境具备
4. 有权益学员 PROTECTED 变体、BUY 已确认后续轮 Dry-run 触发——M15.10 遗留继续挂起
5. Docker 容器级部署未验证

## 15. 是否具备进入 Docker 部署阶段的条件

**具备**：7 服务本地稳定、全仓 297 tests 全绿、REAL 四能力（qwen-plus 对话/工具/RAG/语音 STT+TTS）双闭环验证、评测体系与审计完备、费用全程可控（累计真实 API 费用 <0.05 元）。语音链路的实机验收与 Docker 编排实测为部署阶段前置项。

**本轮暂停：等待 Docker 部署与最终验收授权。**
