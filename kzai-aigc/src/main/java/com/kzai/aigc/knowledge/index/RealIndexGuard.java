package com.kzai.aigc.knowledge.index;

import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M15 REAL 索引防混用守卫（fail-closed）。
 * <p>
 * 授权要求：REAL RAG 测试链路必须在查询前严格校验——
 * Embedding Provider 为 DashScope、模型为 text-embedding-v4、实际向量维度与索引
 * mapping 匹配、别名指向 REAL 测试前缀索引；任一不满足即拒绝检索（fail-closed），
 * 绝不允许真实 1024 维查询静默打到旧 MOCK 256 维索引，也不允许静默降级为 REAL 标识。
 * <p>
 * 仅作用于 KNOWLEDGE 检索链路（KnowledgeRetriever 查询前调用）；
 * 普通 REAL qwen-plus 文字聊天（RECOMMEND/CONSULT 等不触发检索的 Agent）不受影响。
 * 校验结果缓存 30s，避免每轮检索重复请求 ES mapping。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kzai.aigc", name = "embedding-provider", havingValue = "dashscope")
public class RealIndexGuard {

    private final KnowledgeEmbeddingService embedding;
    private final EsKnowledgeGateway es;
    private final String expectedModel;
    private final String realIndexPrefix;
    private final long cacheTtlMs;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    private record Cached(Optional<String> error, long at) {}

    public RealIndexGuard(KnowledgeEmbeddingService embedding, EsKnowledgeGateway es,
                          @Value("${kzai.aigc.embedding-model:text-embedding-v1}") String embeddingModel,
                          @Value("${kzai.aigc.real-index-guard.expected-model:text-embedding-v4}") String expectedModel,
                          @Value("${kzai.aigc.real-index-guard.real-prefix:kzai-knowledge-real-test}") String realIndexPrefix,
                          @Value("${kzai.aigc.real-index-guard.cache-ttl-ms:30000}") long cacheTtlMs) {
        this.embedding = embedding;
        this.es = es;
        this.expectedModel = expectedModel;
        this.realIndexPrefix = realIndexPrefix;
        this.cacheTtlMs = cacheTtlMs;
        // 绑定校验：配置的 Embedding 模型必须与守卫期望一致（模型漂移即 fail-fast）
        if (!expectedModel.equals(embeddingModel)) {
            throw new IllegalStateException(
                    "REAL 索引守卫启用但 Embedding 模型不匹配：配置=" + embeddingModel
                            + " 期望=" + expectedModel + "（若确需换模型，请同步修改 "
                            + "kzai.aigc.real-index-guard.expected-model 并重建 REAL 索引，禁止混用向量空间）");
        }
        log.info("[knowledge.guard] REAL 索引防混用守卫已启用（model={} realPrefix={}）", expectedModel, realIndexPrefix);
    }

    /**
     * 检索前校验（fail-closed）：返回空=可检索；非空=拒绝原因（检索侧转为受控降级，
     * 返回明确错误而非伪造结果）。
     */
    public Optional<String> checkReadable() {
        long now = System.currentTimeMillis();
        Cached c = cache.get();
        if (c != null && now - c.at() < cacheTtlMs) {
            return c.error();
        }
        Optional<String> error = check();
        cache.set(new Cached(error, now));
        if (error.isPresent()) {
            log.error("[knowledge.guard] REAL 检索被拒绝（fail-closed）: {}", error.get());
        }
        return error;
    }

    private Optional<String> check() {
        // ① Provider 必须为真实 DashScope（MOCK 向量无语义，绝不允许标记 REAL RAG）
        if (embedding.mock()) {
            return Optional.of("EMBEDDING_PROVIDER_IS_MOCK");
        }
        // ② 别名必须解析到 REAL 测试前缀的物理索引（不允许打到 MOCK 索引）
        String aliased = es.currentAliasedIndex();
        if (aliased == null || aliased.isBlank()) {
            return Optional.of("REAL_INDEX_ALIAS_MISSING");
        }
        if (!aliased.startsWith(realIndexPrefix)) {
            return Optional.of("REAL_INDEX_PREFIX_MISMATCH: alias -> " + aliased
                    + " (expected prefix " + realIndexPrefix + ")");
        }
        // ③ 索引向量维度必须与当前 Embedding 实测维度一致（1024 vs 256 等不匹配即拒绝）
        Integer dims = es.vectorDimsOf(aliased);
        if (dims == null) {
            return Optional.of("REAL_INDEX_MAPPING_UNREADABLE: " + aliased);
        }
        int expected = embedding.dimensions();
        if (dims != expected) {
            return Optional.of("REAL_INDEX_DIMS_MISMATCH: index=" + dims + " embedding=" + expected);
        }
        return Optional.empty();
    }
}
