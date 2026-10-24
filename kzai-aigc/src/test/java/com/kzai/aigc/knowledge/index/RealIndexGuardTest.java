package com.kzai.aigc.knowledge.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M15 REAL：REAL 索引防混用守卫 fail-closed 单测。
 * 授权要求：Provider 为 MOCK / 别名指向非 REAL 前缀 / 维度不匹配时必须拒绝检索，
 * 绝不允许真实 1024 维查询打到旧 MOCK 256 维索引。
 */
class RealIndexGuardTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private KnowledgeEmbeddingService embedding(boolean mock, int dims) {
        KnowledgeEmbeddingService e = mock(KnowledgeEmbeddingService.class);
        when(e.mock()).thenReturn(mock);
        when(e.dimensions()).thenReturn(dims);
        return e;
    }

    private EsKnowledgeGateway es(String aliasedIndex, Integer dims) {
        EsKnowledgeGateway g = mock(EsKnowledgeGateway.class);
        when(g.currentAliasedIndex()).thenReturn(aliasedIndex);
        when(g.vectorDimsOf(aliasedIndex)).thenReturn(dims);
        return g;
    }

    private RealIndexGuard newGuard(KnowledgeEmbeddingService e, EsKnowledgeGateway g) {
        return new RealIndexGuard(e, g, "text-embedding-v4", "text-embedding-v4",
                "kzai-knowledge-real-test", 0);
    }

    @Test
    void 构造期模型不匹配即failFast() {
        KnowledgeEmbeddingService e = embedding(false, 1024);
        EsKnowledgeGateway g = es("kzai-knowledge-real-test-v1", 1024);
        try {
            new RealIndexGuard(e, g, "text-embedding-v1", "text-embedding-v4", "kzai-knowledge-real-test", 0);
            throw new AssertionError("应当 fail-fast");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("模型不匹配"));
        }
    }

    @Test
    void mockProvider必须拒绝() {
        var err = newGuard(embedding(true, 256), es("kzai-knowledge-real-test-v1", 256)).checkReadable();
        assertEquals("EMBEDDING_PROVIDER_IS_MOCK", err.orElseThrow());
    }

    @Test
    void 别名指向MOCK索引必须拒绝() {
        var err = newGuard(embedding(false, 1024), es("kzai-knowledge-v9001", 256)).checkReadable();
        assertTrue(err.orElseThrow().startsWith("REAL_INDEX_PREFIX_MISMATCH"));
    }

    @Test
    void 维度不匹配必须拒绝() {
        var err = newGuard(embedding(false, 1024), es("kzai-knowledge-real-test-v1", 256)).checkReadable();
        assertTrue(err.orElseThrow().startsWith("REAL_INDEX_DIMS_MISMATCH"));
    }

    @Test
    void 别名缺失与mapping不可读必须拒绝() {
        assertEquals("REAL_INDEX_ALIAS_MISSING",
                newGuard(embedding(false, 1024), es(null, null)).checkReadable().orElseThrow());
        assertEquals("REAL_INDEX_MAPPING_UNREADABLE: kzai-knowledge-real-test-v1",
                newGuard(embedding(false, 1024), es("kzai-knowledge-real-test-v1", null)).checkReadable().orElseThrow());
    }

    @Test
    void 全部匹配才允许检索() {
        var err = newGuard(embedding(false, 1024), es("kzai-knowledge-real-test-v1", 1024)).checkReadable();
        assertTrue(err.isEmpty(), "不应拒绝: " + err);
    }
}
