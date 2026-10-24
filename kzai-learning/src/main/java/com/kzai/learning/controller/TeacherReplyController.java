package com.kzai.learning.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.dto.TeacherCorrectRequest;
import com.kzai.learning.service.TeacherQuestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** M10：教师回复级管理（修正/可见性/精华；仅 TEACHER/ADMIN，服务端归属校验）。 */
@RestController
@RequestMapping("/teacher/replies")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_TEACHER, Constants.ROLE_ADMIN})
public class TeacherReplyController {

    private final TeacherQuestionService teacherQuestionService;

    /** 人工修正 AI 回复（修正前内容留存 + 统一审计时间线） */
    @PostMapping("/{replyId}/correct")
    public R<Map<String, Object>> correct(@PathVariable("replyId") long replyId,
                                          @Valid @RequestBody TeacherCorrectRequest req) {
        return R.ok(teacherQuestionService.correct(UserContext.getUserId(), UserContext.getRoles(),
                        replyId, req))
                .requestId(UserContext.getRequestId());
    }

    @PatchMapping("/{replyId}/visibility")
    public R<Map<String, Object>> visibility(@PathVariable("replyId") long replyId,
                                             @RequestBody Map<String, Object> body) {
        boolean visible = !"false".equalsIgnoreCase(String.valueOf(body.get("visible")));
        return R.ok(teacherQuestionService.setReplyVisibility(
                        UserContext.getUserId(), UserContext.getRoles(), replyId, visible))
                .requestId(UserContext.getRequestId());
    }

    /** 精华标记（≠AI 采纳事件，口径见执行报告） */
    @PostMapping("/{replyId}/feature")
    public R<Map<String, Object>> feature(@PathVariable("replyId") long replyId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        boolean on = body == null || !"false".equalsIgnoreCase(String.valueOf(body.get("on")));
        return R.ok(teacherQuestionService.feature(UserContext.getUserId(), UserContext.getRoles(),
                        replyId, on))
                .requestId(UserContext.getRequestId());
    }

    // M15.20-B：AI 回复检查标记 + 学员采纳（有帮助）——extra upsert

    private final com.kzai.learning.service.ReplyExtraOpsService replyExtraOpsService;

    /**
     * M15.21：教师标记 AI 回复已检查（校验链：回复存在→属于本人课程→确为 AI 回复→标记）。
     * Service.markAiChecked 校验 AI 来源与幂等；课程归属在此校验（teacher_question 模式）。
     */
    @org.springframework.web.bind.annotation.PostMapping("/{replyId}/check-ai")
    public R<Boolean> markAiChecked(@org.springframework.web.bind.annotation.PathVariable("replyId") Long replyId) {
        long teacherId = com.kzai.common.context.UserContext.getUserId();
        // 课程归属校验：回复所属课程必须是本人教授课程（复用教师问答归属链）
        teacherQuestionService.requireReplyOfTeacher(replyId, teacherId, UserContext.getRoles());
        replyExtraOpsService.markAiChecked(replyId, teacherId);
        return R.ok(Boolean.TRUE);
    }

}
