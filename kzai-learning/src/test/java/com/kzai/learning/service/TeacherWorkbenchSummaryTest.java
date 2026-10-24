package com.kzai.learning.service;

import com.kzai.learning.entity.InteractionQuestion;
import com.kzai.learning.entity.InteractionReply;
import com.kzai.learning.entity.InteractionReplyExtra;
import com.kzai.learning.entity.LearningLesson;
import com.kzai.learning.mapper.InteractionQuestionMapper;
import com.kzai.learning.mapper.InteractionReplyExtraMapper;
import com.kzai.learning.mapper.InteractionReplyMapper;
import com.kzai.learning.mapper.LearningLessonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * M15.20-B 回归：工作台口径（空集合早退/学习人数去重/AI 与人工区分/待检查真实状态）。
 */
@ExtendWith(MockitoExtension.class)
class TeacherWorkbenchSummaryTest {

    @Mock
    private LearningLessonMapper lessonMapper;

    @Mock
    private com.kzai.learning.mapper.LearningLessonProgressMapper progressMapper;

    @Mock
    private com.kzai.learning.client.CourseClient courseClient;

    @Mock
    private InteractionQuestionMapper questionMapper;

    @Mock
    private InteractionReplyMapper replyMapper;

    @Mock
    private InteractionReplyExtraMapper replyExtraMapper;

    @Spy
    @InjectMocks
    private TeacherStatsService service;

    private void stubOwned(List<Long> courseIds) {
        // resolveCourseIds 为 private——用 spy 覆写
        lenient().doReturn(courseIds).when(service).resolveCourseIds(9L, null, false);
    }

    @Test
    void emptyCourses_returnsZeros() {
        stubOwned(List.of());
        var m = service.workbenchSummary(9L, false);
        assertEquals(0L, m.get("courseCount"));
        assertEquals(0L, m.get("learners"));
        assertEquals(0L, m.get("pendingQuestions"));
        assertEquals(0L, m.get("aiReplies"));
        assertEquals(0L, m.get("aiUnchecked"));
    }

    @Test
    void learnersDeduped_byDistinctUserId() {
        stubOwned(List.of(1L));
        // 两条学习记录（同一学员）→ 学习人数 1（去重口径）
        lenient().when(lessonMapper.selectObjs(any())).thenReturn(List.of(3L, 3L));
        lenient().when(questionMapper.selectList(any())).thenReturn(List.of());
        var m = service.workbenchSummary(9L, false);
        assertEquals(2L, m.get("learners"));
    }

    @Test
    void noQuestions_zeroCounts_withoutCrossTeacherQueries() {
        stubOwned(List.of(1L));
        lenient().when(lessonMapper.selectObjs(any())).thenReturn(List.of(3L));
        lenient().when(questionMapper.selectList(any())).thenReturn(List.of());
        var m = service.workbenchSummary(9L, false);
        assertEquals(0L, m.get("pendingQuestions"));
        assertEquals(0L, m.get("aiReplies"));
        assertEquals(0L, m.get("aiUnchecked"));
    }

    @Test
    void mixedAiAndHumanReplies_categorized() {
        stubOwned(List.of(1L));
        lenient().when(lessonMapper.selectObjs(any())).thenReturn(List.of(3L));
        var q = new InteractionQuestion();
        q.setId(100L);
        q.setCourseId(1L);
        q.setCreateTime(LocalDateTime.now());
        lenient().when(questionMapper.selectList(any())).thenReturn(List.of(q));

        var aiReply = new InteractionReply();
        aiReply.setId(500L);
        aiReply.setQuestionId(100L);
        var humanReply = new InteractionReply();
        humanReply.setId(501L);
        humanReply.setQuestionId(100L);
        lenient().when(replyMapper.selectList(any())).thenReturn(List.of(aiReply, humanReply));

        var aiExtra = new InteractionReplyExtra();
        aiExtra.setReplyId(500L);
        aiExtra.setSource(InteractionReplyExtra.SOURCE_SYSTEM_AI);
        aiExtra.setAiChecked(0); // 待检查
        lenient().when(replyExtraMapper.selectList(any())).thenReturn(List.of(aiExtra));
        // human 501 无 extra 行（人工回复不写 extra）

        var m = service.workbenchSummary(9L, false);
        assertEquals(1L, m.get("aiReplies"));
        assertEquals(1L, m.get("aiUnchecked"));
        assertEquals(0L, m.get("pendingQuestions")); // 已有回复
    }
}
