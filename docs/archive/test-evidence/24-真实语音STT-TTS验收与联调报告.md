# 24-真实语音STT-TTS验收与联调报告（M15.11）

> 执行依据：用户《M15.11 真实语音 STT/TTS 接入验证与端到端验收指令》（预算 0.50 元；最多 1 次 Paraformer 识别任务 + 1 次 Sambert 合成）。
> **本轮结果如实声明：语音协议 5 项实测修正完成并实证；文件转写路径因环境限制（file_urls 需公网 URL）不可用；实时识别模型 paraformer-realtime-v2 已下线；STT 未成功 → TTS 按纪律未执行。真实语音识别待重新授权（qwen3-asr-flash-realtime 协议实现或可用模型 ID）。**

## 1. 本轮代码基线

- 运行 jar SHA-256 前缀链：**57cc37e2（启动时发现落后于构建）→ 5adea8d3 → e3aad2bc → 55dd93e9 → babfd966 → 51d32568（最终）**——每轮协议修复后重新打包重启并核对 SHA/PID（上轮旧 jar 教训落实：本轮启动即发现运行 jar 落后并先修复）
- 修复均集中在 `DashScopeSpeechProvider`（SpeechToTextProvider/TextToSpeechProvider 抽象不变，未侵入 ChatProvider）

## 2. Paraformer 官方协议核对与实测修正（5 项，全部真实 API 实证）

| # | 项 | 原实现 | 实测修正 |
|---|---|---|---|
| 1 | 上传凭证 | POST /api/v1/uploads → **405** | **GET /api/v1/uploads?Action=get_policy&model=xxx** |
| 2 | 凭证响应结构 | 假设 data=字符串(host)+policy=对象 | **data 为对象**：oss_access_key_id/policy(base64)/signature/upload_dir/upload_host（下划线命名） |
| 3 | OSS 上传 URL | uploadHost+"/"+ossKey → **405** | **bucket 域名根路径**（key 仅在表单）+ x-oss-forbid-overwrite=true |
| 4 | OSS 成功码 | 仅接受 200 | **204 No Content 即成功**（实测返回 204） |
| 5 | 任务提交头 | 无 X-DashScope-Async → **403 current user api does not support synchronous calls** | **必须 X-DashScope-Async: enable** |

修正后文件转写链路全通：凭证 ✓ → OSS 上传 ✓ → 任务提交 ✓（task_id 获得）→ 轮询 ✓。

## 3. 文件转写路径的环境限制（D15-39）

任务提交成功后轮询返回 **FAILED: FILE_403_FORBIDDEN**——官方文档核对：`input.file_urls` 必须是**公网可下载 URL（HTTP 200）**；DashScope 临时上传对象为 private ACL 不可被转写服务拉取。本环境无公网对象存储 → **该路径在当前部署形态不可用**。代码留档 `transcribeByFileUrl`（协议已全部实证修正），具备公网 OSS 的环境可恢复。

## 4. 实时识别路径尝试（D15-40）

切换官方推荐的短语音交互路径：Paraformer 实时识别 WebSocket（run-task/audio/asr/recognition → 3200B 分片上传 → finish-task → result-generated 汇总）——实现完成，真实会话返回 **Model not found (paraformer-realtime-v2) (ModelNotFound)**：该模型已下线。当前官方实时 ASR 为 **qwen3-asr-flash-realtime**（0.00033 元/秒，36K 秒/月免费），但走 **OmniRealtime 协议**（`wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime`，需 workspace ID 与新协议栈）——超出本轮最小修复范围，**待授权实现**。

## 5. STT 执行记录（如实）

| 尝试 | 阶段 | 结果 | 任务提交 | 计费 |
|---|---|---|---|---|
| 1 | 上传凭证 | 405（POST 误用） | 0 | 0 |
| 2 | 凭证解析 | 结构不符 | 0 | 0 |
| 3 | OSS 上传 | 405（URL 拼 key） | 0 | 0 |
| 4 | OSS 204 误判 | 上传实际成功 | 0 | 0 |
| 5 | 任务提交+轮询 | **任务提交成功 → FAILED FILE_403** | **1** | FAILED 按 3s 音频 ≈0.00024 元（或 36K 秒月度免费额度内=0） |
| 6 | 实时识别会话 | ModelNotFound（模型下线） | 0（未成任务） | 0 |

**每轮失败均停止分析、修复后重试**（协议 bug 修复属授权内「检查和修复已有语音代码」）；5 次失败均为协议实现缺陷与环境限制，无连续盲目付费调试。

## 6. TTS：按纪律未执行（D15-41）

「STT 成功才执行 TTS」——STT 未成功即不调用 Sambert（预算保护）。协议实现保持：run-task/continue-task/finish-task、task_group=audio/tts、二进制音频帧累积、WAV 组装、streaming=false 如实标注（X-Tts-Streaming:false）。计费：1 元/万字符、每模型每月 3 万字符免费——15 字测试句最坏 0.0015 元。下轮授权可直接验证。

## 7. 语音安全预检（全部通过）

- 测试音频：macOS `say`（Eddy 中文）本地生成 **3.0 秒 wav**（16kHz 单声道，100KB），内容「你好，我想学习 Java 课程」——无个人敏感信息、非真实用户录音 ✓
- AudioValidator：5MB 上限/格式白名单/魔数+扩展名双验/时长限制/空与非音频拒绝（既有实现保持）
- 音频不落库：内存处理+临时文件 finally 删除（实现核查）；不入 MySQL/Redis/知识库/业务文件 ✓
- 凭证与 Key：上传凭证仅服务端持有；Authorization 仅服务端→DashScope；前端仅持 JWT ✓
- 第三方传输说明：音频发送至 DashScope 临时 OSS（独立 upload_dir、private ACL、forbid-overwrite）✓
- 并发：SpeechConcurrencyGuard（Semaphore voice.max-concurrent）——`SpeechConcurrencyTest` 3/3 ✅；真实语音任务并发=1 ✓
- TTS 测试文本：15 字无敏感内容 ✓
- 客户端取消≠上游停止计费：报告中如实标注（不宣称 future.cancel 绝对不收费）✓

## 8. 前端语音交互（协议层验收）

- 识别回填红线 ✅：`AiWorkbenchView.vue` L528「识别文字仅回填输入框，绝不自动发送（防误识别触发业务操作）」——draft.value 回填+「请确认或修改后发送」——**STT 后不自动 /chat** ✓
- 播报：用户点击触发（speakingIndex）；卡片 JSON 不朗读（stripForTts）；streaming=false 如实标注 ✓
- **实机麦克风/扬声器验收：需要用户手动执行**（本环境无物理音视频设备，headless 模拟不作为真实设备验收）——如实标记
- 端到端联调：STT 未成功未执行完整链路；回填→确认→发送门禁的前半段已代码级验证

## 9. 数据与日志检查

- 原始 12 张核心表结构零改动；无新增表；orders 218/评测 31 条不变
- ai_model_call_log：source=STT/TTS 口径既有；本轮真实失败调用已计入；语音 token 恒 NULL 不伪造 ✓
- 音频内容/识别正文不进日志；无 API Key/上传凭证泄露（grep 核查）✓

## 10. 全量回归（Maven/MySQL/ES/Redis）

- `mvn clean install -DforkCount=0` ✅ **297 tests（XML 权威）failures=0 errors=0 skipped=2 → 通过 295**（含 SpeechConcurrencyTest 3/3 与语音协议修复回归）
- **真实 MySQL 8 IT：13/13**（本轮无相关变更，结论沿用并复验计数零残留 599=599）
- ES IT ✅ 随构建；Redis 回归 ✅ 随构建；M14 MOCK 语音回归 ✅
- 前端生产构建 ✅ 2.70s；AI SSE/BUY 门禁 ✅（M15.10 实测沿用）

## 11. R1–R5 清理

删除：/tmp/kzai-stt-test.aiff、/tmp/stt_resp*.json、/tmp/publish_prompts.sh 等；保留全部测试/SEED/迁移/审计/历史评测/语音双 Provider（DashScope+Mock）。清理后 clean install 全绿。

## 12. 费用与账单核验

| 项 | 估算 | 依据 |
|---|---|---|
| Paraformer 文件转写（FAILED 任务 1 个，3s 音频） | ≈0.00024 元（0.00008 元/秒；或月度 36K 秒免费额度内=0） | 官方价格页 |
| Sambert | 0（未调用） | — |
| **本轮合计** | **≈0.0002 元**（预算 0.50 元） | 以控制台账单为准 |

## 13. 剩余问题与待授权清单

| # | 项 | 说明 |
|---|---|---|
| A | **qwen3-asr-flash-realtime 协议实现**（~100 行：OmniRealtime 端点 + workspace ID 配置）+ 1 次真实识别授权 | 实现后 STT 可闭环 |
| B | TTS 验证授权（1 次 Sambert，最坏 0.0015 元） | 协议已就绪 |
| C | 有合法权益学员账号（PROTECTED 变体）/BUY 已确认后续轮 Dry-run 触发 | M15.10 遗留（指令明确本轮不执行） |
| D | 实机麦克风/扬声器手动验收 | 用户环境执行 |
| E | 公网对象存储（可选） | 恢复文件转写路径（长音频场景） |

## 14. 结论

本轮完成语音协议 5 项实测修正（全部真实 API 实证）与文件转写环境限制定性；**真实 STT 识别未成功（环境限制+模型下线），TTS 按纪律未执行**——不虚报成功。前端回填红线与安全机制代码级验证通过，全仓回归全绿、数据零破坏、费用 ≈0.0002 元。**等待授权：qwen3-asr-flash-realtime 协议实现 + STT/TTS 真实验证重试。**
