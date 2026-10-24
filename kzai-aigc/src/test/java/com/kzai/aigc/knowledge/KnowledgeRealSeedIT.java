package com.kzai.aigc.knowledge;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.chunk.TextChunker;
import com.kzai.aigc.knowledge.embedding.DashScopeKnowledgeEmbeddingService;
import com.kzai.aigc.knowledge.index.EsKnowledgeGateway;
import com.kzai.aigc.knowledge.parser.DocumentParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M15 REAL：独立 REAL 测试索引种子（手动启用，默认跳过——CI 不触发、零费用）。
 * <p>
 * 授权隔离方案（不修改任何生产数据）：
 * <ul>
 *   <li><b>不走 KnowledgeIndexJobRunner</b>——其 doReindex 会更新
 *       ai_knowledge_document.index_version/index_status 并删除旧版本切片（MySQL/ES 副作用）；</li>
 *   <li>本测试<b>只读</b> MySQL 已发布文档元数据与原文文件，本地切片后直接写入
 *       独立索引 kzai-knowledge-real-test-v1（独立前缀/别名，1024 维）；</li>
 *   <li>切片 indexVersion 沿用 MySQL 权威值——检索侧版本白名单 (docId,indexVersion) 可一致匹配；</li>
 *   <li>chunkId=docId_v{indexVersion}_{i} 为 bulk _id，重复执行幂等覆盖。</li>
 * </ul>
 * 执行方式：
 * {@code mvn -pl kzai-aigc test -Dtest=KnowledgeRealSeedIT -Dkzai.real.seed=true -DforkCount=0}
 * 需要环境：DASHSCOPE_API_KEY、本地 MySQL（course_stack_ai）与 ES 8（127.0.0.1:9200）。
 */
@EnabledIfSystemProperty(named = "kzai.real.seed", matches = "true")
class KnowledgeRealSeedIT {

    /** 按标题选择已发布文档（授权清单：3 篇 PUBLIC 语义文档 + 1 篇 PROTECTED + 1 篇弱内容占位） */
    private static final List<String> SEED_TITLES = List.of(
            "M12评测·平台退款政策",
            "M12评测·发票与证书说明",
            "M12评测·课程有效期与学习规则",
            "M12评测·内部风控与运营手册",
            "m9web-1792487705");

    private static final int REAL_TEST_VERSION = 1;

    @Test
    void seedRealTestIndex() throws Exception {
        String dbUrl = env("KZAI_DB_URL", "jdbc:mysql://127.0.0.1:3306/course_stack_ai"
                + "?useUnicode=true&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String dbUser = env("KZAI_DB_USER", "kzai");
        String dbPass = env("KZAI_DB_PASS", "Kzai@2026");
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertTrue(apiKey != null && !apiKey.isBlank(), "DASHSCOPE_API_KEY 未设置");

        // ---- 只读源数据 ----
        record SourceDoc(long id, String title, String docType, Long courseId,
                         String filePath, String accessLevel, int indexVersion) {}
        List<SourceDoc> docs = new ArrayList<>();
        try (Connection cn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT id,title,doc_type,course_id,file_path,access_level,index_version "
                             + "FROM ai_knowledge_document WHERE title=? AND review_status=1 AND deleted=0")) {
            for (String title : SEED_TITLES) {
                ps.setString(1, title);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        docs.add(new SourceDoc(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getObject(4) == null ? null : rs.getLong(4),
                                rs.getString(5), rs.getString(6), rs.getInt(7)));
                    }
                }
            }
        }
        assertTrue(docs.size() >= 4, "已发布种子文档不足: " + docs.size());
        System.out.println("[seed] 只读取到 " + docs.size() + " 篇已发布文档");

        // ---- 真实 Embedding（v4，1024 维以真实返回为准）----
        DashScopeKnowledgeEmbeddingService embedding = new DashScopeKnowledgeEmbeddingService(
                apiKey, env("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com"),
                env("KZAI_EMBEDDING_MODEL", "text-embedding-v4"));
        int dims = embedding.dimensions();
        System.out.println("[seed] 实测维度=" + dims);
        assertEquals(1024, dims, "v4 默认维度应为 1024（真实返回）");

        // ---- 切片 + 向量化（合计 ≤10 条时单次批量请求）----
        List<Map<String, Object>> chunkDocs = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (SourceDoc d : docs) {
            String text = new DocumentParser().parse(d.docType(), Files.readAllBytes(Path.of(d.filePath())));
            List<String> chunks = new TextChunker().chunk(text);
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("chunkId", d.id() + "_v" + d.indexVersion() + "_" + i);
                doc.put("docId", d.id());
                doc.put("chunkIndex", i);
                doc.put("indexVersion", d.indexVersion()); // MySQL 权威版本 → 白名单匹配
                doc.put("docTitle", d.title());
                doc.put("courseId", d.courseId());
                doc.put("accessLevel", d.accessLevel());
                doc.put("reviewStatus", 1);
                doc.put("text", chunks.get(i));
                texts.add(chunks.get(i));
                chunkDocs.add(doc);
            }
        }
        assertTrue(chunkDocs.size() <= 10, "本轮授权单次批量上限 10 条: " + chunkDocs.size());
        List<float[]> vectors = embedding.embedAll(texts); // 1 次 API 请求（≤10 行）
        System.out.println("[seed] 批量向量化 1 次 API 请求，切片数=" + vectors.size());
        for (int i = 0; i < chunkDocs.size(); i++) {
            float[] v = vectors.get(i);
            assertEquals(dims, v.length, "向量维度不一致");
            chunkDocs.get(i).put("vector", v);
        }

        // ---- 独立 REAL 测试索引（独立前缀/别名，不触碰 MOCK 索引）----
        AiProperties props = new AiProperties();
        props.setKnowledgeEsUrl(env("KZAI_ES_URL", "http://127.0.0.1:9200"));
        props.setKnowledgeIndexPrefix(env("KZAI_REAL_INDEX_PREFIX", "kzai-knowledge-real-test"));
        props.setKnowledgeIndexAlias(env("KZAI_REAL_INDEX_ALIAS", "kzai-knowledge-real-test-alias"));
        EsKnowledgeGateway es = new EsKnowledgeGateway(props, new ObjectMapper());
        assertTrue(es.available(), "ES 不可用: " + props.getKnowledgeEsUrl());
        assertTrue(es.createVersionedIndex(REAL_TEST_VERSION, dims), "创建 real-test-v1 失败");
        assertTrue(es.swapAlias(REAL_TEST_VERSION, null), "挂载 real-test 别名失败");
        System.out.println("[seed] REAL 测试索引与别名就绪: " + props.getKnowledgeIndexPrefix() + "-v1");

        int written = es.bulkIndex(REAL_TEST_VERSION, chunkDocs);
        assertEquals(chunkDocs.size(), written, "bulk 写入数量不一致");

        // ---- 数量核验（经别名，等价于检索视角）----
        for (var d : docs) {
            long count = es.countByDocIdVersion(d.id(), d.indexVersion());
            System.out.println("[seed] doc=" + d.title() + " id=" + d.id()
                    + " access=" + d.accessLevel() + " indexVersion=" + d.indexVersion()
                    + " chunks=" + count);
            assertTrue(count > 0, "切片核验失败: " + d.title());
        }
        System.out.println("[seed] 完成：REAL 测试索引写入与核验全部通过");
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
