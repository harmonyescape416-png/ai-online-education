package com.kzai.learning.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 真实 MySQL（可重复读）下的日事实并发。使用独立库 kzai_fact_race，测完删除。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/kzai_fact_race?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:fact-race-schema.sql",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false"
})
class FactConcurrencyMysqlTest {

    @BeforeAll
    static void createDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS kzai_fact_race");
            st.execute("CREATE DATABASE kzai_fact_race CHARACTER SET utf8mb4");
        }
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS kzai_fact_race");
        }
    }

    @Autowired
    private LearningFactRecorder recorder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;

    @Test
    void existingRow_concurrentAdds_keepEveryDelta() throws Exception {
        recorder.record(1, 9, 3, 30, false, false, LocalDateTime.of(2026, 10, 1, 12, 0));
        runTogether(
                () -> recorder.record(1, 9, 3, 10, false, false, LocalDateTime.of(2026, 10, 1, 13, 0)),
                () -> recorder.record(1, 9, 3, 20, false, false, LocalDateTime.of(2026, 10, 1, 14, 0)));
        assertEquals(60L, seconds());
        assertEquals(1, rows());
    }

    @Test
    void missingRow_concurrentAdds_mergeIntoOneRow() throws Exception {
        jdbc.update("DELETE FROM learning_activity_fact");
        runTogether(
                () -> recorder.record(2, 9, 3, 10, false, false, LocalDateTime.of(2026, 10, 3, 12, 0)),
                () -> recorder.record(2, 9, 3, 20, false, false, LocalDateTime.of(2026, 10, 3, 12, 5)));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning_activity_fact WHERE user_id=2", Integer.class));
        assertEquals(30L, jdbc.queryForObject(
                "SELECT study_seconds FROM learning_activity_fact WHERE user_id=2", Long.class));
    }

    @Test
    void laterUpdateDoesNotClearFinishFlagOrMoveTimeBackward() {
        jdbc.update("DELETE FROM learning_activity_fact");
        jdbc.update("DELETE FROM learning_first_event");
        LocalDateTime later = LocalDateTime.of(2026, 10, 4, 18, 0);
        LocalDateTime earlier = LocalDateTime.of(2026, 10, 4, 8, 0);
        recorder.record(3, 9, 4, 5, true, true, later);
        recorder.record(3, 9, 4, 7, false, false, earlier);
        assertEquals(12L, jdbc.queryForObject(
                "SELECT study_seconds FROM learning_activity_fact WHERE user_id=3", Long.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT chapter_first_finished FROM learning_activity_fact WHERE user_id=3", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT course_first_finished FROM learning_activity_fact WHERE user_id=3", Integer.class));
        assertEquals(later, jdbc.queryForObject(
                "SELECT activity_time FROM learning_activity_fact WHERE user_id=3", LocalDateTime.class));
    }

    @Test
    void concurrentFirstFinishClaimsOnceAndPlainAddsDoNotClearFlag() throws Exception {
        jdbc.update("DELETE FROM learning_activity_fact");
        jdbc.update("DELETE FROM learning_first_event");
        LocalDateTime later = LocalDateTime.of(2026, 10, 6, 18, 0);
        recorder.record(6, 9, 8, 30, true, true, later);
        runTogether(
                () -> recorder.record(6, 9, 8, 10, true, true, LocalDateTime.of(2026, 10, 6, 9, 0)),
                () -> recorder.record(6, 9, 8, 20, false, false, LocalDateTime.of(2026, 10, 6, 10, 0)));
        assertEquals(60L, jdbc.queryForObject(
                "SELECT study_seconds FROM learning_activity_fact WHERE user_id=6", Long.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT chapter_first_finished FROM learning_activity_fact WHERE user_id=6", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT course_first_finished FROM learning_activity_fact WHERE user_id=6", Integer.class));
        assertEquals(later, jdbc.queryForObject(
                "SELECT activity_time FROM learning_activity_fact WHERE user_id=6", LocalDateTime.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning_first_event WHERE user_id=6", Integer.class));
    }

    @Test
    void failureRollsBackFactWithCallerTransaction() {
        jdbc.update("DELETE FROM learning_activity_fact");
        jdbc.update("DELETE FROM learning_first_event");
        jdbc.update("DELETE FROM learning_lesson_progress");
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO learning_lesson_progress
                    (id, user_id, course_id, chapter_id, study_seconds, video_position_sec, status, create_time, update_time)
                    VALUES (40, 4, 9, 5, 15, 15, 1, '2026-10-05 12:00:00', '2026-10-05 12:00:00')
                    """);
            recorder.record(4, 9, 5, 15, true, true, LocalDateTime.of(2026, 10, 5, 12, 0));
            throw new IllegalStateException("boom");
        }));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning_activity_fact WHERE user_id=4", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning_first_event WHERE user_id=4", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning_lesson_progress WHERE user_id=4", Integer.class));
    }

    private long seconds() {
        return jdbc.queryForObject(
                "SELECT study_seconds FROM learning_activity_fact WHERE user_id=1 AND chapter_id=3", Long.class);
    }

    private int rows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning_activity_fact WHERE user_id=1 AND chapter_id=3", Integer.class);
    }

    private static void runTogether(Runnable left, Runnable right) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(waitAndRun(ready, start, left));
            Future<?> b = pool.submit(waitAndRun(ready, start, right));
            ready.await();
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Runnable waitAndRun(CountDownLatch ready, CountDownLatch start, Runnable body) {
        return () -> {
            ready.countDown();
            try {
                start.await();
                body.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
    }
}
