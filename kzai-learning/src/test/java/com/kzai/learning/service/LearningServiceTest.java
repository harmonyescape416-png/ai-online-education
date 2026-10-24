package com.kzai.learning.service;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.client.TradeClient;
import com.kzai.learning.client.UserClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.EntitlementDecision;
import com.kzai.learning.dto.LearnChapterDTO;
import com.kzai.learning.dto.LessonQuery;
import com.kzai.learning.dto.ProgressReportRequest;
import com.kzai.learning.entity.InteractionQuestion;
import com.kzai.learning.entity.InteractionReply;
import com.kzai.learning.entity.InteractionReplyExtra;
import com.kzai.learning.entity.InteractionReplyHelpful;
import com.kzai.learning.entity.LearningLesson;
import com.kzai.learning.entity.LearningLessonProgress;
import com.kzai.learning.mapper.InteractionQuestionMapper;
import com.kzai.learning.mapper.InteractionReplyExtraMapper;
import com.kzai.learning.mapper.InteractionReplyHelpfulMapper;
import com.kzai.learning.mapper.InteractionReplyMapper;
import com.kzai.learning.stats.StatsWindow;
import com.kzai.learning.mapper.LearningLessonMapper;
import com.kzai.learning.mapper.LearningLessonProgressMapper;
import com.kzai.learning.vo.LessonDetailVO;
import com.kzai.learning.vo.LessonVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 学习服务集成测试（M5）：H2 真实执行 SQL 验证入课、进度、断点续学与越权。
 * <p>
 * 课程服务与交易服务按内部契约用桩提供"真实形态"的数据（课程快照 / 权益判定），
 * 学习业务本身（入课、进度、时长累计、权限）全部走真实 Service + Mapper。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:learning_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false"
})
@Transactional
class LearningServiceTest {

    private static final long USER_A = 3L;
    private static final long USER_B = 9L;
    private static final long FREE_COURSE = 6001L;
    private static final long PAID_COURSE = 6002L;

    @Autowired
    private LearningService learningService;
    @Autowired
    private LearningLessonMapper lessonMapper;
    @Autowired
    private LearningLessonProgressMapper progressMapper;
    @Autowired
    private LearningFactRecorder factRecorder;
    @Autowired
    private TeacherStatsService teacherStatsService;
    @Autowired
    private InteractionQuestionMapper questionMapper;
    @Autowired
    private InteractionReplyMapper replyMapper;
    @Autowired
    private InteractionReplyExtraMapper replyExtraMapper;
    @Autowired
    private InteractionReplyHelpfulMapper helpfulMapper;

    @MockBean
    private CourseClient courseClient;
    @MockBean
    private TradeClient tradeClient;
    @MockBean
    private UserClient userClient;

    @BeforeEach
    void asStudent() {
        UserContext.set(USER_A, "student01", Constants.USER_TYPE_STUDENT,
                List.of(Constants.ROLE_STUDENT), "test");
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    // ---------------------------------------------------------------- 免费课程

    @Test
    void freeCourse_enrollCreatesRealRecord() {
        stubFreeCourse(FREE_COURSE);

        LessonVO vo = learningService.enroll(USER_A, FREE_COURSE);

        assertNotNull(vo.getId());
        assertEquals(1L, countLessons());
        assertEquals(Constants.ENTITLEMENT_FREE, vo.getEntitlementSource());
        assertEquals(1, vo.getStatus(), "入课后为学习中");
        assertEquals(3, vo.getTotalChapterCount());
    }

    @Test
    void freeCourse_repeatedEnrollIsIdempotent() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);

        assertEquals(1L, countLessons(), "重复入课不得产生重复记录");
    }

    @Test
    void freeCourse_enrollDoesNotCreateOrder() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        // 免费入课不经过交易服务：不产生任何订单查询以外的调用
        org.mockito.Mockito.verifyNoInteractions(tradeClient);
    }

    // ---------------------------------------------------------------- 付费课程

    @Test
    void paidCourse_withoutEntitlement_denied() {
        stubPaidCourse(PAID_COURSE, false, "未查询到该课程的已支付订单，请先购买课程");

        AuthException ex = assertThrows(AuthException.class,
                () -> learningService.enroll(USER_A, PAID_COURSE));
        assertTrue(ex.getMessage().contains("请先购买"), ex.getMessage());
        assertEquals(0L, countLessons());
    }

    @Test
    void paidCourse_withEntitlement_allowed() {
        stubPaidCourse(PAID_COURSE, true, null);
        LessonVO vo = learningService.enroll(USER_A, PAID_COURSE);
        assertEquals(Constants.ENTITLEMENT_PAID, vo.getEntitlementSource());
        assertTrue(vo.getEntitlementValid());
    }

    @Test
    void tradeUnavailable_deniesEntitlement() {
        stubPaidCourse(PAID_COURSE, true, null);
        when(tradeClient.entitlementDetail(anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("trade down"));

        AuthException ex = assertThrows(AuthException.class,
                () -> learningService.enroll(USER_A, PAID_COURSE));
        assertTrue(ex.getMessage().contains("不可用"), ex.getMessage());
    }

    // ---------------------------------------------------------------- 进度

    @Test
    void progressReport_persistsPositionAndStudySeconds() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        Long chapterId = chapterId(FREE_COURSE, 1);

        report(FREE_COURSE, chapterId, 0, 1200, null);
        // 模拟真实观看：距上次上报已过去 300 秒，位置前进 300 秒 → 全部计入
        backdate(USER_A, chapterId, 300);
        LessonDetailVO after = report(FREE_COURSE, chapterId, 300, 1200, null);

        assertEquals(chapterId, after.getLastChapterId());
        assertEquals(300, after.getLastVideoPositionSec());
        assertEquals(300L, after.getStudySeconds(), "有效学习时长按真实位置前进量累计");
    }

    @Test
    void progressReport_repeatedReportDoesNotDoubleCount() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        Long chapterId = chapterId(FREE_COURSE, 1);

        report(FREE_COURSE, chapterId, 0, 1200, null);
        backdate(USER_A, chapterId, 300);
        report(FREE_COURSE, chapterId, 300, 1200, null);
        report(FREE_COURSE, chapterId, 300, 1200, null);
        LessonDetailVO after = report(FREE_COURSE, chapterId, 300, 1200, null);

        assertEquals(300L, after.getStudySeconds(), "重复上报同一位置不得重复累计时长");
    }

    /**
     * M6 验收项：拖动进度条造成的巨大位置跳跃不得计入有效学习时长。
     * 位置前进 900 秒，但两次上报之间只过了 5 秒 → 最多只认 5 秒 + 容差。
     */
    @Test
    void progressReport_dragDoesNotInflateStudySeconds() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        Long chapterId = chapterId(FREE_COURSE, 1);

        report(FREE_COURSE, chapterId, 0, 1200, null);
        backdate(USER_A, chapterId, 5);
        LessonDetailVO after = report(FREE_COURSE, chapterId, 900, 1200, null);

        assertEquals(900, after.getLastVideoPositionSec(), "断点位置本身随拖动前进（用户确实跳到该处）");
        assertTrue(after.getStudySeconds() <= 5 + 3,
                "拖动产生的 900 秒跳跃不得计入有效时长，实际=" + after.getStudySeconds());
        assertTrue(after.getStudySeconds() > 0, "真实观看的 5 秒仍应计入");
    }

    @Test
    void progressReport_doesNotRollBack() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        Long chapterId = chapterId(FREE_COURSE, 1);

        report(FREE_COURSE, chapterId, 500, 1200, null);
        LessonDetailVO after = report(FREE_COURSE, chapterId, 100, 1200, null);

        assertEquals(500, after.getLastVideoPositionSec(), "断点位置不得倒退");
    }

    @Test
    void progressReport_invalidChapterRejected() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);

        ProgressReportRequest request = new ProgressReportRequest();
        request.setChapterId(999999L);
        request.setVideoPositionSec(10);
        assertThrows(BizException.class,
                () -> learningService.reportProgress(USER_A, FREE_COURSE, request));
    }

    @Test
    void progressReport_capsForgedStudySeconds() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        Long chapterId = chapterId(FREE_COURSE, 1);

        report(FREE_COURSE, chapterId, 0, 100001, null);
        // 即便伪造"距上次上报已过去 10 小时"，单次上报的增量仍受上限约束
        backdate(USER_A, chapterId, 36000);
        LessonDetailVO after = report(FREE_COURSE, chapterId, 100000, 100001, null);

        assertEquals(900L, after.getStudySeconds(), "单次上报的时长增量受上限约束");
    }

    @Test
    void courseProgress_computedFromFinishedChapters() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);

        learningService.completeChapter(USER_A, FREE_COURSE, chapterId(FREE_COURSE, 1));
        LessonDetailVO after = learningService.completeChapter(USER_A, FREE_COURSE, chapterId(FREE_COURSE, 2));

        assertEquals(2, after.getFinishedChapterCount());
        assertEquals(66, after.getProgress(), "进度由服务端按已完成章节数计算");
        assertEquals(Constants.LEARN_STATUS_LEARNING, after.getStatus());
    }

    @Test
    void allChaptersFinished_marksCourseFinished() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        for (int i = 1; i <= 3; i++) {
            learningService.completeChapter(USER_A, FREE_COURSE, chapterId(FREE_COURSE, i));
        }
        LessonDetailVO detail = learningService.detail(USER_A, FREE_COURSE);
        assertEquals(Constants.LEARN_STATUS_FINISHED, detail.getStatus());
        assertEquals(100, detail.getProgress());
    }

    @Test
    void videoFinished_marksChapterFinished() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        Long chapterId = chapterId(FREE_COURSE, 1);

        LessonDetailVO after = report(FREE_COURSE, chapterId, 1200, 1200, null);

        LearningLessonProgress p = progressMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<LearningLessonProgress>lambdaQuery()
                        .eq(LearningLessonProgress::getUserId, USER_A)
                        .eq(LearningLessonProgress::getChapterId, chapterId));
        assertEquals(Constants.LEARN_STATUS_FINISHED, p.getStatus());
        assertNotNull(p.getFinishTime());
        assertTrue(after.getFinishedChapterCount() >= 1);
    }

    @Test
    void completedFlagAloneDoesNotFinishVideoChapter() {
        // 带视频的章节：前端 completed=true 不能单独生效，必须以播放位置为准
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        Long chapterId = chapterId(FREE_COURSE, 1);

        LessonDetailVO after = report(FREE_COURSE, chapterId, 10, 1200, true);

        assertEquals(0, after.getFinishedChapterCount(), "未播放到阈值不得标记完成");
    }

    // ---------------------------------------------------------------- 权限

    @Test
    void userCannotWriteOthersProgress() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        Long chapterId = chapterId(FREE_COURSE, 1);
        report(FREE_COURSE, chapterId, 300, 1200, null);

        // 学员 B 入课后上报自己的进度，不得影响学员 A 的记录
        UserContext.set(USER_B, "student02", Constants.USER_TYPE_STUDENT,
                List.of(Constants.ROLE_STUDENT), "test");
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_B, FREE_COURSE);
        report(USER_B, FREE_COURSE, chapterId, 999, 1200, null);

        LearningLessonProgress mine = progressMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<LearningLessonProgress>lambdaQuery()
                        .eq(LearningLessonProgress::getUserId, USER_A)
                        .eq(LearningLessonProgress::getChapterId, chapterId));
        assertEquals(300, mine.getVideoPositionSec(), "他人上报不得篡改本人进度");
    }

    @Test
    void lessonListOnlyReturnsOwnRecords() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        UserContext.set(USER_B, "student02", Constants.USER_TYPE_STUDENT,
                List.of(Constants.ROLE_STUDENT), "test");
        learningService.enroll(USER_B, FREE_COURSE);
        UserContext.set(USER_A, "student01", Constants.USER_TYPE_STUDENT,
                List.of(Constants.ROLE_STUDENT), "test");

        var page = learningService.page(USER_A, new LessonQuery());
        assertEquals(1, page.getTotal());
        assertTrue(page.getRecords().stream().allMatch(v -> v.getCourseId().equals(FREE_COURSE)));
    }

    @Test
    void refundedCourseIsHiddenWhileOtherInvalidCoursesStay() {
        stubFreeCourse(FREE_COURSE);
        learningService.enroll(USER_A, FREE_COURSE);
        enrollPaid(6002L);
        enrollPaid(6003L);
        enrollPaid(6004L);

        EntitlementDecision refunded = EntitlementDecision.deny("该课程订单处于「已退款」状态，学习权益已暂停");
        refunded.setRefundStatus(Constants.REFUND_SUCCESS);
        when(tradeClient.entitlementDetail(anyLong(), org.mockito.ArgumentMatchers.eq(6002L))).thenReturn(R.ok(refunded));
        when(tradeClient.entitlementDetail(anyLong(), org.mockito.ArgumentMatchers.eq(6003L)))
                .thenReturn(R.ok(EntitlementDecision.deny("该课程订单处于「退款申请中」状态，学习权益已暂停")));
        when(tradeClient.entitlementDetail(anyLong(), org.mockito.ArgumentMatchers.eq(6004L)))
                .thenReturn(R.ok(EntitlementDecision.deny("课程学习有效期已于 2026-01-01 到期，请重新购买后继续学习")));

        var page = learningService.page(USER_A, new LessonQuery());
        var ids = page.getRecords().stream().map(LessonVO::getCourseId).toList();
        assertEquals(3, page.getTotal());
        assertFalse(ids.contains(6002L));
        assertTrue(ids.contains(FREE_COURSE));
        assertTrue(ids.contains(6003L));
        assertTrue(ids.contains(6004L));
        assertTrue(page.getRecords().stream().filter(v -> v.getCourseId().equals(FREE_COURSE))
                .allMatch(v -> Boolean.TRUE.equals(v.getEntitlementValid())));
        assertTrue(page.getRecords().stream().filter(v -> !v.getCourseId().equals(FREE_COURSE))
                .allMatch(v -> Boolean.FALSE.equals(v.getEntitlementValid())));
    }

    @Test
    void previewChapterAccessibleWithoutEntitlement() {
        // 未购学员只能访问教师显式标记为可试看的章节
        CourseOutlineDTO outline = paidOutline(PAID_COURSE);
        outline.getChapters().get(0).setFreePreview(true);
        when(courseClient.learnOutline(PAID_COURSE)).thenReturn(R.ok(outline));
        when(tradeClient.entitlementDetail(anyLong(), anyLong()))
                .thenReturn(R.ok(EntitlementDecision.deny("未取得该课程的学习权益")));

        var chapter = learningService.chapterDetail(USER_A, PAID_COURSE, chapterId(PAID_COURSE, 1));
        assertTrue(chapter.getAccessible(), "显式标记的试看章节可访问");
        assertTrue(chapter.getFreePreview());
    }

    @Test
    void nonPreviewChapterBlockedWithoutEntitlement() {
        stubPaidCourse(PAID_COURSE, false, "未取得该课程的学习权益");

        AuthException ex = assertThrows(AuthException.class,
                () -> learningService.chapterDetail(USER_A, PAID_COURSE, chapterId(PAID_COURSE, 2)));
        assertTrue(ex.getMessage().contains("学习权益"), ex.getMessage());
    }

    @Test
    void progressReportRejectedWithoutEntitlement() {
        stubPaidCourse(PAID_COURSE, false, "未取得该课程的学习权益");
        ProgressReportRequest request = new ProgressReportRequest();
        request.setChapterId(chapterId(PAID_COURSE, 1));
        request.setVideoPositionSec(10);

        assertThrows(AuthException.class,
                () -> learningService.reportProgress(USER_A, PAID_COURSE, request));
    }

    @Test
    void courseServiceUnavailableReturnsControlledError() {
        when(courseClient.learnOutline(anyLong())).thenThrow(new IllegalStateException("course down"));
        BizException ex = assertThrows(BizException.class,
                () -> learningService.enroll(USER_A, FREE_COURSE));
        assertTrue(ex.getMessage().contains("课程服务暂时不可用"), ex.getMessage());
    }

    // ---------------------------------------------------------------- 辅助

    private long countLessons() {
        Long count = lessonMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<LearningLesson>lambdaQuery());
        return count == null ? 0L : count;
    }

    /** 把进度行的 update_time 回拨指定秒数，用于模拟"两次上报之间真实流逝的时间" */
    private void backdate(Long userId, Long chapterId, int seconds) {
        LocalDateTime past = LocalDateTime.now().minusSeconds(seconds);
        progressMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<LearningLessonProgress>lambdaUpdate()
                        .set(LearningLessonProgress::getUpdateTime, past)
                        .eq(LearningLessonProgress::getUserId, userId)
                        .eq(LearningLessonProgress::getChapterId, chapterId));
    }

    private LessonDetailVO report(Long courseId, Long chapterId, int position,
                                  Integer duration, Boolean completed) {
        return report(USER_A, courseId, chapterId, position, duration, completed);
    }

    private LessonDetailVO report(Long userId, Long courseId, Long chapterId, int position,
                                  Integer duration, Boolean completed) {
        ProgressReportRequest request = new ProgressReportRequest();
        request.setChapterId(chapterId);
        request.setVideoPositionSec(position);
        request.setVideoDurationSec(duration);
        request.setCompleted(completed);
        return learningService.reportProgress(userId, courseId, request);
    }

    private Long chapterId(Long courseId, int order) {
        CourseOutlineDTO outline = courseId.equals(FREE_COURSE)
                ? freeOutline(courseId) : paidOutline(courseId);
        return outline.getChapters().get(order - 1).getId();
    }

    @Test
    void rangeStats_keepsEachDaySeparateFromCumulative() {
        stubFreeCourse(FREE_COURSE);
        factRecorder.record(USER_A, FREE_COURSE, 1L, 30, false, false, shanghai(2026, 10, 1));
        factRecorder.record(USER_A, FREE_COURSE, 1L, 60, false, false, shanghai(2026, 10, 2));

        var day1 = teacherStatsService.page(7L, FREE_COURSE, 1, 5, true, "2026-10-01", "2026-10-01");
        var day2 = teacherStatsService.page(7L, FREE_COURSE, 1, 5, true, "2026-10-02", "2026-10-02");
        var both = teacherStatsService.page(7L, FREE_COURSE, 1, 5, true, "2026-10-01", "2026-10-02");

        assertEquals(30L, day1.getRecords().get(0).getTotalStudySeconds());
        assertEquals(60L, day2.getRecords().get(0).getTotalStudySeconds());
        assertEquals(90L, both.getRecords().get(0).getTotalStudySeconds());
        assertEquals(1L, both.getRecords().get(0).getLearnerCount());
    }

    @Test
    void repeatedFinish_countsCourseOnce() {
        stubFreeCourse(FREE_COURSE);
        factRecorder.record(USER_A, FREE_COURSE, 1L, 0, false, true, shanghai(2026, 10, 1));
        factRecorder.record(USER_A, FREE_COURSE, 2L, 0, false, true, shanghai(2026, 10, 2));
        var both = teacherStatsService.page(7L, FREE_COURSE, 1, 5, true, "2026-10-01", "2026-10-02");
        assertEquals(1L, both.getRecords().get(0).getFinishedLearnerCount());
    }

    @Test
    void rangeBeforeFactCoverage_isNotFakeZero() {
        stubFreeCourse(FREE_COURSE);
        var page = teacherStatsService.page(7L, FREE_COURSE, 1, 5, true, "1999-01-01", "1999-01-02");
        assertEquals("INSUFFICIENT", page.getRecords().get(0).getLearningCoverage());
        assertEquals(null, page.getRecords().get(0).getLearnerCount());
        assertEquals(null, page.getRecords().get(0).getTotalStudySeconds());
    }

    @Test
    void adoptionRate_countsDistinctHelpfulReplies() {
        stubFreeCourse(FREE_COURSE);
        long questionId = 9100L;
        InteractionQuestion question = new InteractionQuestion();
        question.setId(questionId);
        question.setTitle("问题");
        question.setDescription("描述");
        question.setCourseId(FREE_COURSE);
        question.setUserId(USER_A);
        question.setHidden(0);
        question.setAnswerTimes(4);
        question.setCreateTime(LocalDateTime.of(2026, 10, 1, 8, 0));
        questionMapper.insert(question);
        for (int i = 0; i < 4; i++) {
            InteractionReply reply = new InteractionReply();
            reply.setId(9200L + i);
            reply.setQuestionId(questionId);
            reply.setUserId(1L);
            reply.setContent("回答" + i);
            reply.setHidden(0);
            reply.setCreateTime(LocalDateTime.of(2026, 10, 1, 9, i));
            replyMapper.insert(reply);
            InteractionReplyExtra extra = new InteractionReplyExtra();
            extra.setReplyId(reply.getId());
            extra.setQuestionId(questionId);
            extra.setSource(InteractionReplyExtra.SOURCE_SYSTEM_AI);
            extra.setCorrected(0);
            replyExtraMapper.insert(extra);
        }
        InteractionReply teacherReply = new InteractionReply();
        teacherReply.setId(9290L);
        teacherReply.setQuestionId(questionId);
        teacherReply.setUserId(7L);
        teacherReply.setContent("教师回复");
        teacherReply.setHidden(0);
        teacherReply.setCreateTime(LocalDateTime.of(2026, 10, 1, 11, 0));
        replyMapper.insert(teacherReply);
        InteractionReplyExtra teacherExtra = new InteractionReplyExtra();
        teacherExtra.setReplyId(9290L);
        teacherExtra.setQuestionId(questionId);
        teacherExtra.setSource(InteractionReplyExtra.SOURCE_TEACHER);
        teacherExtra.setCorrected(0);
        replyExtraMapper.insert(teacherExtra);

        helpfulMapper.insert(mark(9200L, USER_A));
        helpfulMapper.insert(mark(9200L, USER_B));
        helpfulMapper.insert(mark(9201L, USER_A));

        var stats = teacherStatsService.page(7L, FREE_COURSE, 1, 5, true, null, null).getRecords().get(0);
        assertEquals(4L, stats.getAiSampleCount());
        assertEquals(2L, stats.getAiAdoptedCount());
        assertEquals(50.0, stats.getAiAdoptRate());

        helpfulMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.<InteractionReplyHelpful>lambdaQuery()
                .eq(InteractionReplyHelpful::getReplyId, 9201L)
                .eq(InteractionReplyHelpful::getUserId, USER_A));
        var afterUndo = teacherStatsService.page(7L, FREE_COURSE, 1, 5, true, null, null).getRecords().get(0);
        assertEquals(1L, afterUndo.getAiAdoptedCount());
        assertEquals(25.0, afterUndo.getAiAdoptRate());
    }

    private static LocalDateTime shanghai(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 12, 0)
                .atZone(StatsWindow.ZONE)
                .withZoneSameInstant(java.time.ZoneId.systemDefault())
                .toLocalDateTime();
    }

    private static InteractionReplyHelpful mark(long replyId, long userId) {
        InteractionReplyHelpful row = new InteractionReplyHelpful();
        row.setReplyId(replyId);
        row.setUserId(userId);
        return row;
    }

    private void stubFreeCourse(Long courseId) {
        when(courseClient.learnOutline(courseId)).thenReturn(R.ok(freeOutline(courseId)));
    }

    private void enrollPaid(long courseId) {
        when(courseClient.learnOutline(courseId)).thenReturn(R.ok(paidOutline(courseId)));
        when(tradeClient.entitlementDetail(anyLong(), org.mockito.ArgumentMatchers.eq(courseId)))
                .thenReturn(R.ok(EntitlementDecision.grant(Constants.ENTITLEMENT_PAID, LocalDateTime.now().plusMonths(12))));
        when(tradeClient.hasPaidEntitlement(anyLong(), org.mockito.ArgumentMatchers.eq(courseId))).thenReturn(R.ok(true));
        learningService.enroll(USER_A, courseId);
    }

    private void stubPaidCourse(Long courseId, boolean allowed, String reason) {
        when(courseClient.learnOutline(courseId)).thenReturn(R.ok(paidOutline(courseId)));
        EntitlementDecision decision = allowed
                ? EntitlementDecision.grant(Constants.ENTITLEMENT_PAID, LocalDateTime.now().plusMonths(12))
                : EntitlementDecision.deny(reason);
        when(tradeClient.entitlementDetail(anyLong(), anyLong())).thenReturn(R.ok(decision));
        when(tradeClient.hasPaidEntitlement(anyLong(), anyLong())).thenReturn(R.ok(allowed));
    }

    private CourseOutlineDTO freeOutline(Long courseId) {
        CourseOutlineDTO outline = new CourseOutlineDTO();
        outline.setId(courseId);
        outline.setName("免费课程" + courseId);
        outline.setFree(1);
        outline.setPrice(0);
        outline.setStatus(Constants.COURSE_STATUS_ONLINE);
        outline.setCreater(7L);
        outline.setCategoryEnabled(true);
        outline.setStudentVisible(true);
        outline.setChapters(chapters(courseId, false));
        return outline;
    }

    private CourseOutlineDTO paidOutline(Long courseId) {
        CourseOutlineDTO outline = new CourseOutlineDTO();
        outline.setId(courseId);
        outline.setName("付费课程" + courseId);
        outline.setFree(0);
        outline.setPrice(19900);
        outline.setStatus(Constants.COURSE_STATUS_ONLINE);
        outline.setCreater(7L);
        outline.setCategoryEnabled(true);
        outline.setStudentVisible(true);
        outline.setChapters(chapters(courseId, false));
        return outline;
    }

    private List<LearnChapterDTO> chapters(Long courseId, boolean previewFirst) {
        LearnChapterDTO c1 = new LearnChapterDTO();
        c1.setId(courseId * 10 + 1);
        c1.setCourseId(courseId);
        c1.setName("第一章");
        c1.setChapterOrder(1);
        c1.setFreePreview(previewFirst);
        c1.setVideoDurationSec(1200);
        LearnChapterDTO c2 = new LearnChapterDTO();
        c2.setId(courseId * 10 + 2);
        c2.setCourseId(courseId);
        c2.setName("第二章");
        c2.setChapterOrder(2);
        c2.setFreePreview(false);
        c2.setVideoDurationSec(1200);
        LearnChapterDTO c3 = new LearnChapterDTO();
        c3.setId(courseId * 10 + 3);
        c3.setCourseId(courseId);
        c3.setName("第三章");
        c3.setChapterOrder(3);
        c3.setVideoDurationSec(null);
        return List.of(c1, c2, c3);
    }
}
