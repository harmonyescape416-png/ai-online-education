package com.kzai.aigc.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M13 运行时动态系统参数（三层生效链，与 M8 PromptManager 同构）：
 * <pre>
 *   Nacos（kzai-ai-params.json / tj.ai，权威）→ Redis AI:PARAMS:EFFECTIVE（重启恢复）
 *     → 代码内置默认（sys_config_param 初始值，永不失败）
 * </pre>
 * <ul>
 *   <li>发布：ConfigParamService 写 Nacos → 本进程原子替换（AtomicReference，运行中请求
 *       读取快照稳定）→ 写 Redis 供重启恢复与跨实例 30s 轮询感知；</li>
 *   <li>读取点：KnowledgeRetriever（TopK/阈值）、ChatSessionService（历史上限）、
 *       ChatService（每日限额、超时）；缺失键回退 AiProperties 静态默认；</li>
 *   <li>值非法（越界/类型错误）拒绝应用并保留最后有效配置。</li>
 * </ul>
 */
@Slf4j
@Component
public class DynamicAiParams {

    public static final String KEY_TOP_K = "knowledge.top-k";
    public static final String KEY_MIN_SCORE = "knowledge.min-score";
    public static final String KEY_HISTORY_LIMIT = "chat.history-limit";
    public static final String KEY_TIMEOUT_SECONDS = "model.timeout-seconds";
    public static final String KEY_DAILY_LIMIT = "chat.daily-limit";
    // M14 语音参数
    public static final String KEY_VOICE_ENABLED = "voice.enabled";
    public static final String KEY_STT_MAX_BYTES = "stt.max-upload-bytes";
    public static final String KEY_STT_MAX_SECONDS = "stt.max-seconds";
    public static final String KEY_TTS_MAX_CHARS = "tts.max-chars";
    public static final String KEY_VOICE_CONCURRENCY = "voice.max-concurrent";

    public static final String DATA_ID = "kzai-ai-params.json";
    public static final String GROUP = "tj.ai";
    private static final String REDIS_KEY = "AI:PARAMS:EFFECTIVE";

    private final RestClient nacos;
    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final AiProperties props;

    /** 当前生效值快照（键→字符串值）；AtomicReference 保证运行中请求读取稳定 */
    private final java.util.concurrent.atomic.AtomicReference<Map<String, String>> effective =
            new java.util.concurrent.atomic.AtomicReference<>(new LinkedHashMap<>());

    public DynamicAiParams(@Value("${spring.cloud.nacos.discovery.server-addr}") String serverAddr,
                           StringRedisTemplate redis, ObjectMapper om, AiProperties props) {
        this.nacos = RestClient.builder().baseUrl("http://" + serverAddr).build();
        this.redis = redis;
        this.om = om;
        this.props = props;
    }

    /** 默认值（与 sql/14_m13_management.sql 初始化一致；sys_config_param 缺失时的兜底） */
    public static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(KEY_TOP_K, "6");
        m.put(KEY_MIN_SCORE, "0.6");
        m.put(KEY_HISTORY_LIMIT, "30");
        m.put(KEY_TIMEOUT_SECONDS, "60");
        m.put(KEY_DAILY_LIMIT, "200");
        m.put(KEY_VOICE_ENABLED, "true");
        m.put(KEY_STT_MAX_BYTES, "5242880");
        m.put(KEY_STT_MAX_SECONDS, "60");
        m.put(KEY_TTS_MAX_CHARS, "500");
        m.put(KEY_VOICE_CONCURRENCY, "2");
        return m;
    }

    /** Nacos 是否可用（发布前置检查） */
    public boolean nacosAvailable() {
        try {
            nacos.get().uri("/nacos/").retrieve().toEntity(String.class);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @PostConstruct
    void init() {
        Map<String, String> start = new LinkedHashMap<>(defaults());
        // 1) Redis 恢复（重启后恢复上次发布值）
        try {
            String cached = redis.opsForValue().get(REDIS_KEY);
            if (cached != null && !cached.isBlank()) {
                Map<String, String> fromRedis = om.readValue(cached, Map.class);
                if (valid(fromRedis)) {
                    start.putAll(fromRedis);
                    log.info("[config.params] Redis 恢复生效参数 {} 个", fromRedis.size());
                }
            }
        } catch (Exception ex) {
            log.warn("[config.params] Redis 恢复失败（使用默认）: {}", ex.getMessage());
        }
        // 2) Nacos 权威覆盖
        Map<String, String> fromNacos = fetchFromNacos();
        if (fromNacos != null) {
            start.putAll(fromNacos);
        }
        effective.set(start);
        log.info("[config.params] 生效参数初始化: {}", start);
    }

    /** 30s 轮询：感知其它实例的发布（本实例发布走进程内原子替换，无需等待） */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void pollNacos() {
        Map<String, String> fromNacos = fetchFromNacos();
        if (fromNacos == null || fromNacos.isEmpty()) {
            return;
        }
        Map<String, String> next = new LinkedHashMap<>(effective.get());
        boolean changed = false;
        for (Map.Entry<String, String> e : fromNacos.entrySet()) {
            if (!e.getValue().equals(next.get(e.getKey()))) {
                next.put(e.getKey(), e.getValue());
                changed = true;
            }
        }
        if (changed && valid(next)) {
            apply(next, "NACOS_POLL");
        }
    }

    /** 发布后本进程立即生效 + Redis 持久化（供重启恢复/跨实例） */
    public boolean apply(Map<String, String> params, String source) {
        if (!valid(params)) {
            log.warn("[config.params] 拒绝非法参数（保留最后有效配置）: {}", params);
            return false;
        }
        effective.set(new LinkedHashMap<>(params));
        try {
            redis.opsForValue().set(REDIS_KEY, om.writeValueAsString(effective.get()), Duration.ofDays(30));
        } catch (Exception ex) {
            log.warn("[config.params] Redis 写入失败（进程内已生效）: {}", ex.getMessage());
        }
        log.info("[config.params] 参数已生效 source={} values={}", source, effective.get());
        return true;
    }

    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(effective.get());
    }

    // ---- 类型化读取（缺失/非法回退 AiProperties 静态默认） ----

    public int topK() {
        return intOf(KEY_TOP_K, props.getKnowledgeTopK(), 1, 20);
    }

    public double minScore() {
        return doubleOf(KEY_MIN_SCORE, props.getKnowledgeMinScore(), 0, 1);
    }

    public int historyLimit() {
        return intOf(KEY_HISTORY_LIMIT, props.getHistoryLimit(), 5, 50);
    }

    public int timeoutSeconds() {
        return intOf(KEY_TIMEOUT_SECONDS, 60, 1, 300);
    }

    public int dailyLimit() {
        return intOf(KEY_DAILY_LIMIT, 200, 1, 1000);
    }

    // ---- M14 语音参数 ----
    public boolean voiceEnabled() {
        return Boolean.parseBoolean(effective.get().getOrDefault(KEY_VOICE_ENABLED, "true"));
    }

    public int sttMaxBytes() {
        return intOf(KEY_STT_MAX_BYTES, 5 * 1024 * 1024, 64 * 1024, 20 * 1024 * 1024);
    }

    public int sttMaxSeconds() {
        return intOf(KEY_STT_MAX_SECONDS, 60, 5, 120);
    }

    public int ttsMaxChars() {
        return intOf(KEY_TTS_MAX_CHARS, 500, 50, 2000);
    }

    public int voiceConcurrency() {
        return intOf(KEY_VOICE_CONCURRENCY, 2, 1, 8);
    }

    private int intOf(String key, int def, int min, int max) {
        try {
            int v = Integer.parseInt(effective.get().getOrDefault(key, String.valueOf(def)));
            return Math.max(min, Math.min(max, v));
        } catch (Exception ex) {
            return def;
        }
    }

    private double doubleOf(String key, double def, double min, double max) {
        try {
            double v = Double.parseDouble(effective.get().getOrDefault(key, String.valueOf(def)));
            return Math.max(min, Math.min(max, v));
        } catch (Exception ex) {
            return def;
        }
    }

    /** 全量值合法性校验（类型+范围；未知键忽略——向前兼容） */
    private boolean valid(Map<String, String> params) {
        try {
            for (Map.Entry<String, String> e : params.entrySet()) {
                switch (e.getKey()) {
                    case KEY_TOP_K -> {
                        int v = Integer.parseInt(e.getValue());
                        if (v < 1 || v > 20) return false;
                    }
                    case KEY_MIN_SCORE -> {
                        double v = Double.parseDouble(e.getValue());
                        if (v < 0 || v > 1) return false;
                    }
                    case KEY_HISTORY_LIMIT -> {
                        int v = Integer.parseInt(e.getValue());
                        if (v < 5 || v > 50) return false;
                    }
                    case KEY_TIMEOUT_SECONDS -> {
                        int v = Integer.parseInt(e.getValue());
                        if (v < 1 || v > 300) return false;
                    }
                    case KEY_DAILY_LIMIT -> {
                        int v = Integer.parseInt(e.getValue());
                        if (v < 1 || v > 1000) return false;
                    }
                    case KEY_VOICE_ENABLED -> {
                        if (!"true".equalsIgnoreCase(e.getValue()) && !"false".equalsIgnoreCase(e.getValue()))
                            return false;
                    }
                    case KEY_STT_MAX_BYTES -> {
                        int v = Integer.parseInt(e.getValue());
                        if (v < 65536 || v > 20971520) return false;
                    }
                    case KEY_STT_MAX_SECONDS -> {
                        int v = Integer.parseInt(e.getValue());
                        if (v < 5 || v > 120) return false;
                    }
                    case KEY_TTS_MAX_CHARS -> {
                        int v = Integer.parseInt(e.getValue());
                        if (v < 50 || v > 2000) return false;
                    }
                    case KEY_VOICE_CONCURRENCY -> {
                        int v = Integer.parseInt(e.getValue());
                        if (v < 1 || v > 8) return false;
                    }
                    default -> { /* 未知键忽略 */ }
                }
            }
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** 从 Nacos 读取参数（null=Nacos 不可用/无配置；与 Prompt 有界读取同模式） */
    @SuppressWarnings("unchecked")
    public Map<String, String> fetchFromNacos() {
        try {
            String body = nacos.get()
                    .uri(uri -> uri.path("/nacos/v1/cs/configs")
                            .queryParam("dataId", DATA_ID)
                            .queryParam("group", GROUP)
                            .build())
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonNode node = om.readTree(body);
            JsonNode params = node.path("params");
            Map<String, String> out = new LinkedHashMap<>();
            params.fields().forEachRemaining(f -> out.put(f.getKey(), f.getValue().asText()));
            return valid(out) ? out : null;
        } catch (Exception ex) {
            log.debug("[config.params] Nacos 读取失败: {}", ex.getMessage());
            return null;
        }
    }
}
