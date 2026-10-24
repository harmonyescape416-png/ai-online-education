package com.kzai.aigc.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M15 修正：DashScope 官方语音 API（按官方文档结构完整实现；本环境无 Key，标记「协议实现完成、真实 API 未验证」）。
 *
 * <p><b>STT（Paraformer 录音文件识别，官方异步任务流程）</b>：
 * <ol>
 *   <li>POST /api/v1/uploads —— 获取 OSS 上传凭证（policy/签名/目标地址）；
 *       音频以受控临时文件落盘（仅上传期间，finally 删除，不入业务库/知识库/审计正文）；</li>
 *   <li>multipart POST 到凭证指定地址 —— 上传音频至 DashScope 临时 OSS（官方短期对象，非业务存储）；</li>
 *   <li>POST /api/v1/services/audio/asr/transcription —— model=paraformer-v2，input.file_urls=[临时 OSS 地址]，
 *       返回 output.task_id；</li>
 *   <li>GET /api/v1/tasks/{task_id} —— 轮询 PENDING/RUNNING → SUCCEEDED/FAILED（上限=timeout）；
 *       SUCCEEDED 后解析 output.results[*].text 拼接转写文本。</li>
 * </ol>
 * 音频会发送至 DashScope（第三方语音服务）——页面已如实告知；不进入本地业务存储。
 *
 * <p><b>TTS（sambert 官方 WebSocket 流式协议）</b>：M14 初版误用文本模型 HTTP 端点（已修正）——官方 sambert 走
 * WebSocket wss://dashscope.aliyuncs.com/api-ws/v1/inference/（task group=audio/tts，二进制帧=音频）。
 * 本实现使用 JDK 内置 WebSocket（零新依赖）：run-task → continue-task（文本）→ finish-task，
 * 累积二进制帧；接口契约按「合成完成后整段返回」（{@link #streaming() streaming()=false 如实标注}），
 * 浏览器边收边播为未实现能力（真实流式播放未验证）。
 *
 * <p><b>验收边界（不混淆）</b>：源码实现完成 ✓ / 协议按官方文档实现 ✓ / 真实 API 验证 ✗（无 Key）/
 * 真实浏览器闭环 ✗。装配条件：kzai.aigc.real-enabled=true 且 DASHSCOPE_API_KEY 环境变量存在。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kzai.aigc", name = "real-enabled", havingValue = "true")
public class DashScopeSpeechProvider implements SpeechToTextProvider, TextToSpeechProvider {

    public static final String PROVIDER = "DASHSCOPE";
    private static final String STT_MODEL = "paraformer-v2";
    /** M15.11：实时识别模型（短语音交互推荐路径，免公网 URL 依赖） */
    private static final String STT_REALTIME_MODEL = "paraformer-realtime-v2";
    /** M15.11-R1：短录音 STT 最终选型（HTTP 同步、Base64 Data URL） */
    private static final String STT_QWEN_ASR_MODEL = "qwen3-asr-flash";
    private static final String TTS_MODEL = "sambert-zhichu-v1";
    private static final String BASE = "https://dashscope.aliyuncs.com";
    private static final String WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";

    private final RestClient rest;
    private final ObjectMapper om;
    private final HttpClient http;
    private final String apiKey;

    public DashScopeSpeechProvider(ObjectMapper om) {
        this.apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DASHSCOPE_ENABLED=true 但 DASHSCOPE_API_KEY 缺失（拒绝以空凭据装配真实语音）");
        }
        this.rest = RestClient.builder().baseUrl(BASE)
                .defaultHeader("Authorization", "bearer " + apiKey).build();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.om = om;
        log.info("[speech.dashscope] 真实语音 Provider 已装配（STT=paraformer-v2 异步任务流程；TTS=sambert WebSocket 流式协议）——真实 API 未验证");
    }

    // ------------------------------------------------------------------
    // STT：官方上传凭证 → OSS 上传 → 任务提交 → 轮询
    // ------------------------------------------------------------------

    /**
     * M15.11：对外入口切换为 Paraformer 实时识别 WebSocket（短语音交互官方推荐路径，免公网 URL）。
     * 原文件转写实现（transcribeByFileUrl）保留留档：其 file_urls 要求公网可下载 URL，
     * 当前部署环境无公网存储（FILE_403_FORBIDDEN 实证），具备公网对象存储的环境可恢复二选一。
     */
    /** M15.11-R1：STT 最终选型 qwen3-asr-flash HTTP 同步识别（Base64 Data URL，免公网 URL/免 workspace；
     * 0.00022 元/秒，3s 音频 ≈0.0007 元）。Paraformer 文件转写（需公网 URL）与实时识别
     * （paraformer-realtime-v2 当前账号/地域 ModelNotFound）保留留档备查。 */
    @Override
    public SttResult transcribe(byte[] audio, String format, int timeoutSeconds) {
        return transcribeByQwenAsr(audio, format);
    }

    /**
     * qwen3-asr-flash 非实时同步识别（官方 OpenAI 兼容端点）：
     * POST /compatible-mode/v1/chat/completions，
     * messages[].content=[{type:"input_audio", input_audio:{data:"data:{mime};base64,{b64}"}}]，
     * 响应 choices[0].message.content = 识别文本。
     */
    private SttResult transcribeByQwenAsr(byte[] audio, String format) {
        String mime = switch (format == null ? "wav" : format.toLowerCase()) {
            case "wav", "x-wav", "wave" -> "audio/wav";
            case "mp3", "mpeg" -> "audio/mpeg";
            case "ogg" -> "audio/ogg";
            case "m4a", "mp4" -> "audio/mp4";
            case "flac" -> "audio/flac";
            case "aac" -> "audio/aac";
            default -> "audio/wav";
        };
        String dataUrl = "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(audio);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", STT_QWEN_ASR_MODEL);
        req.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of(
                        "type", "input_audio",
                        "input_audio", Map.of("data", dataUrl))))));
        req.put("stream", false);
        req.put("asr_options", Map.of("enable_itn", false));
        try {
            JsonNode resp = rest.post().uri("/compatible-mode/v1/chat/completions")
                    .header("Content-Type", "application/json")
                    .body(om.writeValueAsString(req))
                    .retrieve().body(JsonNode.class);
            String text = resp == null ? "" : resp.path("choices").path(0)
                    .path("message").path("content").asText("");
            if (text.isBlank()) {
                throw new IllegalStateException("qwen3-asr 未返回识别文本: "
                        + (resp == null ? "null" : resp.toString()));
            }
            return new SttResult(text, PROVIDER, STT_QWEN_ASR_MODEL, false);
        } catch (Exception ex) {
            log.warn("[speech.dashscope] STT 失败（qwen3-asr-flash 同步识别）: {}", ex.getMessage());
            throw new IllegalStateException("STT_PROVIDER_ERROR: " + ex.getMessage(), ex);
        }
    }

    /**
     * M15.11 留档：文件转写（paraformer-v2）实现——协议已按官方实测修正
     * （GET uploads 凭证/OSS PostObject 根路径/X-DashScope-Async 头/204 语义），
     * 但 input.file_urls 要求公网可下载 URL（FILE_403_FORBIDDEN 实证不可用私有对象）。
     */
    @SuppressWarnings("unused")
    private SttResult transcribeByFileUrl(byte[] audio, String format, int timeoutSeconds) {
        Path tmp = null;
        try {
            // ① 受控临时文件（仅上传期间；finally 删除——成功/失败/超时/取消均覆盖）
            tmp = Files.createTempFile("kzai-stt-", "." + safeExt(format));
            Files.write(tmp, audio);

            // ② 获取上传凭证（M15.11 协议修正：官方为 GET /api/v1/uploads?Action=get_policy&model=xxx，
            // 此前误用 POST → 405 Method Not Allowed；字段结构 data(上传host)/policy(凭证) 不变）
            JsonNode policy = rest.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/uploads")
                            .queryParam("Action", "get_policy")
                            .queryParam("model", STT_MODEL).build())
                    .header("X-DashScope-OssResourceResolve", "enable")
                    .retrieve().body(JsonNode.class);
            // M15.11 按真实响应修正结构：data 为对象（含 base64 policy/signature/upload_host/
            // oss_access_key_id 等，下划线命名），非「data 字符串 + policy 对象」
            JsonNode data = policy == null ? null : policy.path("data");
            if (data == null || data.isNull() || data.isMissingNode()
                    || data.path("upload_host").asText().isBlank()) {
                throw new IllegalStateException("获取 DashScope 上传凭证失败: "
                        + (policy == null ? "null" : policy.toString()));
            }
            String ossAccessKeyId = data.path("oss_access_key_id").asText();
            String signature = data.path("signature").asText();
            String encodePolicy = data.path("policy").asText(); // base64 编码的 policy 文本
            String uploadDir = data.path("upload_dir").asText();
            String uploadHost = data.path("upload_host").asText();

            // ③ 上传音频到官方临时 OSS（M15.11 修正：OSS PostObject 上传到 bucket 域名根路径，
            // key 只在表单字段中——此前拼接 key 到 URL 导致对象级路径 405）
            String ossKey = uploadDir + "/" + UUID.randomUUID() + "." + safeExt(format);
            String ossUrl = uploadHost + "/";
            String boundary = "----kzai" + UUID.randomUUID().toString().replace("-", "");
            var parts = new ArrayList<byte[]>();
            addFormField(parts, boundary, "OSSAccessKeyId", ossAccessKeyId);
            addFormField(parts, boundary, "Signature", signature);
            addFormField(parts, boundary, "policy", encodePolicy);
            addFormField(parts, boundary, "x-oss-object-acl", "private");
            addFormField(parts, boundary, "x-oss-forbid-overwrite", "true");
            addFormField(parts, boundary, "key", ossKey);
            addFileField(parts, boundary, "file", tmp, audio);
            parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest upload = HttpRequest.newBuilder(URI.create(ossUrl))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(parts)).build();
            HttpResponse<String> upResp = http.send(upload, HttpResponse.BodyHandlers.ofString());
            // M15.11 修正：OSS PostObject 成功返回 204 No Content（此前仅接受 200）
            if (upResp.statusCode() != 200 && upResp.statusCode() != 204) {
                throw new IllegalStateException("音频上传 OSS 失败 HTTP " + upResp.statusCode()
                        + " " + upResp.body());
            }

            // ④ 提交转写任务（file_urls=官方临时 OSS 地址）
            // M15.11 修正：异步任务端点必须设置 X-DashScope-Async: enable——
            // 缺失该头官方报「current user api does not support synchronous calls」
            Map<String, Object> taskReq = new LinkedHashMap<>();
            taskReq.put("model", STT_MODEL);
            taskReq.put("input", Map.of("file_urls", List.of(ossUrl)));
            taskReq.put("parameters", Map.of("language_hints", List.of("zh")));
            JsonNode taskResp = rest.post().uri("/api/v1/services/audio/asr/transcription")
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-Async", "enable")
                    .body(om.writeValueAsString(taskReq))
                    .retrieve().body(JsonNode.class);
            String taskId = taskResp == null ? "" : taskResp.path("output").path("task_id").asText();
            if (taskId.isBlank()) {
                throw new IllegalStateException("转写任务提交失败: "
                        + (taskResp == null ? "null" : taskResp.toString()));
            }

            // ⑤ 轮询任务结果（官方 1s 间隔；上限=timeout）
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000);
                JsonNode st = rest.get().uri("/api/v1/tasks/" + taskId)
                        .retrieve().body(JsonNode.class);
                String status = st == null ? "" : st.path("output").path("task_status").asText();
                if ("SUCCEEDED".equals(status)) {
                    StringBuilder text = new StringBuilder();
                    for (JsonNode r : st.path("output").path("results")) {
                        text.append(r.path("text").asText());
                    }
                    return new SttResult(text.toString(), PROVIDER, STT_MODEL, false);
                }
                if ("FAILED".equals(status) || "CANCELED".equals(status) || "UNKNOWN".equals(status)) {
                    throw new IllegalStateException("转写任务 " + status + ": "
                            + st.path("output").path("message").asText(""));
                }
            }
            throw new IllegalStateException("转写任务轮询超时（>" + timeoutSeconds + "s）task=" + taskId);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("STT_PROVIDER_ERROR: interrupted", ie);
        } catch (Exception ex) {
            log.warn("[speech.dashscope] STT 失败（协议实现完成，真实 API 未验证）: {}", ex.getMessage());
            throw new IllegalStateException("STT_PROVIDER_ERROR: " + ex.getMessage(), ex);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp); // 受控临时文件清理
                } catch (Exception ignore) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // TTS：sambert 官方 WebSocket（run-task/continue-task/finish-task；二进制帧=音频）
    // ------------------------------------------------------------------

    @Override
    public TtsResult synthesize(String text, int timeoutSeconds) {
        ConcurrentLinkedQueue<byte[]> audioChunks = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean finishedFlag = new AtomicBoolean(false);
        try {
            String taskId = "tts-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            WebSocket.Listener listener = new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                    // M15.11-R1：解析 Sambert 文本事件（task-finished/task-failed）
                    try {
                        JsonNode root = om.readTree(data.toString());
                        String event = root.path("header").path("event").asText();
                        if ("task-finished".equals(event)) {
                            finishedFlag.set(true);
                        } else if ("task-failed".equals(event)) {
                            error.set(new IllegalStateException("Sambert task-failed: "
                                    + root.path("header").path("error_message").asText("")
                                    + " (" + root.path("header").path("error_code").asText("") + ")"));
                        }
                    } catch (Exception ex) {
                        error.set(ex);
                    }
                    w.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onBinary(WebSocket w, java.nio.ByteBuffer data, boolean last) {
                    byte[] b = new byte[data.remaining()];
                    data.get(b);
                    audioChunks.add(b);
                    w.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
                    if (statusCode != 1000) {
                        error.set(new IllegalStateException("TTS WebSocket 关闭 " + statusCode + ": " + reason));
                    }
                    return null;
                }

                @Override
                public void onError(WebSocket w, Throwable t) {
                    error.set(t);
                }
            };
            WebSocket ws = http.newWebSocketBuilder()
                    .header("Authorization", "bearer " + apiKey)
                    .header("X-DashScope-DataInspection", "enable")
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(WS_URL), listener)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);

            // M15.11-R1 协议纠错（官方 Sambert）：仅发送一次 run-task，input.text 一次性传全部文本；
            // Sambert 不支持 continue-task/finish-task（此前误发导致协议违规）；
            // 完成判定=收到 header.event=task-finished 文本事件；task_group=audio
            ws.sendText(frame(taskId, "run-task", Map.of(
                    "task_group", "audio",
                    "task", "tts",
                    "function", "SpeechSynthesizer",
                    "model", TTS_MODEL,
                    "parameters", Map.of("text_type", "PlainText", "format", "wav", "sample_rate", 16000),
                    "input", Map.of("text", text))), true);

            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            boolean finished = false;
            while (System.currentTimeMillis() < deadline) {
                if (error.get() != null) {
                    throw new IllegalStateException("TTS_WS_ERROR: " + error.get().getMessage(), error.get());
                }
                if (finishedFlag.get() && !audioChunks.isEmpty()) {
                    finished = true;
                    break;
                }
                Thread.sleep(100);
            }
            ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done");
            ws.abort();
            if (!finished) {
                throw new IllegalStateException("TTS 未收到 task-finished（超时 " + timeoutSeconds + "s）");
            }

            if (error.get() != null) {
                throw new IllegalStateException("TTS_WS_ERROR: " + error.get().getMessage(), error.get());
            }
            if (audioChunks.isEmpty()) {
                throw new IllegalStateException("TTS 未收到音频帧（超时 " + timeoutSeconds + "s）");
            }
            var out = new java.io.ByteArrayOutputStream();
            for (byte[] c : audioChunks) {
                out.writeBytes(c);
            }
            return new TtsResult(out.toByteArray(), "audio/wav", PROVIDER, TTS_MODEL, false);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TTS_PROVIDER_ERROR: interrupted", ie);
        } catch (Exception ex) {
            log.warn("[speech.dashscope] TTS 失败（sambert WebSocket 协议实现，真实 API 未验证）: {}", ex.getMessage());
            throw new IllegalStateException("TTS_PROVIDER_ERROR: " + ex.getMessage(), ex);
        }
    }

    private String frame(String taskId, String action, Map<String, Object> payload) throws Exception {
        return om.writeValueAsString(Map.of(
                "header", Map.of("action", action, "task_id", taskId, "streaming", "out"),
                "payload", payload));
    }

    private static String safeExt(String format) {
        String f = format == null ? "wav" : format.toLowerCase();
        return switch (f) {
            case "wav", "x-wav", "wave" -> "wav";
            case "mp3", "mpeg" -> "mp3";
            case "ogg" -> "ogg";
            case "webm" -> "webm";
            case "m4a", "mp4" -> "m4a";
            case "flac" -> "flac";
            case "aac" -> "aac";
            default -> "bin";
        };
    }

    private static void addFormField(List<byte[]> parts, String boundary, String name, String value) {
        parts.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void addFileField(List<byte[]> parts, String boundary, String name,
                                     Path file, byte[] content) throws Exception {
        parts.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + file.getFileName() + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(content);
        parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * M15.11：Paraformer 实时识别 WebSocket（task_group=audio/asr, function=recognition）。
     * 分片上传音频（~3200B/片）→ finish-task → 汇总 result-generated 句子文本。
     * 无需公网 URL；wav(16k) 直接作为输入格式。
     */
    private SttResult transcribeRealtime(byte[] audio, int timeoutSeconds) {
        java.util.concurrent.ConcurrentLinkedQueue<String> sentences =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        AtomicReference<String> finalText = new AtomicReference<>(null);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean started = new AtomicBoolean(false);
        try {
            String taskId = "asr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            WebSocket.Listener listener = new WebSocket.Listener() {
                private final StringBuilder buf = new StringBuilder();

                @Override
                public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                    buf.append(data);
                    if (last) {
                        String msg = buf.toString();
                        buf.setLength(0);
                        try {
                            JsonNode root = om.readTree(msg);
                            String event = root.path("header").path("event").asText();
                            switch (event) {
                                case "task-started" -> started.set(true);
                                case "result-generated" -> {
                                    String text = root.path("payload").path("output").path("sentence")
                                            .path("text").asText("");
                                    if (!text.isBlank()) {
                                        sentences.add(text);
                                    }
                                }
                                case "task-finished" -> finalText.compareAndSet(null,
                                        sentences.isEmpty() ? "" : sentences.peek());
                                case "task-failed" -> error.set(new IllegalStateException("ASR task-failed: "
                                        + root.path("header").path("error_message").asText("")
                                        + " (" + root.path("header").path("error_code").asText("") + ")"));
                                default -> { }
                            }
                        } catch (Exception ex) {
                            error.set(ex);
                        }
                    }
                    w.request(1);
                    return null;
                }

                @Override
                public void onError(WebSocket w, Throwable t) {
                    error.set(t);
                }
            };
            WebSocket ws = http.newWebSocketBuilder()
                    .header("Authorization", "bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(WS_URL), listener)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);

            ws.sendText(frame(taskId, "run-task", Map.of(
                    "task_group", "audio/asr",
                    "task", "asr",
                    "function", "recognition",
                    "model", STT_REALTIME_MODEL,
                    "parameters", Map.of("format", "wav", "sample_rate", 16000),
                    "input", Map.of())), true);

            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (!started.get() && System.currentTimeMillis() < deadline && error.get() == null) {
                Thread.sleep(20);
            }
            if (!started.get()) {
                ws.abort();
                throw new IllegalStateException("实时识别任务未启动（task-started 未收到，超时 "
                        + timeoutSeconds + "s）" + (error.get() == null ? "" : ": " + error.get()));
            }
            int chunk = 3200;
            for (int off = 0; off < audio.length; off += chunk) {
                int len = Math.min(chunk, audio.length - off);
                ws.sendBinary(java.nio.ByteBuffer.wrap(audio, off, len), false);
                Thread.sleep(20);
            }
            ws.sendBinary(java.nio.ByteBuffer.allocate(0), true);
            ws.sendText(frame(taskId, "finish-task", Map.of("input", Map.of())), true);

            while (System.currentTimeMillis() < deadline) {
                if (error.get() != null) {
                    ws.abort();
                    throw new IllegalStateException("ASR_WS_ERROR: " + error.get().getMessage(), error.get());
                }
                if (finalText.get() != null) {
                    break;
                }
                Thread.sleep(50);
            }
            ws.abort();
            if (finalText.get() == null) {
                throw new IllegalStateException("实时识别未获得文本（超时 " + timeoutSeconds + "s）");
            }
            return new SttResult(finalText.get(), PROVIDER, STT_REALTIME_MODEL, false);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("STT_PROVIDER_ERROR: interrupted", ie);
        } catch (Exception ex) {
            log.warn("[speech.dashscope] STT 失败（Paraformer 实时识别 WebSocket）: {}", ex.getMessage());
            throw new IllegalStateException("STT_PROVIDER_ERROR: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean mock() {
        return false;
    }
}
