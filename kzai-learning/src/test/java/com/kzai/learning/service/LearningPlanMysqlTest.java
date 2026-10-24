package com.kzai.learning.service;

import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.client.TradeClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.EntitlementDecision;
import com.kzai.learning.dto.LearnChapterDTO;
import com.kzai.learning.vo.LearningPlanBoardVO;
import com.kzai.learning.vo.LearningPlanItemVO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 真实 MySQL 上的学习计划。使用隔离库 kzai_plan_race，不写 course_stack_ai。
 * 固定时钟为 2026-10-21 12:00（Asia/Shanghai），当周是 [10-19, 10-26)。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/kzai_plan_race?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:plan-race-schema.sql",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class LearningPlanMysqlTest {

    private static final long USER = 3L;
    private static final long OTHER = 9L;
    private static final long COURSE = 6001L;
    private static final Clock NOON = Clock.fixed(Instant.parse("2026-10-21T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @BeforeAll
    static void createDatabase() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS kzai_plan_race");
            st.execute("CREATE DATABASE kzai_plan_race CHARACTER SET utf8mb4");
        }
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS kzai_plan_race");
        }
    }

    @Autowired
    private LearningPlanService plans;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private CourseClient courseClient;
    @MockBean
    private TradeClient tradeClient;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM learning_plan_pause");
        jdbc.update("DELETE FROM learning_plan_goal");
        jdbc.update("DELETE FROM learning_plan");
        jdbc.update("DELETE FROM learning_first_event");
        jdbc.update("UPDATE learning_fact_coverage SET facts_since='2000-01-01 00:00:00' WHERE id=1");
        plans.useClock(NOON);
        UserContext.set(USER, "student", 2, List.of("STUDENT"), "plan");
        when(courseClient.learnOutline(COURSE)).thenReturn(R.ok(outline()));
        when(tradeClient.entitlementDetail(USER, COURSE)).thenReturn(R.ok(EntitlementDecision.grant("FREE", null)));
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
        plans.useClock(Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void repeatJoinReturnsSamePlanAndDatabaseRejectsSecondOpenRow() {
        LearningPlanItemVO first = plans.join(USER, COURSE, 2);
        LearningPlanItemVO second = plans.join(USER, COURSE, 9);
        assertEquals(first.getId(), second.getId());
        assertTrue(second.getAlreadyJoined());
        assertEquals(2, second.getWeeklyGoal());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning_plan WHERE user_id=? AND status=1", Integer.class, USER));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update("""
                INSERT INTO learning_plan (id, user_id, course_id, status, joined_at, create_time, update_time)
                VALUES (99, ?, ?, 1, '2026-10-21 12:00:00', '2026-10-21 12:00:00', '2026-10-21 12:00:00')
                """, USER, COURSE));
    }

    @Test
    void otherUserCannotPause() {
        LearningPlanItemVO mine = plans.join(USER, COURSE, 2);
        assertThrows(BizException.class, () -> plans.pause(OTHER, Long.parseLong(mine.getId())));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM learning_plan WHERE id=?", Integer.class, mine.getId()));
    }

    @Test
    void weekBoundaryAndRepeatFinishCountOnlyFirstCompletionAfterJoin() {
        plans.join(USER, COURSE, 2);
        finish(1, "2026-10-18", "2026-10-18 10:00:00");
        finish(2, "2026-10-19", "2026-10-19 10:00:00");
        finish(3, "2026-10-21", "2026-10-21 13:00:00");
        finish(4, "2026-10-26", "2026-10-26 00:00:00");
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> finish(3, "2026-10-21", "2026-10-21 18:00:00"));
        LearningPlanItemVO item = only(plans.board(USER));
        assertEquals(1, item.getFinishedThisWeek());
        assertEquals(1, item.getRemaining());
        assertEquals(50.0, item.getRatio());
        assertEquals("PARTIAL", item.getWeekStatus());
    }

    @Test
    void goalChangeAppliesNextWeekAndPauseSkipsFinishesUntilResume() {
        LearningPlanItemVO created = plans.join(USER, COURSE, 2);
        long id = Long.parseLong(created.getId());
        plans.useClock(Clock.fixed(Instant.parse("2026-10-21T07:00:00Z"), ZoneId.of("Asia/Shanghai")));
        LearningPlanItemVO changed = plans.changeGoal(USER, id, 5);
        assertEquals(2, changed.getWeeklyGoal());
        assertEquals(5, changed.getNextWeekGoal());
        assertEquals("2026-10-26", changed.getNextGoalWeek());
        assertEquals(2, jdbc.queryForObject(
                "SELECT weekly_goal FROM learning_plan_goal WHERE plan_id=? AND effective_week='2026-10-19'",
                Integer.class, id));

        finish(1, "2026-10-21", "2026-10-21 12:30:00");
        plans.useClock(Clock.fixed(Instant.parse("2026-10-21T06:00:00Z"), ZoneId.of("Asia/Shanghai")));
        plans.pause(USER, id);
        finish(2, "2026-10-21", "2026-10-21 15:00:00");
        plans.useClock(Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneId.of("Asia/Shanghai")));
        plans.resume(USER, id);
        finish(3, "2026-10-21", "2026-10-21 19:00:00");
        LearningPlanItemVO item = only(plans.board(USER));
        assertEquals("ACTIVE", item.getStatus());
        assertEquals(2, item.getFinishedThisWeek());
        assertEquals("REACHED", item.getWeekStatus());
    }

    @Test
    void lostEntitlementHidesContinuePath() {
        plans.join(USER, COURSE, 2);
        when(tradeClient.entitlementDetail(USER, COURSE)).thenReturn(R.ok(EntitlementDecision.deny("课程已退款或权益已到期")));
        LearningPlanItemVO item = only(plans.board(USER));
        assertFalse(item.getCanContinue());
        assertNull(item.getContinuePath());
        assertEquals("课程已退款或权益已到期", item.getAccessReason());
    }

    @Test
    void insufficientCoverageDoesNotShowZero() {
        plans.join(USER, COURSE, 2);
        jdbc.update("UPDATE learning_fact_coverage SET facts_since='2026-10-23 00:00:00' WHERE id=1");
        finish(1, "2026-10-21", "2026-10-21 13:00:00");
        LearningPlanBoardVO board = plans.board(USER);
        assertEquals("INSUFFICIENT", board.getCoverage());
        assertEquals("INSUFFICIENT", board.getSummary());
        assertNull(board.getPlans().get(0).getFinishedThisWeek());
        assertEquals("本周完成量暂不可用", board.getCoverageText());
    }

    @Test
    void finishingAllChaptersCompletesPlan() {
        plans.join(USER, COURSE, 3);
        finish(11, "2026-10-21", "2026-10-21 13:00:00");
        finish(12, "2026-10-21", "2026-10-21 14:00:00");
        finish(13, "2026-10-21", "2026-10-21 15:00:00");
        finish(14, "2026-10-21", "2026-10-21 16:00:00");
        finish(15, "2026-10-21", "2026-10-21 17:00:00");
        LearningPlanItemVO item = only(plans.board(USER));
        assertEquals("COMPLETED", item.getStatus());
        assertEquals("COURSE_DONE", item.getWeekStatus());
        assertNull(item.getContinuePath());
        assertEquals("NONE", plans.board(USER).getSummary());
    }

    @Test
    void quitThenJoinCreatesANewPlan() {
        LearningPlanItemVO first = plans.join(USER, COURSE, 2);
        plans.quit(USER, Long.parseLong(first.getId()));
        LearningPlanItemVO again = plans.join(USER, COURSE, 4);
        assertFalse(first.getId().equals(again.getId()));
        assertEquals(4, again.getWeeklyGoal());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM learning_plan WHERE user_id=?", Integer.class, USER));
    }

    @Test
    void deniedEntitlementCannotJoin() {
        when(tradeClient.entitlementDetail(USER, COURSE)).thenReturn(R.ok(EntitlementDecision.deny("未购买或已退款")));
        AuthException ex = assertThrows(AuthException.class, () -> plans.join(USER, COURSE, 2));
        assertEquals("未购买或已退款", ex.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM learning_plan", Integer.class));
    }

    private static LearningPlanItemVO only(LearningPlanBoardVO board) {
        assertEquals(1, board.getPlans().size());
        return board.getPlans().get(0);
    }

    private void finish(long chapterId, String bizDate, String createTime) {
        jdbc.update("""
                INSERT INTO learning_first_event
                (id, user_id, course_id, chapter_id, event_type, biz_date, create_time)
                VALUES (?, ?, ?, ?, 'CHAPTER_FINISH', ?, ?)
                """, chapterId, USER, COURSE, chapterId, bizDate, createTime);
    }

    private static CourseOutlineDTO outline() {
        CourseOutlineDTO outline = new CourseOutlineDTO();
        outline.setId(COURSE);
        outline.setName("计划课");
        outline.setFree(0);
        outline.setPrice(100);
        outline.setStudentVisible(true);
        outline.setChapters(List.of(chapter(11), chapter(12), chapter(13), chapter(14), chapter(15)));
        return outline;
    }

    private static LearnChapterDTO chapter(long id) {
        LearnChapterDTO chapter = new LearnChapterDTO();
        chapter.setId(id);
        chapter.setCourseId(COURSE);
        chapter.setName("第" + id + "节");
        return chapter;
    }
}
