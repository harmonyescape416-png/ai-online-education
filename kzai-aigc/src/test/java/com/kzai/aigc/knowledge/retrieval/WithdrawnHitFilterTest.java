package com.kzai.aigc.knowledge.retrieval;

import com.kzai.aigc.client.TradeClient;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.config.DynamicAiParams;
import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import com.kzai.aigc.knowledge.index.EsKnowledgeGateway;
import com.kzai.aigc.knowledge.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ES 替身仍返回已撤回文档的切片时，MySQL 权威状态必须把它丢掉。
 * 这不是真实 Elasticsearch 链路。
 */
class WithdrawnHitFilterTest {

    @Test
    void withdrawnDocumentIsNotReturned() {
        EsKnowledgeGateway es = mock(EsKnowledgeGateway.class);
        KnowledgeEmbeddingService embedding = mock(KnowledgeEmbeddingService.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        DynamicAiParams dynamic = mock(DynamicAiParams.class);
        when(dynamic.topK()).thenReturn(6);
        when(dynamic.minScore()).thenReturn(0.0);
        when(embedding.embedQuery(any())).thenReturn(new float[] {0.1f});
        when(embedding.provider()).thenReturn("MOCK");
        when(documents.selectList(any())).thenReturn(List.of());
        KnowledgeDocument withdrawn = new KnowledgeDocument();
        withdrawn.setId(8L);
        withdrawn.setReviewStatus(KnowledgeDocument.REVIEW_WITHDRAWN);
        withdrawn.setDeleted(0);
        withdrawn.setIndexVersion(1);
        withdrawn.setAccessLevel(KnowledgeDocument.ACCESS_PUBLIC);
        when(documents.selectById(8L)).thenReturn(withdrawn);
        when(es.knnSearch(any(), anyInt(), any(), anyList())).thenReturn(List.of(Map.of(
                "docId", "8", "score", 0.9, "indexVersion", 1, "text", "旧切片", "chunkIndex", 0)));
        @SuppressWarnings("unchecked")
        ObjectProvider<com.kzai.aigc.knowledge.index.RealIndexGuard> guards = mock(ObjectProvider.class);
        KnowledgeRetriever retriever = new KnowledgeRetriever(
                es, embedding, documents, mock(TradeClient.class), new AiProperties(), dynamic, guards);
        KnowledgeRetriever.RetrievalResult result = retriever.retrieve("问题", 1L, List.of("STUDENT"), null);
        assertTrue(result.hits.isEmpty());
    }
}
