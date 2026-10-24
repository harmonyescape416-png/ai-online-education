package com.kzai.aigc.knowledge;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kzai.aigc.client.TradeClient;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import com.kzai.aigc.knowledge.index.EsKnowledgeGateway;
import com.kzai.aigc.knowledge.mapper.KnowledgeDocumentMapper;
import com.kzai.aigc.knowledge.retrieval.KnowledgeRetriever;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M13 补充验收（M12 遗留缺口）：检索「权威版本白名单」预过滤的<b>边界回退</b>测试。
 * <p>
 * 核查任务书 2.8：「版本白名单超过 500 条时的回退机制有明确边界测试」。
 * 覆盖（Mock 依赖，纯单测，不依赖真实 ES）：
 * <ol>
 *   <li>已发布文档数 = 500（上限内）→ knnSearch 收到 500 个版本白名单子句（不回退）；</li>
 *   <li>已发布文档数 = 501（超限）→ knnSearch 收到 null（回退 3 倍候选扩大 + 逐条复核口径）；</li>
 *   <li>已发布文档数 = 3 → 白名单子句恰好 3 个；</li>
 *   <li>MySQL 查询异常 → 白名单 null（降级不放大权限，逐条复核兜底）；</li>
 *   <li>无已发布文档 → 白名单为空列表（kNN filter 无 should，行为与旧口径一致）。</li>
 * </ol>
 */
class KnowledgeRetrieverVersionFilterTest {

    private EsKnowledgeGateway es;
    private KnowledgeDocumentMapper documentMapper;
    private KnowledgeRetriever retriever;

    @BeforeAll
    static void initMpLambdaCache() {
        // Wrappers.lambdaQuery().select(SFunction) 依赖 MP TableInfo 缓存（生产由 MapperScan 初始化）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeDocument.class);
    }

    @BeforeEach
    void setUp() {
        es = mock(EsKnowledgeGateway.class);
        documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeEmbeddingService embedding = mock(KnowledgeEmbeddingService.class);
        // M15 REAL：检索侧走 embedQuery（query 类型）
        when(embedding.embedQuery(any())).thenReturn(new float[8]);
        retriever = new KnowledgeRetriever(es, embedding, documentMapper,
                mock(TradeClient.class), new AiProperties(),
                new com.kzai.aigc.config.DynamicAiParams("127.0.0.1:8848",
                        org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                        new com.fasterxml.jackson.databind.ObjectMapper(), new AiProperties()),
                noGuard());
        when(es.knnSearch(any(), anyInt(), any(), any())).thenReturn(new ArrayList<>());
    }

    /** M15 REAL：纯单测无 REAL 索引守卫 Bean，注入空 Provider（fail-open 于 Mock 链路） */
    private static org.springframework.beans.factory.ObjectProvider<com.kzai.aigc.knowledge.index.RealIndexGuard> noGuard() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public com.kzai.aigc.knowledge.index.RealIndexGuard getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.kzai.aigc.knowledge.index.RealIndexGuard getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.kzai.aigc.knowledge.index.RealIndexGuard getIfAvailable() {
                return null;
            }

            @Override
            public com.kzai.aigc.knowledge.index.RealIndexGuard getIfUnique() {
                return null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<KnowledgeDocument> publishedDocs(int n) {
        List<KnowledgeDocument> docs = new ArrayList<>();
        for (long i = 1; i <= n; i++) {
            KnowledgeDocument d = new KnowledgeDocument();
            d.setId(9_000_000L + i);
            d.setDeleted(0);
            d.setReviewStatus(KnowledgeDocument.REVIEW_PUBLISHED);
            d.setIndexVersion((int) i);
            docs.add(d);
        }
        return docs;
    }

    @Test
    void atLimit500_whitelistApplied_noFallback() {
        when(documentMapper.selectList(any(Wrapper.class))).thenReturn(publishedDocs(500));
        retriever.retrieve("q", 1L, List.of("STUDENT"), null);
        ArgumentCaptor<List<Map<String, Object>>> captor = forPairs();
        verify(es).knnSearch(any(), anyInt(), any(), captor.capture());
        List<Map<String, Object>> pairs = captor.getValue();
        assertNotNull(pairs, "恰好 500 条（上限内）不得回退");
        assertEquals(500, pairs.size());
    }

    @Test
    void overLimit501_fallbackToNull() {
        when(documentMapper.selectList(any(Wrapper.class))).thenReturn(publishedDocs(501));
        retriever.retrieve("q", 1L, List.of("STUDENT"), null);
        ArgumentCaptor<List<Map<String, Object>>> captor = forPairs();
        verify(es).knnSearch(any(), anyInt(), any(), captor.capture());
        assertNull(captor.getValue(), "超过 500 条上限必须回退（白名单=null，走 3 倍候选扩大+逐条复核）");
    }

    @Test
    void smallSet_pairCountMatches() {
        when(documentMapper.selectList(any(Wrapper.class))).thenReturn(publishedDocs(3));
        retriever.retrieve("q", 1L, List.of("STUDENT"), null);
        ArgumentCaptor<List<Map<String, Object>>> captor = forPairs();
        verify(es).knnSearch(any(), anyInt(), any(), captor.capture());
        assertEquals(3, captor.getValue().size());
    }

    @Test
    void mysqlFailure_fallbackToNull_noPermissionAmplification() {
        when(documentMapper.selectList(any(Wrapper.class)))
                .thenThrow(new RuntimeException("mysql down"));
        retriever.retrieve("q", 1L, List.of("STUDENT"), null);
        ArgumentCaptor<List<Map<String, Object>>> captor = forPairs();
        verify(es).knnSearch(any(), anyInt(), any(), captor.capture());
        assertNull(captor.getValue(), "MySQL 异常时白名单必须为 null（降级，由逐条复核兜底）");
    }

    @Test
    void emptyPublished_emptyWhitelistList() {
        when(documentMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        retriever.retrieve("q", 1L, List.of("STUDENT"), null);
        ArgumentCaptor<List<Map<String, Object>>> captor = forPairs();
        verify(es).knnSearch(any(), anyInt(), any(), captor.capture());
        List<Map<String, Object>> pairs = captor.getValue();
        assertNotNull(pairs);
        assertTrue(pairs.isEmpty(), "无已发布文档时白名单为空列表（kNN filter 不含 should 子句）");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<Map<String, Object>>> forPairs() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
