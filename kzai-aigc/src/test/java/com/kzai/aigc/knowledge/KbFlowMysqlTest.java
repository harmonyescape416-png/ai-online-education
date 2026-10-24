package com.kzai.aigc.knowledge;

import com.kzai.aigc.controller.InternalKnowledgeController;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import com.kzai.aigc.knowledge.entity.KnowledgeIndexJob;
import com.kzai.aigc.knowledge.index.EsKnowledgeGateway;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.BizException;
import com.kzai.common.security.InternalAuthUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 MySQL 上的重试入队和发布竞态。
 * Elasticsearch 是测试替身，向量是固定长度的协议向量，不代表语义检索。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/kzai_kb_flow?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:kb-race-schema.sql",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.task.scheduling.enabled=false",
        "kzai.aigc.embedding-provider=mock",
        "kzai.aigc.real-enabled=false"
})
class KbFlowMysqlTest {

    private static final String SECRET = "kzai-dev-internal-secret-only-for-local-run";

    @BeforeAll
    static void createDatabase() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS kzai_kb_flow");
            st.execute("CREATE DATABASE kzai_kb_flow CHARACTER SET utf8mb4");
        }
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS kzai_kb_flow");
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private InternalKnowledgeController controller;
    @Autowired
    private KnowledgeAdminService admin;
    @Autowired
    private KnowledgeIndexJobRunner runner;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private EsKnowledgeGateway es;

    private Path source;
    private final AtomicInteger written = new AtomicInteger();
    private CountDownLatch reached;
    private CountDownLatch release;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM ai_knowledge_index_job");
        jdbc.update("DELETE FROM ai_knowledge_document");
        source = Files.createTempFile("kb-flow", ".md");
        Files.writeString(source, "# 知识\n\n并发发布的正文");
        written.set(0);
        reached = null;
        release = null;
        when(es.currentAliasedIndex()).thenReturn("kzai-knowledge-v1");
        when(es.deleteByDocIdVersionNeq(anyLong(), anyInt())).thenReturn(0L);
        when(es.deleteByDocId(anyLong())).thenReturn(0L);
        when(es.bulkIndex(anyInt(), any())).thenAnswer(invocation -> {
            List<?> docs = invocation.getArgument(1);
            written.set(docs.size());
            if (reached != null) {
                reached.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return docs.size();
        });
        when(es.countByDocIdVersion(anyLong(), anyInt())).thenAnswer(invocation -> (long) written.get());
        when(es.countByDocId(anyLong())).thenAnswer(invocation -> (long) written.get());
    }

    @Test
    void signedRetryCreatesOneJobAndWorkerPublishesWithoutChangingAccess() throws Exception {
        insertDoc(88L, 1, 5, 2, "PROTECTED", 0);
        mockMvc.perform(post("/internal/ai/knowledge/retry-resource")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"88\"}"))
                .andExpect(status().isForbidden());

        String body = mockMvc.perform(post("/internal/ai/knowledge/retry-resource")
                        .headers(signed("retry-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"88\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phase").value("INDEXING"))
                .andExpect(jsonPath("$.data.accessLevel").value("PROTECTED"))
                .andExpect(jsonPath("$.data.jobId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("INDEXING"));

        Map<String, Object> again = controller.retry(Map.of("resourceId", "88")).getData();
        assertEquals(true, again.get("alreadyQueued"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_knowledge_index_job WHERE doc_id=88 AND status IN (0,1)", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_knowledge_document WHERE source_resource_id=88", Integer.class));

        runner.poll();
        assertEquals(KnowledgeDocument.INDEX_SUCCESS, jdbc.queryForObject(
                "SELECT index_status FROM ai_knowledge_document WHERE id=88", Integer.class));
        assertEquals("PROTECTED", jdbc.queryForObject(
                "SELECT access_level FROM ai_knowledge_document WHERE id=88", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT review_status FROM ai_knowledge_document WHERE id=88", Integer.class));
        assertEquals(KnowledgeIndexJob.STATUS_SUCCESS, jdbc.queryForObject(
                "SELECT status FROM ai_knowledge_index_job WHERE doc_id=88", Integer.class));
    }

    @Test
    void workerFailureKeepsReasonAndAccess() {
        insertDoc(93L, 1, 5, 2, "PROTECTED", 1);
        org.mockito.Mockito.doReturn(-1).when(es).bulkIndex(anyInt(), any());
        controller.retry(Map.of("resourceId", "93"));
        Long jobId = jdbc.queryForObject(
                "SELECT id FROM ai_knowledge_index_job WHERE doc_id=93 AND status IN (0,1)", Long.class);
        jdbc.update("UPDATE ai_knowledge_index_job SET attempt=4, status=1 WHERE id=?", jobId);
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setId(jobId);
        job.setDocId(93L);
        job.setAction(KnowledgeIndexJob.ACTION_REINDEX);
        job.setStatus(KnowledgeIndexJob.STATUS_RUNNING);
        job.setAttempt(4);
        job.setPublishGeneration(2);
        runner.executeClaimed(job);
        assertEquals(KnowledgeDocument.INDEX_FAILED, jdbc.queryForObject(
                "SELECT index_status FROM ai_knowledge_document WHERE id=93", Integer.class));
        String reason = jdbc.queryForObject(
                "SELECT fail_reason FROM ai_knowledge_document WHERE id=93", String.class);
        assertTrue(reason != null && reason.contains("ES 批量写入失败"));
        assertEquals("PROTECTED", jdbc.queryForObject(
                "SELECT access_level FROM ai_knowledge_document WHERE id=93", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT review_status FROM ai_knowledge_document WHERE id=93", Integer.class));
    }

    @Test
    void pendingWithdrawnAndFailedAgainStayRejected() throws Exception {
        insertDoc(89L, 0, 5, 1, "PUBLIC", 0);
        assertThrows(BizException.class, () -> controller.retry(Map.of("resourceId", "89")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_knowledge_index_job WHERE doc_id=89", Integer.class));

        jdbc.update("UPDATE ai_knowledge_document SET review_status=2 WHERE id=89");
        assertThrows(BizException.class, () -> controller.retry(Map.of("resourceId", "89")));

        jdbc.update("UPDATE ai_knowledge_document SET review_status=1, index_status=5, deleted=1 WHERE id=89");
        assertThrows(BizException.class, () -> controller.retry(Map.of("resourceId", "89")));
    }

    @Test
    void withdrawAfterEsWriteDoesNotPublish() throws Exception {
        insertDoc(90L, 1, 5, 4, "PUBLIC", 1);
        KnowledgeIndexJob job = runningJob(9001L, 90L, 4);
        holdOnBulk();
        Thread worker = new Thread(() -> runner.executeClaimed(job));
        worker.start();
        assertTrue(reached.await(5, TimeUnit.SECONDS));
        assertEquals(1, admin.withdrawByResource(90L));
        release.countDown();
        worker.join(5000);
        assertEquals(2, jdbc.queryForObject(
                "SELECT review_status FROM ai_knowledge_document WHERE id=90", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT index_version FROM ai_knowledge_document WHERE id=90", Integer.class));
        assertEquals(KnowledgeIndexJob.STATUS_FAILED, jdbc.queryForObject(
                "SELECT status FROM ai_knowledge_index_job WHERE id=9001", Integer.class));
        String error = jdbc.queryForObject("SELECT error FROM ai_knowledge_index_job WHERE id=9001", String.class);
        assertTrue(error.contains("发布资格已失效") || error.contains("来源已失效"));
    }

    @Test
    void replaceDuringChunkBuildAndDeleteAfterWriteDoNotPublish() throws Exception {
        insertDoc(91L, 1, 5, 3, "PROTECTED", 1);
        KnowledgeIndexJob building = runningJob(9101L, 91L, 3);
        holdOnBulk();
        Thread worker = new Thread(() -> runner.executeClaimed(building));
        worker.start();
        assertTrue(reached.await(5, TimeUnit.SECONDS));
        assertEquals(1, admin.withdrawByResource(91L));
        release.countDown();
        worker.join(5000);
        assertEquals(1, jdbc.queryForObject(
                "SELECT index_version FROM ai_knowledge_document WHERE id=91", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT review_status FROM ai_knowledge_document WHERE id=91", Integer.class));

        jdbc.update("""
                UPDATE ai_knowledge_document
                SET review_status=1, publish_generation=3, index_version=1, index_status=5, deleted=0
                WHERE id=91
                """);
        jdbc.update("UPDATE ai_knowledge_index_job SET status=3 WHERE id=9101");
        KnowledgeIndexJob afterWrite = runningJob(9102L, 91L, 3);
        reached = new CountDownLatch(1);
        release = new CountDownLatch(1);
        Thread second = new Thread(() -> runner.executeClaimed(afterWrite));
        second.start();
        assertTrue(reached.await(5, TimeUnit.SECONDS));
        admin.delete(91L, 1L);
        release.countDown();
        second.join(5000);
        assertEquals(1, jdbc.queryForObject(
                "SELECT deleted FROM ai_knowledge_document WHERE id=91", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT index_version FROM ai_knowledge_document WHERE id=91", Integer.class));
        assertEquals(KnowledgeIndexJob.STATUS_FAILED, jdbc.queryForObject(
                "SELECT status FROM ai_knowledge_index_job WHERE id=9102", Integer.class));
    }

    @Test
    void resumedOldJobCannotPublishAfterNewReview() throws Exception {
        insertDoc(92L, 1, 5, 2, "PUBLIC", 1);
        KnowledgeIndexJob old = runningJob(9201L, 92L, 2);
        admin.withdrawByResource(92L);
        admin.review(92L, true, 7L);
        old.setStatus(KnowledgeIndexJob.STATUS_RUNNING);
        runner.executeClaimed(old);
        KnowledgeDocument doc = admin.detail(92L);
        assertEquals(1, doc.getReviewStatus());
        assertTrue(doc.getPublishGeneration() > 2);
        assertEquals(1, doc.getIndexVersion());
        assertEquals(KnowledgeIndexJob.STATUS_FAILED, jdbc.queryForObject(
                "SELECT status FROM ai_knowledge_index_job WHERE id=9201", Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_knowledge_index_job
                WHERE doc_id=92 AND publish_generation=? AND status=0
                """, Integer.class, doc.getPublishGeneration()));
    }

    private void holdOnBulk() {
        reached = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    private KnowledgeIndexJob runningJob(long jobId, long docId, int generation) {
        jdbc.update("""
                INSERT INTO ai_knowledge_index_job
                (id, doc_id, action, status, attempt, publish_generation, create_time, update_time)
                VALUES (?, ?, 'REINDEX', 1, 0, ?, NOW(), NOW())
                """, jobId, docId, generation);
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setId(jobId);
        job.setDocId(docId);
        job.setAction(KnowledgeIndexJob.ACTION_REINDEX);
        job.setStatus(KnowledgeIndexJob.STATUS_RUNNING);
        job.setAttempt(0);
        job.setPublishGeneration(generation);
        return job;
    }

    private void insertDoc(long id, int review, int indexStatus, int generation, String access, int indexVersion) {
        jdbc.update("""
                INSERT INTO ai_knowledge_document
                (id, title, doc_type, course_id, file_name, file_path, file_size, sha256,
                 index_version, index_status, access_level, review_status, publish_generation,
                 source_resource_id, source_version, publisher, create_time, update_time, deleted)
                VALUES (?, 't', 'MARKDOWN', 7, 'a.md', ?, 4, ?, ?, ?, ?, ?, ?, ?, ?, 8, NOW(), NOW(), 0)
                """, id, source.toString(), String.format("%064d", id), indexVersion, indexStatus, access,
                review, generation, id, String.valueOf(id));
    }

    private static org.springframework.http.HttpHeaders signed(String requestId) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add(Constants.HEADER_REQUEST_ID, requestId);
        headers.add(Constants.HEADER_INTERNAL_SIGN, InternalAuthUtils.sign(SECRET,
                InternalAuthUtils.payload(requestId, "", "", "", "")));
        return headers;
    }
}
