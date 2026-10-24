package com.kzai.aigc.speech;

import com.kzai.aigc.config.DynamicAiParams;
import com.kzai.aigc.stats.ModelCallLogService;
import com.kzai.aigc.stats.entity.AiModelCallLog;
import com.kzai.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M14 语音服务编排（成本与安全控制中枢）：
 * <ul>
 *   <li>开关：{@code voice.enabled} 动态参数（默认开启，可一键关闭全部语音入口）；</li>
 *   <li>并发：JVM 内 {@link Semaphore}（{@code voice.max-concurrent}，默认 2）——
 *       <b>不依赖 Redis</b>，作为 Redis 故障 fail-open 窗口期的语音计费最小护栏
 *       （语音按次/秒计费，风险高于文本；该护栏不改变 M13 已批准的聊天降级语义）；</li>
 *   <li>校验：魔数/大小/时长边界（音频不落库，仅内存处理，请求结束即释放）；</li>
 *   <li>审计：复用 ai_model_call_log（source=STT/TTS；token 恒 NULL；音频时长/字符数
 *       作为独立语音用量写结构化运行日志，不冒充文本 token）；写入失败不影响业务。</li>
 * </ul>
 */
@Slf4j
@Service
public class SpeechService {

    public static final String KEY_VOICE_ENABLED = "voice.enabled";
    public static final String KEY_STT_MAX_BYTES = "stt.max-upload-bytes";
    public static final String KEY_STT_MAX_SECONDS = "stt.max-seconds";
    public static final String KEY_TTS_MAX_CHARS = "tts.max-chars";
    public static final String KEY_VOICE_CONCURRENCY = "voice.max-concurrent";

    public static final String SOURCE_STT = "STT";
    public static final String SOURCE_TTS = "TTS";

    private final DynamicAiParams params;
    private final SpeechToTextProvider sttProvider;
    private final TextToSpeechProvider ttsProvider;
    private final AudioValidator validator;
    private final ModelCallLogService callLog;

    /** 语音并发护栏（JVM 内，与 Redis 独立） */
    private final AtomicReference<Semaphore> concurrency = new AtomicReference<>(new Semaphore(2));
    /** 当前信号量基准许可数（用于安全重建：仅在完全空闲时调整容量） */
    private final AtomicReference<Integer> basePermits = new AtomicReference<>(2);

    public SpeechService(DynamicAiParams params, SpeechToTextProvider sttProvider,
                         TextToSpeechProvider ttsProvider, AudioValidator validator,
                         ModelCallLogService callLog) {
        this.params = params;
        this.sttProvider = sttProvider;
        this.ttsProvider = ttsProvider;
        this.validator = validator;
        this.callLog = callLog;
    }

    public boolean voiceEnabled() {
        return "true".equalsIgnoreCase(params.snapshot().getOrDefault(KEY_VOICE_ENABLED, "true"));
    }

    /** 语音是否 MOCK 模式（无真实凭据） */
    public boolean mockMode() {
        return sttProvider.mock();
    }

    public int sttMaxBytes() {
        try {
            return Math.max(64 * 1024, Math.min(20 * 1024 * 1024,
                    Integer.parseInt(params.snapshot().getOrDefault(KEY_STT_MAX_BYTES, String.valueOf(5 * 1024 * 1024)))));
        } catch (Exception ex) {
            return 5 * 1024 * 1024;
        }
    }

    public int sttMaxSeconds() {
        try {
            return Math.max(5, Math.min(120,
                    Integer.parseInt(params.snapshot().getOrDefault(KEY_STT_MAX_SECONDS, "60"))));
        } catch (Exception ex) {
            return 60;
        }
    }

    public int ttsMaxChars() {
        try {
            return Math.max(50, Math.min(2000,
                    Integer.parseInt(params.snapshot().getOrDefault(KEY_TTS_MAX_CHARS, "500"))));
        } catch (Exception ex) {
            return 500;
        }
    }

    private void refreshConcurrency() {
        int want = Math.max(1, Math.min(8,
                Integer.parseInt(params.snapshot().getOrDefault(KEY_VOICE_CONCURRENCY, "2"))));
        Semaphore cur = concurrency.get();
        int base = basePermits.get();
        // 仅在完全空闲（剩余许可==基准）时安全重建容量；运行中请求保持原信号量稳定
        if (want != base && cur.availablePermits() == base
                && basePermits.compareAndSet(base, want)) {
            concurrency.compareAndSet(cur, new Semaphore(want));
        }
    }

    /** STT：校验 → 并发护栏 → Provider → 调用日志。返回文字与 mock 标识。 */
    public SttOutcome transcribe(long userId, byte[] audio, String filename, String requestId) {
        requireEnabled();
        long t0 = System.nanoTime();
        try {
            AudioValidator.AudioFormat fmt = validator.validate(audio, filename, sttMaxBytes());
            acquire();
            try {
                SpeechToTextProvider.SttResult r = sttProvider.transcribe(audio, fmt.code(), params.timeoutSeconds());
                callLog(requestId, userId, SOURCE_STT, r.provider(), r.modelCode(), true, null,
                        (int) ((System.nanoTime() - t0) / 1_000_000));
                // 语音用量（独立概念）：字节数/格式写结构化运行日志，不冒充 token
                log.info("[speech.stt] requestId={} user={} bytes={} format={} 耗时={}ms mock={}",
                        requestId, userId, audio.length, fmt.code(),
                        (System.nanoTime() - t0) / 1_000_000, r.mock());
                return new SttOutcome(r.text(), r.provider(), r.modelCode(), r.mock());
            } finally {
                concurrency.get().release();
            }
        } catch (SpeechValidationException vex) {
            callLog(requestId, userId, SOURCE_STT, providerCode(), null, false, vex.getErrorCode(),
                    (int) ((System.nanoTime() - t0) / 1_000_000));
            throw new BizException(vex.getMessage());
        } catch (BizException quota) {
            throw quota;
        } catch (RuntimeException pex) {
            callLog(requestId, userId, SOURCE_STT, providerCode(), null, false, "STT_PROVIDER_ERROR",
                    (int) ((System.nanoTime() - t0) / 1_000_000));
            throw new BizException("语音识别失败（" + providerCode() + "），请稍后重试或改用文字输入");
        }
    }

    /** TTS：文本校验 → 并发护栏 → Provider → 调用日志。 */
    public TtsOutcome synthesize(long userId, String text, String requestId) {
        requireEnabled();
        if (text == null || text.isBlank()) {
            throw new BizException("播报内容不能为空");
        }
        String normalized = text.trim();
        if (normalized.length() > ttsMaxChars()) {
            throw new BizException("播报内容超过长度上限（" + ttsMaxChars() + " 字），请缩短后重试");
        }
        long t0 = System.nanoTime();
        acquire();
        try {
            TextToSpeechProvider.TtsResult r = ttsProvider.synthesize(normalized, params.timeoutSeconds());
            if (r.audio() == null || r.audio().length == 0) {
                throw new IllegalStateException("TTS 返回空音频");
            }
            callLog(requestId, userId, SOURCE_TTS, r.provider(), r.modelCode(), true, null,
                    (int) ((System.nanoTime() - t0) / 1_000_000));
            log.info("[speech.tts] requestId={} user={} chars={} audioBytes={} streaming={} mock={}",
                    requestId, userId, normalized.length(), r.audio().length, ttsProvider.streaming(), r.mock());
            return new TtsOutcome(r.audio(), r.contentType(), r.provider(), r.modelCode(), r.mock(),
                    ttsProvider.streaming());
        } catch (RuntimeException pex) {
            callLog(requestId, userId, SOURCE_TTS, providerCode(), null, false, "TTS_PROVIDER_ERROR",
                    (int) ((System.nanoTime() - t0) / 1_000_000));
            if (pex instanceof BizException b) {
                throw b;
            }
            throw new BizException("语音合成失败（" + providerCode() + "），请稍后重试");
        } finally {
            concurrency.get().release();
        }
    }

    private void requireEnabled() {
        if (!voiceEnabled()) {
            throw new BizException("语音功能已由管理员关闭");
        }
    }

    private void acquire() {
        refreshConcurrency();
        try {
            if (!concurrency.get().tryAcquire()) {
                throw new BizException("语音服务繁忙，请稍后重试");
            }
        } catch (BizException b) {
            throw b;
        }
    }

    private String providerCode() {
        return sttProvider.mock() ? "MOCK" : "DASHSCOPE";
    }

    private void callLog(String requestId, long userId, String source, String provider,
                         String modelCode, boolean ok, String errorKind, int totalMs) {
        try {
            AiModelCallLog r = new AiModelCallLog();
            r.setRequestId(requestId);
            r.setUserId(userId);
            r.setSource(source);
            r.setProvider(provider);
            r.setModelCode(modelCode);
            r.setSuccess(ok ? 1 : 0);
            r.setErrorKind(errorKind);
            r.setTotalLatencyMs(totalMs);
            // STT/TTS 无文本 token：三列保持 NULL（不可用即 unavailable，不冒充）
            callLog.record(r);
        } catch (RuntimeException ex) {
            log.warn("[speech] 调用日志写入失败（不阻断语音业务）: {}", ex.getMessage());
        }
    }

    public record SttOutcome(String text, String provider, String modelCode, boolean mock) {}

    public record TtsOutcome(byte[] audio, String contentType, String provider,
                             String modelCode, boolean mock, boolean streaming) {}
}
