package com.kzai.learning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.common.exception.BizException;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.EntitlementDecision;
import com.kzai.learning.dto.QuestionCreateRequest;
import com.kzai.learning.dto.TeacherCorrectRequest;
import com.kzai.learning.entity.InteractionQuestion;
import com.kzai.learning.entity.InteractionReply;
import com.kzai.learning.entity.InteractionReplyExtra;
import com.kzai.learning.entity.QuestionReplyTask;
import com.kzai.learning.mapper.InteractionQuestionMapper;
import com.kzai.learning.mapper.InteractionReplyExtraMapper;
import com.kzai.learning.mapper.InteractionReplyMapper;
import com.kzai.learning.mapper.QuestionReplyTaskMapper;
import com.kzai.common.result.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * M10 核心单测（H2 MySQL 兼容模式，真实 Mapper）：
 * 提问落库+任务唯一约束 / 事务一致性（回复+extra+聚合+任务）/ 教师修正审计 / 重生成拒绝。
 * 注意：active_flag 为 MySQL/H2 生成列——重复任务插入由唯一约束拒绝。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:qa_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.sleuth.enabled=false"
})
@MapperScan("com.kzai.learning.mapper")
@Import({QuestionService.class, TeacherQuestionService.class, AiReplyTxService.class,
        com.kzai.learning.util.OperationAuditLogger.class})
@Transactional
class QuestionReplyTxTest {

    @Autowired
    QuestionService questionService;
    @Autowired
    TeacherQuestionService teacherService;
    @Autowired
    AiReplyTxService txService;
    @Autowired
    InteractionQuestionMapper questionMapper;
    @Autowired
    InteractionReplyMapper replyMapper;
    @Autowired
    InteractionReplyExtraMapper extraMapper;
    @Autowired
    QuestionReplyTaskMapper taskMapper;

    @MockBean
    CourseClient courseClient;
    @MockBean
    com.kzai.learning.client.UserClient userClient;
    @MockBean
    com.kzai.learning.client.AigcClient aigcClient;
    @MockBean
    EntitlementService entitlementService;

    private static final long STUDENT = 5L;
    private static final long TEACHER = 7L;
    private static final long COURSE = 100L;
    private static final long CHAPTER = 1001L;

    @BeforeEach
    void mockCourseScope() {
        CourseOutlineDTO outline = new CourseOutlineDTO();
        outline.setId(COURSE);
        outline.setName("测试课程");
        outline.setChapters(List.of(chapter(CHAPTER)));
        // M15.12-R1：问答提交安全校验新增字段（上架/分类启用/学员可见）
        outline.setStatus(2);
        outline.setCategoryEnabled(Boolean.TRUE);
        outline.setStudentVisible(Boolean.TRUE);
        when(courseClient.learnOutline(COURSE)).thenReturn(R.ok(outline));
        when(courseClient.byCreator(TEACHER)).thenReturn(R.ok(List.of(outline)));
        when(courseClient.ownership(eq(TEACHER), any())).thenReturn(R.ok(Map.of(COURSE, true)));
        when(userClient.writeOperationLog(any())).thenReturn(R.ok(true));
        var allowed = new EntitlementDecision();
        allowed.setAllowed(Boolean.TRUE);
        when(entitlementService.resolve(any(), any())).thenReturn(allowed);
    }

    private com.kzai.learning.dto.LearnChapterDTO chapter(long id) {
        com.kzai.learning.dto.LearnChapterDTO c = new com.kzai.learning.dto.LearnChapterDTO();
        c.setId(id);
        c.setName("第一章");
        return c;
    }

    private QuestionCreateRequest createReq() {
        QuestionCreateRequest req = new QuestionCreateRequest();
        req.setTitle("什么是 Spring Boot？");
        req.setDescription("请解释自动配置原理。");
        req.setCourseId(COURSE);
        req.setChapterId(CHAPTER);
        return req;
    }

    @Test
    void createQuestion_persistsWithZeroCounts_andQueuesTask() throws Exception {
        Map<String, Object> out = questionService.createQuestion(STUDENT, createReq());
        long qid = Long.parseLong(String.valueOf(out.get("questionId")));
        InteractionQuestion q = questionMapper.selectById(qid);
        assertEquals(0, q.getAnswerTimes());
        assertEquals(null, q.getLatestAnswerId());
        assertEquals(InteractionQuestion.STATUS_UNVIEWED, q.getStatus());
        assertEquals(STUDENT, q.getUserId());
        // 持久化任务已创建（PENDING）
        QuestionReplyTask t = taskMapper.selectById(Long.parseLong(String.valueOf(out.get("taskId"))));
        assertEquals(QuestionReplyTask.STATUS_PENDING, t.getStatus());
        assertEquals(qid, t.getQuestionId());
    }

    @Test
    void secondActiveTaskForSameQuestion_rejectedByUniqueConstraint() {
        questionService.createQuestion(STUDENT, createReq());
        // 同一问题再次入队（模拟重复提交）→ 生成列唯一约束拒绝
        QuestionReplyTask dup = new QuestionReplyTask();
        dup.setQuestionId(questionMapper.selectList(null).get(0).getId());
        dup.setAction(QuestionReplyTask.ACTION_GENERATE);
        dup.setStatus(QuestionReplyTask.STATUS_PENDING);
        dup.setAttempt(0);
        dup.setCreateTime(LocalDateTime.now());
        dup.setUpdateTime(LocalDateTime.now());
        assertThrows(Exception.class, () -> taskMapper.insert(dup),
                "同一问题同时仅允许一个进行中任务（uk_question_active）");
    }

    @Test
    void completeGeneration_singleTx_replyExtraAggregationTask() {
        Map<String, Object> out = questionService.createQuestion(STUDENT, createReq());
        long taskId = Long.parseLong(String.valueOf(out.get("taskId")));
        long qid = Long.parseLong(String.valueOf(out.get("questionId")));

        txService.completeGeneration(taskId, "【MOCK】Spring Boot 自动配置是……", "MOCK", Map.of());

        QuestionReplyTask t = taskMapper.selectById(taskId);
        assertEquals(QuestionReplyTask.STATUS_SUCCESS, t.getStatus());
        InteractionReply reply = replyMapper.selectById(t.getReplyId());
        assertEquals(9999L, reply.getUserId());
        assertEquals(0, reply.getIsStudent());
        InteractionReplyExtra extra = extraMapper.selectById(reply.getId());
        assertEquals(InteractionReplyExtra.SOURCE_SYSTEM_AI, extra.getSource());
        assertEquals("MOCK", extra.getModelCode());
        assertEquals(taskId, extra.getAiTaskId());
        InteractionQuestion q = questionMapper.selectById(qid);
        assertEquals(1, q.getAnswerTimes());
        assertEquals(reply.getId(), q.getLatestAnswerId());
        // 幂等：重复回调不产生第二条回复
        txService.completeGeneration(taskId, "again", "MOCK", Map.of());
        assertEquals(1, replyMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InteractionReply>()
                        .eq(InteractionReply::getQuestionId, qid)));
    }

    @Test
    void teacherCorrectKeepsPrevContent_andBlocksRegenerate() {
        Map<String, Object> out = questionService.createQuestion(STUDENT, createReq());
        long taskId = Long.parseLong(String.valueOf(out.get("taskId")));
        long qid = Long.parseLong(String.valueOf(out.get("questionId")));
        txService.completeGeneration(taskId, "AI 原始回答", "MOCK", Map.of());
        QuestionReplyTask t = taskMapper.selectById(taskId);
        long replyId = t.getReplyId();

        var res = teacherService.correct(TEACHER, List.of("TEACHER"), replyId,
                correctReq("教师修正后的回答"));
        assertEquals(true, res.get("corrected"));
        InteractionReplyExtra extra = extraMapper.selectById(replyId);
        assertEquals(1, extra.getCorrected());
        assertEquals("AI 原始回答", extra.getPrevContent(), "修正前内容完整留存");
        assertEquals(TEACHER, extra.getCorrector());

        // 已修正的 AI 回复不允许重生成覆盖
        assertThrows(BizException.class,
                () -> teacherService.regenerate(TEACHER, List.of("TEACHER"), qid));
    }

    @Test
    void teacherReply_sourceTeacher_notStudent() {
        Map<String, Object> out = questionService.createQuestion(STUDENT, createReq());
        long qid = Long.parseLong(String.valueOf(out.get("questionId")));
        teacherService.reply(TEACHER, List.of("TEACHER"), qid,
                replyReq("教师直接回复内容"));
        List<InteractionReply> replies = replyMapper.selectList(null);
        assertEquals(1, replies.size());
        assertEquals(0, replies.get(0).getIsStudent());
        assertEquals(InteractionReplyExtra.SOURCE_TEACHER,
                extraMapper.selectById(replies.get(0).getId()).getSource());
    }

    @Test
    void chapterOwnership_validated() {
        QuestionCreateRequest bad = createReq();
        bad.setChapterId(999999L);
        assertThrows(BizException.class, () -> questionService.createQuestion(STUDENT, bad),
                "章节不属于课程被拒绝");
    }

    private TeacherCorrectRequest correctReq(String content) {
        TeacherCorrectRequest r = new TeacherCorrectRequest();
        r.setContent(content);
        return r;
    }

    private com.kzai.learning.dto.QuestionReplyRequest replyReq(String content) {
        com.kzai.learning.dto.QuestionReplyRequest r = new com.kzai.learning.dto.QuestionReplyRequest();
        r.setContent(content);
        return r;
    }
}
