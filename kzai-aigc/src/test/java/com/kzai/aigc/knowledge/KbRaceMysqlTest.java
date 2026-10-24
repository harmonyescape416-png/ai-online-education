package com.kzai.aigc.knowledge;

import com.kzai.aigc.knowledge.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 上的发布条件更新和进行中任务唯一约束。
 * 使用 KnowledgeDocumentMapper.PUBLISH_IF_CURRENT，与 Worker 的发布语句相同。
 * 不连接 Elasticsearch，也不调用 Embedding。
 */
class KbRaceMysqlTest {

    private static final String URL = "jdbc:mysql://127.0.0.1:3306/kzai_kb_race?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    @BeforeAll
    static void createDatabase() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS kzai_kb_race");
            st.execute("CREATE DATABASE kzai_kb_race CHARACTER SET utf8mb4");
        }
        String ddl;
        try (var in = KbRaceMysqlTest.class.getResourceAsStream("/kb-race-schema.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = DriverManager.getConnection(URL, "root", "");
             Statement st = c.createStatement()) {
            for (String part : ddl.split(";")) {
                if (!part.isBlank()) {
                    st.execute(part);
                }
            }
        }
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS kzai_kb_race");
        }
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "root", "");
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM ai_knowledge_index_job");
            st.execute("DELETE FROM ai_knowledge_document");
        }
    }

    @Test
    void withdrawBeforePublishDoesNotRestoreReview() throws Exception {
        insertDoc(41L, 1, 1, 5, "PROTECTED");
        try (Connection c = DriverManager.getConnection(URL, "root", "");
             Statement st = c.createStatement()) {
            st.execute("UPDATE ai_knowledge_document SET review_status=2, publish_generation=2 WHERE id=41");
        }
        assertEquals(0, publish(41L, 2, 1));
        try (Connection c = DriverManager.getConnection(URL, "root", "");
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT review_status, publish_generation, index_version, index_status FROM ai_knowledge_document WHERE id=41")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("review_status"));
            assertEquals(2, rs.getInt("publish_generation"));
            assertEquals(1, rs.getInt("index_version"));
            assertEquals(5, rs.getInt("index_status"));
        }
    }

    @Test
    void oldGenerationCannotPublishAfterNewReview() throws Exception {
        insertDoc(42L, 1, 3, 5, "PUBLIC");
        assertEquals(0, publish(42L, 9, 1));
        assertEquals(1, publish(42L, 4, 3));
        try (Connection c = DriverManager.getConnection(URL, "root", "");
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT review_status, index_version, access_level FROM ai_knowledge_document WHERE id=42")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("review_status"));
            assertEquals(4, rs.getInt("index_version"));
            assertEquals("PUBLIC", rs.getString("access_level"));
        }
    }

    @Test
    void concurrentActiveJobsCollapseToOne() throws Exception {
        insertDoc(43L, 1, 2, 5, "PROTECTED");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> insertJobAfter(ready, start, 4301L));
            Future<?> b = pool.submit(() -> insertJobAfter(ready, start, 4302L));
            ready.await();
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        try (Connection c = DriverManager.getConnection(URL, "root", "");
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM ai_knowledge_index_job WHERE doc_id=43 AND status IN (0,1)")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    private static String jdbcPublishSql() {
        String sql = KnowledgeDocumentMapper.PUBLISH_IF_CURRENT;
        for (String token : new String[]{"#{chars}", "#{chunks}", "#{indexVersion}", "#{now}", "#{id}", "#{generation}"}) {
            if (!sql.contains(token)) {
                throw new IllegalStateException("发布语句缺少 " + token);
            }
            sql = sql.replace(token, "?");
        }
        if (sql.contains("#{") || !sql.contains("review_status = 1") || !sql.contains("publish_generation = ?")) {
            throw new IllegalStateException(sql);
        }
        return sql;
    }

    private static int publish(long id, int version, int generation) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "root", "");
             PreparedStatement ps = c.prepareStatement(jdbcPublishSql())) {
            ps.setInt(1, 12);
            ps.setInt(2, 2);
            ps.setInt(3, version);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            ps.setLong(5, id);
            ps.setInt(6, generation);
            return ps.executeUpdate();
        }
    }

    private static void insertJobAfter(CountDownLatch ready, CountDownLatch start, long jobId) {
        ready.countDown();
        try {
            start.await();
            try (Connection c = DriverManager.getConnection(URL, "root", "");
                 PreparedStatement ps = c.prepareStatement("""
                         INSERT INTO ai_knowledge_index_job
                         (id, doc_id, action, status, attempt, publish_generation, create_time, update_time)
                         VALUES (?, 43, 'REINDEX', 0, 0, 2, NOW(), NOW())
                         """)) {
                ps.setLong(1, jobId);
                ps.executeUpdate();
            } catch (java.sql.SQLIntegrityConstraintViolationException ignored) {
                // 唯一进行中任务：后到的插入必须失败
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void insertDoc(long id, int review, int generation, int indexStatus, String access) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "root", "");
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO ai_knowledge_document
                     (id, title, doc_type, course_id, file_name, file_path, file_size, sha256,
                      index_version, index_status, access_level, review_status, publish_generation,
                      source_resource_id, source_version, create_time, update_time, deleted)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW(),0)
                     """)) {
            ps.setLong(1, id);
            ps.setString(2, "t");
            ps.setString(3, "MARKDOWN");
            ps.setLong(4, 7L);
            ps.setString(5, "a.md");
            ps.setString(6, "/tmp/a.md");
            ps.setLong(7, 4L);
            ps.setString(8, String.format("%064d", id));
            ps.setInt(9, 1);
            ps.setInt(10, indexStatus);
            ps.setString(11, access);
            ps.setInt(12, review);
            ps.setInt(13, generation);
            ps.setLong(14, id);
            ps.setString(15, String.valueOf(id));
            ps.executeUpdate();
        }
    }
}
