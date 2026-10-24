package com.kzai.learning.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.dto.QuestionReplyRequest;
import com.kzai.learning.dto.TeacherCorrectRequest;
import com.kzai.learning.service.TeacherQuestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 教师问答管理（M10）：仅本人课程（ADMIN 豁免）；服务端归属校验。
 * 路由前缀 /teacher/questions/**，需在 Gateway 中先于课程服务的 /teacher/** 命中。
 * 回复级操作（修正/隐藏/精华）在 /teacher/replies/**（TeacherReplyController）。
 */
@RestController
@RequestMapping("/teacher/questions")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_TEACHER, Constants.ROLE_ADMIN})
public class TeacherQuestionController {

    private final TeacherQuestionService teacherQuestionService;

    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) Long courseId,
                                       @RequestParam(required = false) Integer hidden,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return R.ok(teacherQuestionService.list(UserContext.getUserId(), UserContext.getRoles(),
                        courseId, hidden, page, size))
                .requestId(UserContext.getRequestId());
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable("id") long id) {
        return R.ok(teacherQuestionService.detail(UserContext.getUserId(), UserContext.getRoles(), id))
                .requestId(UserContext.getRequestId());
    }

    @PostMapping("/{id}/reply")
    public R<Map<String, Object>> reply(@PathVariable("id") long id,
                                        @Valid @RequestBody QuestionReplyRequest req) {
        return R.ok(teacherQuestionService.reply(UserContext.getUserId(), UserContext.getRoles(),
                        id, req))
                .requestId(UserContext.getRequestId());
    }

    /** 重新生成 AI 回复（已教师修正的 AI 回复将被拒绝） */
    @PostMapping("/{id}/regenerate")
    public R<Map<String, Object>> regenerate(@PathVariable("id") long id) {
        return R.ok(teacherQuestionService.regenerate(UserContext.getUserId(), UserContext.getRoles(), id))
                .requestId(UserContext.getRequestId());
    }

    @PatchMapping("/{id}/visibility")
    public R<Map<String, Object>> visibility(@PathVariable("id") long id,
                                             @RequestBody Map<String, Object> body) {
        boolean visible = !"false".equalsIgnoreCase(String.valueOf(body.get("visible")));
        return R.ok(teacherQuestionService.setQuestionVisibility(
                        UserContext.getUserId(), UserContext.getRoles(), id, visible))
                .requestId(UserContext.getRequestId());
    }
}
