package com.kzai.learning.service;

import com.kzai.common.exception.BizException;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.QuestionCreateRequest;
import com.kzai.learning.entity.InteractionQuestion;
import com.kzai.learning.mapper.InteractionQuestionMapper;
import com.kzai.learning.mapper.InteractionReplyMapper;
import com.kzai.learning.mapper.InteractionReplyExtraMapper;
import com.kzai.learning.mapper.QuestionReplyTaskMapper;

import com.kzai.common.result.R;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * M15.12-R1 课程问答提交安全回归：
 * 课程状态以课程服务权威快照为准（course.learnOutline 的 status/categoryEnabled/studentVisible）——
 * ①上架课可提交 ②下架课拒绝 ③禁用分类拒绝 ④学员不可见拒绝 ⑤不存在课程拒绝
 * ⑥课程服务故障 fail-closed ⑦拒绝路径零业务写入（无问答/回复/任务落库）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionSubmitGuardTest {

    @Mock InteractionQuestionMapper questionMapper;
    @Mock InteractionReplyMapper replyMapper;
    @Mock InteractionReplyExtraMapper extraMapper;
    @Mock QuestionReplyTaskMapper taskMapper;
    @Mock CourseClient courseClient;
    @Mock EntitlementService entitlementService;
    @Mock NotificationOutboxService notificationOutbox;

    private QuestionService service;

    private static CourseOutlineDTO outline(int status, boolean categoryEnabled, boolean studentVisible) {
        CourseOutlineDTO dto = new CourseOutlineDTO();
        dto.setId(1001L);
        dto.setName("回归课程");
        dto.setStatus(status);
        dto.setCategoryEnabled(categoryEnabled);
        dto.setStudentVisible(studentVisible);
        return dto;
    }

    @BeforeEach
    void setup() {
        service = new QuestionService(questionMapper, replyMapper, extraMapper, taskMapper,
                courseClient, entitlementService, notificationOutbox,
                org.mockito.Mockito.mock(com.kzai.learning.points.PointService.class));
        // 提交路径在拒绝前不应触达任何写 mapper（lenient 供正向用例使用）
        lenient().when(questionMapper.insert(ArgumentMatchers.any(InteractionQuestion.class)))
                .thenReturn(1);
    }

    private void mockOutline(CourseOutlineDTO dto) {
        when(courseClient.learnOutline(1001L)).thenReturn(dto == null ? null : R.ok(dto));
    }

    private BizException ask() {
        return assertThrows(BizException.class, () ->
                service.createQuestion(3L, req()));
    }

    private com.kzai.learning.dto.QuestionCreateRequest req() {
        com.kzai.learning.dto.QuestionCreateRequest r = new com.kzai.learning.dto.QuestionCreateRequest();
        r.setCourseId(1001L);
        r.setTitle("回归验证标题");
        r.setDescription("回归验证描述内容");
        r.setAnonymity(0);
        return r;
    }

    @Test
    void 上架课程可提交_且无越权写入之外的行为() {
        mockOutline(outline(2, true, true));
        // 章节未关联：不触发权益校验；提交成功（insert 已 stub）
        var out = service.createQuestion(3L, req());
        assertTrue(out != null);
    }

    @Test
    void 下架课程拒绝() {
        mockOutline(outline(3, true, false)); // status=3 下架
        assertEquals("课程不存在或已下架", ask().getMessage());
    }

    @Test
    void 禁用分类课程拒绝() {
        mockOutline(outline(2, false, false)); // 分类禁用 → studentVisible=false
        assertEquals("课程不存在或已下架", ask().getMessage());
    }

    @Test
    void 学员不可见课程拒绝() {
        mockOutline(outline(2, true, false));
        assertEquals("课程不存在或已下架", ask().getMessage());
    }

    @Test
    void 不存在课程拒绝() {
        mockOutline(null);
        assertEquals("课程不存在或已下架", ask().getMessage());
    }

    @Test
    void 课程服务故障时fail_closed() {
        when(courseClient.learnOutline(1001L)).thenThrow(new IllegalStateException("connect refused"));
        var ex = assertThrows(BizException.class, () -> service.createQuestion(3L, req()));
        assertEquals("课程服务暂不可用，请稍后再试", ex.getMessage());
    }

    @Test
    void 拒绝路径不产生任何业务写入() {
        // 下架拒绝后：question/reply/task 的写方法均未被调用（零副作用）
        mockOutline(outline(3, true, false));
        assertThrows(BizException.class, () -> service.createQuestion(3L, req()));
        org.mockito.Mockito.verifyNoInteractions(replyMapper, extraMapper, taskMapper, notificationOutbox);
        org.mockito.Mockito.verify(questionMapper, org.mockito.Mockito.never())
                .insert(ArgumentMatchers.any(InteractionQuestion.class));
    }
}
