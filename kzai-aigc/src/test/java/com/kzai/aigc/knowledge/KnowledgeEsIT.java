package com.kzai.aigc.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.client.TradeClient;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import com.kzai.aigc.knowledge.embedding.MockKnowledgeEmbeddingService;
import com.kzai.aigc.knowledge.index.EsKnowledgeGateway;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import com.kzai.aigc.knowledge.mapper.KnowledgeDocumentMapper;
import com.kzai.aigc.knowledge.retrieval.KnowledgeRetriever;
import com.kzai.common.result.R;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 真实 Elasticsearch 8 集成测试（M9，Mock Embedding 链路——
 * <b>仅验证 ES 索引/检索/过滤/别名协议，不代表真实语义检索</b>；Mock 向量显著标识）。
 * 前置：本地 ES 8.11.4 于 127.0.0.1:9200。任务书 B-9/10/13/14/15/16。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeEsIT {

    /** M15 REAL：Mock 链路无 REAL 索引守卫（Provider 条件装配不存在），注入空 Provider */
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


    private static final ObjectMapper OM = new ObjectMapper();

    private EsKnowledgeGateway es;
    private KnowledgeEmbeddingService embedding;
    private KnowledgeRetriever retriever;
    private TradeClient tradeClient;
    private KnowledgeDocumentMapper documentMapper;

    private static final long DOC_PUBLIC = 900001L;
    private static final long DOC_PROTECTED = 900002L;
    private static final long COURSE_A = 888001L;
    private static final int VERSION = 9001;

    @BeforeAll
    void setUp() {
        AiProperties props = new AiProperties();
        es = new EsKnowledgeGateway(props, OM);
        org.junit.jupiter.api.Assertions.assertTrue(es.available(), "需要本地 ES 127.0.0.1:9200");
        embedding = new MockKnowledgeEmbeddingService();
        documentMapper = Mockito.mock(KnowledgeDocumentMapper.class);
        tradeClient = Mockito.mock(TradeClient.class);
        retriever = new KnowledgeRetriever(es, embedding, documentMapper, tradeClient, props,
                new com.kzai.aigc.config.DynamicAiParams("127.0.0.1:8848",
                        org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                        OM, props),
                noGuard());

        // 建版本化索引并切别名（隔离测试空间）
        es.createVersionedIndex(VERSION, embedding.dimensions());
        es.swapAlias(VERSION, es.currentAliasedIndex());

        // 文档桩：公开 / 课程受保护 / 多文档重建用 A、B
        when(documentMapper.selectById(DOC_PUBLIC)).thenReturn(pubDoc(DOC_PUBLIC, null,
                KnowledgeDocument.ACCESS_PUBLIC));
        when(documentMapper.selectById(DOC_PROTECTED)).thenReturn(pubDoc(DOC_PROTECTED, COURSE_A,
                KnowledgeDocument.ACCESS_PROTECTED));
        when(documentMapper.selectById(910001L)).thenReturn(pubDoc(910001L, null,
                KnowledgeDocument.ACCESS_PUBLIC));
        when(documentMapper.selectById(910002L)).thenReturn(pubDoc(910002L, null,
                KnowledgeDocument.ACCESS_PUBLIC));
    }

    private static com.kzai.aigc.knowledge.entity.KnowledgeDocument pubDoc(long id, Long courseId, String access) {
        com.kzai.aigc.knowledge.entity.KnowledgeDocument d =
                new com.kzai.aigc.knowledge.entity.KnowledgeDocument();
        d.setId(id);
        d.setTitle("IT 文档 " + id);
        d.setCourseId(courseId);
        d.setAccessLevel(access);
        d.setReviewStatus(KnowledgeDocument.REVIEW_PUBLISHED);
        d.setDeleted(0);
        return d;
    }

    private Map<String, Object> chunk(long docId, int chunkIndex, String text) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("chunkId", docId + "_" + chunkIndex);
        d.put("docId", docId);
        d.put("chunkIndex", chunkIndex);
        d.put("indexVersion", VERSION);
        d.put("docTitle", "IT 文档 " + docId);
        d.put("courseId", docId == DOC_PROTECTED ? COURSE_A : null);
        d.put("accessLevel", docId == DOC_PROTECTED
                ? KnowledgeDocument.ACCESS_PROTECTED
                : KnowledgeDocument.ACCESS_PUBLIC);
        d.put("reviewStatus", 1);
        d.put("text", text);
        d.put("vector", embedding.embed(text));
        return d;
    }

    @AfterAll
    void clean() {
        es.deleteByDocId(DOC_PUBLIC);
        es.deleteByDocId(DOC_PROTECTED);
    }

    @Test
    @Order(1)
    void bulkWrite_thenRealEsSearchRecallsExactVector() {
        String publicText = "本平台的退款规则为：开课后 7 天内可申请全额退款，超过 7 天按课时折算。";
        int written = es.bulkIndex(VERSION, List.of(
                chunk(DOC_PUBLIC, 0, publicText),
                chunk(DOC_PROTECTED, 0, "本课程内部讲义：Transformer 注意力机制的推导细节。")));
        assertEquals(2, written, "bulk 两条切片全部写入真实 ES");
    }

    @Test
    @Order(2)
    void exactTextQuery_scores1_andRecalled_publicVisible() {
        String q = "本平台的退款规则为：开课后 7 天内可申请全额退款，超过 7 天按课时折算。";
        KnowledgeRetriever.RetrievalResult r = retriever.retrieve(q, 3L, List.of("STUDENT"), null);
        assertFalse(r.degraded);
        assertEquals(1, r.hits.size(), "相同文本向量得分 1.0，必命中；受保护文档被过滤");
        assertEquals(String.valueOf(DOC_PUBLIC), r.hits.get(0).get("docId"));
        assertEquals(1.0, (Double) r.hits.get(0).get("score"), 1e-6);
        assertNotNull(r.hits.get(0).get("chunkId"));
    }

    @Test
    @Order(3)
    void protectedChunk_hiddenFromStudent_visibleToEntitledAndAdmin() {
        String protectedText = "本课程内部讲义：Transformer 注意力机制的推导细节。";
        // 未购学员：不可见
        when(tradeClient.hasEntitlement(anyLong(), anyLong())).thenReturn(R.ok(Boolean.FALSE));
        KnowledgeRetriever.RetrievalResult student =
                retriever.retrieve(protectedText, 5L, List.of("STUDENT"), null);
        assertTrue(student.hits.isEmpty(), "未购学员不得召回受保护讲义（fail-closed 权益过滤）");
        // 已购学员：可见
        when(tradeClient.hasEntitlement(anyLong(), anyLong())).thenReturn(R.ok(Boolean.TRUE));
        KnowledgeRetriever.RetrievalResult paid =
                retriever.retrieve(protectedText, 5L, List.of("STUDENT"), null);
        assertEquals(1, paid.hits.size());
        // 权益查询失败 → fail-closed
        when(tradeClient.hasEntitlement(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("trade down"));
        KnowledgeRetrievalResultAssert.empty(retriever.retrieve(protectedText, 5L, List.of("STUDENT"), null));
        // 管理员无需权益
        Mockito.reset(tradeClient);
        KnowledgeRetriever.RetrievalResult admin =
                retriever.retrieve(protectedText, 1L, List.of("ADMIN"), null);
        assertEquals(1, admin.hits.size());
    }

    @Test
    @Order(4)
    void deletedDoc_noLongerRecalled() {
        // DB 权威状态撤回 → 立即不可召回（即使 ES 向量仍在）
        com.kzai.aigc.knowledge.entity.KnowledgeDocument d = documentMapper.selectById(DOC_PUBLIC);
        d.setReviewStatus(KnowledgeDocument.REVIEW_WITHDRAWN);
        when(documentMapper.selectById(DOC_PUBLIC)).thenReturn(d);
        String q = "本平台的退款规则为：开课后 7 天内可申请全额退款，超过 7 天按课时折算。";
        assertTrue(retriever.retrieve(q, 3L, List.of("STUDENT"), null).hits.isEmpty(),
                "撤回文档立即不可召回");
        // 恢复发布 + ES 物理删除 → 也不可召回
        d.setReviewStatus(KnowledgeDocument.REVIEW_PUBLISHED);
        es.deleteByDocId(DOC_PUBLIC);
        assertTrue(retriever.retrieve(q, 3L, List.of("STUDENT"), null).hits.isEmpty(),
                "删除向量后不可召回");
        // 重新写回（供其他断言/演示）
        es.bulkIndex(VERSION, List.of(chunk(DOC_PUBLIC, 0, q)));
    }

    @Test
    @Order(6)
    void perDocRebuild_otherDocsRemainSearchable() {
        // M10 验收修复实证：逐文档就地重建（覆盖写 + 残留清理）不得影响其他文档可检索性
        long docA = 910001L;
        long docB = 910002L;
        Integer v = currentAliasVersion();
        org.junit.jupiter.api.Assertions.assertTrue(v != null && v > 0, "需已存在别名索引");
        String aOld = "文档A旧切片：退款规则全文说明。";
        String aNew = "文档A新切片：退款规则更新版说明。";
        String b0 = "文档B切片零：发票开具流程说明。";
        String b1 = "文档B切片一：课程有效期说明。";
        assertEquals(4, es.bulkIndex(v, java.util.List.of(
                chunkOf(docA, 0, aOld), chunkOf(docA, 1, "文档A旧切片占位二号。"),
                chunkOf(docB, 0, b0), chunkOf(docB, 1, b1))), "4 片写入");

        // —— 只重建 A：1 片新内容覆盖 A_0，清理 A_1 残留（复刻 KnowledgeIndexJobRunner.doReindex 语义）
        assertEquals(1, es.bulkIndex(v, java.util.List.of(chunkOf(docA, 0, aNew))));
        assertTrue(es.deleteByDocIdChunkIndexGte(docA, 1) >= 0);
        assertEquals(1, es.countByDocId(docA), "重建后 A 恰好 1 片");
        assertEquals(2, es.countByDocId(docB), "B 的切片完全不受影响");

        // B 仍可检索（同文本向量 → 得分 1.0）
        KnowledgeRetriever.RetrievalResult rb = retriever.retrieve(b0, 3L, java.util.List.of("STUDENT"), null);
        org.junit.jupiter.api.Assertions.assertEquals(1, rb.hits.size(), "重建 A 后 B 仍可被召回");
        org.junit.jupiter.api.Assertions.assertEquals(String.valueOf(docB), rb.hits.get(0).get("docId"));

        // A 的新内容可检索、旧内容不可再召回
        KnowledgeRetriever.RetrievalResult raNew = retriever.retrieve(aNew, 3L, java.util.List.of("STUDENT"), null);
        org.junit.jupiter.api.Assertions.assertEquals(1, raNew.hits.size());
        org.junit.jupiter.api.Assertions.assertEquals(String.valueOf(docA), raNew.hits.get(0).get("docId"));
        org.junit.jupiter.api.Assertions.assertTrue(
                retriever.retrieve(aOld, 3L, java.util.List.of("STUDENT"), null).hits.isEmpty(),
                "旧切片已被覆盖，不再召回");

        es.deleteByDocId(docA);
        es.deleteByDocId(docB);
    }

    /** 带发布版本的文档桩（indexVersion = MySQL 权威发布版本） */
    private static com.kzai.aigc.knowledge.entity.KnowledgeDocument pubDocVer(long id, Long courseId,
                                                                               String access,
                                                                               int indexVersion) {
        com.kzai.aigc.knowledge.entity.KnowledgeDocument d = pubDoc(id, courseId, access);
        d.setIndexVersion(indexVersion);
        d.setIndexStatus(KnowledgeDocument.INDEX_SUCCESS);
        return d;
    }

    /** 带发布版本的切片（chunkId 含版本号，indexVersion 为发布版本） */
    private Map<String, Object> chunkVer(long docId, int indexVersion, int chunkIndex, String text) {
        Map<String, Object> d = chunkOf(docId, chunkIndex, text);
        d.put("chunkId", docId + "_v" + indexVersion + "_" + chunkIndex);
        d.put("indexVersion", indexVersion);
        return d;
    }

    private Map<String, Object> chunkOf(long docId, int chunkIndex, String text) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("chunkId", docId + "_" + chunkIndex);
        d.put("docId", docId);
        d.put("chunkIndex", chunkIndex);
        d.put("indexVersion", currentAliasVersion());
        d.put("docTitle", "IT 文档 " + docId);
        d.put("courseId", null);
        d.put("accessLevel", "PUBLIC");
        d.put("reviewStatus", 1);
        d.put("text", text);
        d.put("vector", embedding.embed(text));
        return d;
    }

    private Integer currentAliasVersion() {
        String idx = es.currentAliasedIndex();
        if (idx == null || !idx.startsWith("kzai-knowledge-v")) {
            return null;
        }
        try {
            return Integer.parseInt(idx.substring("kzai-knowledge-v".length()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * M11 验收·故障注入：逐文档重建<b>中途失败</b>（版本化发布流程中断）。
     * <p>
     * 文档 C 已发布版本 1 的切片 C_v1_0/C_v1_1；重建只写入新版本 2 的首片 C_v2_0 后崩溃
     * （未发布、未清理）。期望：
     * <ul>
     *   <li>旧版本切片仍可召回（旧版本不被破坏）；</li>
     *   <li>未发布的新版本切片<b>不参与检索</b>（无新旧混用/部分覆盖）；</li>
     *   <li>发布切换后：新版本可召回，旧版本不可召回。</li>
     * </ul>
     */
    @Test
    @Order(7)
    void reindexCrashMidway_oldVersionIntact_unpublishedVersionNotSearchable_thenPublish() {
        long docC = 910003L;
        Integer v = currentAliasVersion();
        org.junit.jupiter.api.Assertions.assertTrue(v != null && v > 0, "需已存在别名索引");
        String old0 = "文档C旧切片零：旧版退款规则说明。";
        String old1 = "文档C旧切片一：旧版发票规则说明。";
        String fresh = "文档C新版本切片零：新版退款规则说明（重建中途写入）。";
        // 已发布版本 1（MySQL 权威版本 = 1）
        when(documentMapper.selectById(docC)).thenReturn(pubDocVer(docC, null,
                KnowledgeDocument.ACCESS_PUBLIC, 1));
        assertEquals(2, es.bulkIndex(v, java.util.List.of(
                chunkVer(docC, 1, 0, old0), chunkVer(docC, 1, 1, old1))), "旧版本 2 片写入");

        // —— 故障注入：仅写入新版本首片后崩溃（未发布、无清理）
        assertEquals(1, es.bulkIndex(v, java.util.List.of(chunkVer(docC, 2, 0, fresh))));

        // 旧版本切片仍可召回
        KnowledgeRetriever.RetrievalResult rOld =
                retriever.retrieve(old1, 3L, java.util.List.of("STUDENT"), null);
        org.junit.jupiter.api.Assertions.assertTrue(rOld.hits.size() >= 1,
                "重建中途失败后旧版本切片仍应可召回（实际命中 " + rOld.hits.size() + "）");

        // 未发布的新版本切片不得参与检索
        KnowledgeRetriever.RetrievalResult rFresh =
                retriever.retrieve(fresh, 3L, java.util.List.of("STUDENT"), null);
        org.junit.jupiter.api.Assertions.assertTrue(rFresh.hits.isEmpty(),
                "未发布的新版本切片不得参与检索（否则为新旧混用/部分覆盖）");

        // 发布切换：MySQL 权威版本 → 2 后，新版本可召回、旧版本不可召回
        when(documentMapper.selectById(docC)).thenReturn(pubDocVer(docC, null,
                KnowledgeDocument.ACCESS_PUBLIC, 2));
        KnowledgeRetriever.RetrievalResult rAfterPublish =
                retriever.retrieve(fresh, 3L, java.util.List.of("STUDENT"), null);
        org.junit.jupiter.api.Assertions.assertEquals(1, rAfterPublish.hits.size(),
                "发布切换后新版本切片应可召回");
        KnowledgeRetriever.RetrievalResult rOldAfterPublish =
                retriever.retrieve(old1, 3L, java.util.List.of("STUDENT"), null);
        org.junit.jupiter.api.Assertions.assertTrue(rOldAfterPublish.hits.isEmpty(),
                "发布后旧版本切片应不再召回");

        es.deleteByDocId(docC);
    }

    @Test
    @Order(5)
    void aliasSwap_oldIndexKeptUntilVerified() {
        // 版本化：创建新版本索引不影响别名；切换后 currentAliasedIndex 变化；旧索引保留可回滚
        int v = (int) (System.currentTimeMillis() % 100000) + 10; // 每次运行唯一，避免残留
        String before = es.currentAliasedIndex();
        assertTrue(es.createVersionedIndex(v, embedding.dimensions()));
        assertEquals(before, es.currentAliasedIndex(), "切换前别名不变（旧知识库可用）");
        assertTrue(es.swapAlias(v, before), "别名原子切换");
        assertEquals("kzai-knowledge-v" + v, es.currentAliasedIndex());
        es.swapAlias(VERSION, "kzai-knowledge-v" + v); // 切回
        es.deletePhysicalIndex(v); // 清理
    }

    /**
     * M12 验收·多版本混合数据挤占测试（真实 ES）：
     * 文档 E 已发布 V1（6 片），重建「中途崩溃」残留 <b>20 片未发布 V2 切片</b>（大于
     * 无预过滤时的 3 倍候选窗口 18）；文档 F 已发布 6 片。期望（版本预过滤生效）：
     * <ul>
     *   <li>F 始终可检索（不被 E 的 20 片无效版本挤出候选窗口）——重建 E 不影响 F；</li>
     *   <li>检索 E 内容只返回 V1 切片，任何 V2（未发布）切片不得出现；</li>
     *   <li>对照证据：无预过滤的原始 k=18 窗口中 V2 切片占据位数（仅日志，展示挤占风险）。</li>
     * </ul>
     */
    @Test
    @Order(8)
    void mixedMultiVersion_staleChunksDoNotCrowdOutValidDocs() {
        long docE = 910004L;
        long docF = 910005L;
        Integer v = currentAliasVersion();
        org.junit.jupiter.api.Assertions.assertTrue(v != null && v > 0, "需已存在别名索引");

        String textE = "文档E挤占测试切片：完整退货政策与七日无理由退款细则全文。";
        String textF = "文档F对照测试切片：证书发放与电子结业证明申请流程全文。";

        // 文档桩：E/F 均已发布，权威版本 V1
        when(documentMapper.selectById(docE)).thenReturn(pubDocVer(docE, null,
                KnowledgeDocument.ACCESS_PUBLIC, 1));
        when(documentMapper.selectById(docF)).thenReturn(pubDocVer(docF, null,
                KnowledgeDocument.ACCESS_PUBLIC, 1));
        // 权威版本白名单（M12 预过滤数据源）：E→V1、F→V1
        when(documentMapper.selectList(any())).thenReturn(java.util.List.of(
                pubDocVer(docE, null, KnowledgeDocument.ACCESS_PUBLIC, 1),
                pubDocVer(docF, null, KnowledgeDocument.ACCESS_PUBLIC, 1)));

        // 写入混合多版本数据：E-V1 6 片 + E-V2（未发布崩溃残留）20 片 + F-V1 6 片
        java.util.List<Map<String, Object>> bulk = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bulk.add(chunkVer(docE, 1, i, textE));
        }
        for (int i = 0; i < 20; i++) {
            bulk.add(chunkVer(docE, 2, i, textE)); // V2 未发布：不得召回、不得挤占窗口
        }
        for (int i = 0; i < 6; i++) {
            bulk.add(chunkVer(docF, 1, i, textF));
        }
        assertTrue(es.bulkIndex(v, bulk) == bulk.size(), bulk.size() + " 片混合写入");

        try {
            // 对照证据（仅日志）：无预过滤时 k=18 窗口内 V2 切片占据位数
            Map<String, Object> rawFilter = new LinkedHashMap<>();
            rawFilter.put("reviewStatus", 1);
            rawFilter.put("accessLevel", java.util.List.of("PUBLIC", "PROTECTED"));
            java.util.List<Map<String, Object>> raw = es.knnSearch(embedding.embed(textE), 18, rawFilter);
            long staleInWindow = raw == null ? -1 : raw.stream()
                    .filter(h -> String.valueOf(h.get("docId")).equals(String.valueOf(docE))
                            && Integer.valueOf(2).equals(((Number) h.get("indexVersion")).intValue()))
                    .count();
            System.out.println("[M12-IT] 无版本预过滤时 k=18 窗口中未发布 V2 切片数 = " + staleInWindow
                    + " / " + (raw == null ? 0 : raw.size()) + "（挤占风险证据）");

            // ① F 始终可检索（E 的 20 片无效版本不得挤出 F）
            KnowledgeRetriever.RetrievalResult rF =
                    retriever.retrieve(textF, 3L, java.util.List.of("STUDENT"), null);
            org.junit.jupiter.api.Assertions.assertFalse(rF.hits.isEmpty(), "F 必须可检索");
            org.junit.jupiter.api.Assertions.assertTrue(rF.hits.stream().allMatch(h ->
                            String.valueOf(h.get("docId")).equals(String.valueOf(docF))),
                    "F 查询的全部命中必须来自 F（实际 " + rF.hits.size() + " 条）");

            // ② E 内容检索只返回 V1：未发布 V2 一条都不得出现
            KnowledgeRetriever.RetrievalResult rE =
                    retriever.retrieve(textE, 3L, java.util.List.of("STUDENT"), null);
            org.junit.jupiter.api.Assertions.assertFalse(rE.hits.isEmpty(), "E 的有效版本必须可检索");
            org.junit.jupiter.api.Assertions.assertTrue(rE.hits.stream().allMatch(h ->
                            Integer.valueOf(1).equals(((Number) h.get("indexVersion")).intValue())),
                    "E 的全部命中必须是已发布 V1（实际 " + rE.hits.size() + " 条）");
        } finally {
            es.deleteByDocId(docE);
            es.deleteByDocId(docF);
            org.mockito.Mockito.reset(documentMapper);
            // 恢复基础文档桩（其余用例依赖）
            when(documentMapper.selectById(DOC_PUBLIC)).thenReturn(pubDoc(DOC_PUBLIC, null,
                    KnowledgeDocument.ACCESS_PUBLIC));
            when(documentMapper.selectById(DOC_PROTECTED)).thenReturn(pubDoc(DOC_PROTECTED, COURSE_A,
                    KnowledgeDocument.ACCESS_PROTECTED));
            when(documentMapper.selectById(910001L)).thenReturn(pubDoc(910001L, null,
                    KnowledgeDocument.ACCESS_PUBLIC));
            when(documentMapper.selectById(910002L)).thenReturn(pubDoc(910002L, null,
                    KnowledgeDocument.ACCESS_PUBLIC));
        }
    }

    /** 检索结果为空断言 */
    static class KnowledgeRetrievalResultAssert {
        static void empty(KnowledgeRetriever.RetrievalResult r) {
            org.junit.jupiter.api.Assertions.assertTrue(r.hits.isEmpty(), "fail-closed 下应为空");
        }
    }
}
