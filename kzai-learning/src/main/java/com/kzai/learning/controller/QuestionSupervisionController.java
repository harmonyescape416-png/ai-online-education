package com.kzai.learning.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.entity.InteractionQuestion;
import com.kzai.learning.entity.InteractionReply;
import com.kzai.learning.entity.InteractionReplyExtra;
import com.kzai.learning.mapper.InteractionQuestionMapper;
import com.kzai.learning.mapper.InteractionReplyExtraMapper;
import com.kzai.learning.mapper.InteractionReplyMapper;
import com.kzai.learning.util.OperationAuditLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M13 课程问答监管（管理员跨课程；复用 M10 表与服务，不改原始表语义）。
 * <ul>
 *   <li>查询全部问答：课程/用户/时间/关键词筛选；展示 AI/教师/学员来源、
 *       MOCK/REAL 模型标识、修正历史（extra）、隐藏与精华状态；</li>
 *   <li>隐藏/恢复：复用 {@link com.kzai.learning.service.TeacherQuestionService}
 *       既有方法（ADMIN 豁免课程归属；不绕过权限直接改表），
 *       interaction_question.status 保持原始语义（0未查看/1已查看）不变；</li>
 *   <li>监管原因必填并真实写入 sys_operation_log 审计时间线。</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/supervision/questions")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_ADMIN})
public class QuestionSupervisionController {

    private final InteractionQuestionMapper questionMapper;
    private final InteractionReplyMapper replyMapper;
    private final InteractionReplyExtraMapper extraMapper;
    private final com.kzai.learning.service.TeacherQuestionService teacherQuestionService;
    private final OperationAuditLogger audit;

    /** 问答分页（课程/用户/时间/关键词；含最新回复摘要与来源统计） */
    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(required = false) Long courseId,
                                       @RequestParam(required = false) Long userId,
                                       @RequestParam(required = false) String startTime,
                                       @RequestParam(required = false) String endTime,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Integer hidden,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        Page<InteractionQuestion> p = questionMapper.selectPage(new Page<>(page, Math.min(size, 50)),
                Wrappers.<InteractionQuestion>lambdaQuery()
                        .eq(courseId != null, InteractionQuestion::getCourseId, courseId)
                        .eq(userId != null, InteractionQuestion::getUserId, userId)
                        .ge(startTime != null && !startTime.isBlank(),
                                InteractionQuestion::getCreateTime, parseTime(startTime, true))
                        .le(endTime != null && !endTime.isBlank(),
                                InteractionQuestion::getCreateTime, parseTime(endTime, false))
                        .eq(hidden != null, InteractionQuestion::getHidden, hidden)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(InteractionQuestion::getTitle, keyword)
                                .or().like(InteractionQuestion::getDescription, keyword))
                        .orderByDesc(InteractionQuestion::getUpdateTime));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InteractionQuestion q : p.getRecords()) {
            rows.add(toRow(q));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.getTotal());
        out.put("records", rows);
        return R.ok(out);
    }

    /** 问答详情：问题 + 完整回复树（含隐藏；来源/模型/修正/精华元数据） */
    @GetMapping("/{questionId}")
    public R<Map<String, Object>> detail(@PathVariable long questionId) {
        InteractionQuestion q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException("问题不存在");
        }
        Map<String, Object> out = toRow(q);
        List<Map<String, Object>> replies = new ArrayList<>();
        for (InteractionReply r : replyMapper.selectList(Wrappers.<InteractionReply>lambdaQuery()
                .eq(InteractionReply::getQuestionId, questionId)
                .orderByAsc(InteractionReply::getCreateTime))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(r.getId()));
            m.put("content", r.getContent());
            m.put("userId", String.valueOf(r.getUserId()));
            m.put("isStudent", r.getIsStudent());
            m.put("hidden", r.getHidden());
            m.put("targetReplyId", r.getTargetReplyId() == null ? null : String.valueOf(r.getTargetReplyId()));
            m.put("createTime", r.getCreateTime());
            InteractionReplyExtra extra = extraMapper.selectById(r.getId());
            if (extra != null) {
                m.put("source", extra.getSource());           // SYSTEM_AI/TEACHER/STUDENT
                m.put("modelCode", extra.getModelCode());      // MOCK/REAL 模型标识
                m.put("corrected", extra.getCorrected());
                m.put("corrector", extra.getCorrector());
                m.put("correctTime", extra.getCorrectTime());
                m.put("prevContent", extra.getPrevContent());  // 修正历史（修正前快照）
                m.put("featured", extra.getFeatured());
            }
            replies.add(m);
        }
        out.put("replies", replies);
        return R.ok(out);
    }

    /** 隐藏/恢复问题（监管原因必填；复用既有服务与审计链路） */
    public record VisibilityRequest(boolean visible, String reason) { }

    @PatchMapping("/questions/{questionId}/visibility")
    public R<Map<String, Object>> setQuestionVisibility(@PathVariable long questionId,
                                                        @RequestBody VisibilityRequest req) {
        requireReason(req);
        long adminId = UserContext.getUserId();
        String adminName = UserContext.getUsername();
        Map<String, Object> out = teacherQuestionService.setQuestionVisibility(
                adminId, List.of(Constants.ROLE_ADMIN), questionId, req.visible());
        audit.log(adminId, adminName, "SUPERVISION", req.visible() ? "ADMIN_SHOW_QUESTION" : "ADMIN_HIDE_QUESTION",
                "interaction_question", questionId,
                "{\"reason\":\"" + esc(req.reason()) + "\"}");
        return R.ok(out);
    }

    @PatchMapping("/replies/{replyId}/visibility")
    public R<Map<String, Object>> setReplyVisibility(@PathVariable long replyId,
                                                     @RequestBody VisibilityRequest req) {
        requireReason(req);
        long adminId = UserContext.getUserId();
        String adminName = UserContext.getUsername();
        Map<String, Object> out = teacherQuestionService.setReplyVisibility(
                adminId, List.of(Constants.ROLE_ADMIN), replyId, req.visible());
        audit.log(adminId, adminName, "SUPERVISION", req.visible() ? "ADMIN_SHOW_REPLY" : "ADMIN_HIDE_REPLY",
                "interaction_reply", replyId,
                "{\"reason\":\"" + esc(req.reason()) + "\"}");
        return R.ok(out);
    }

    private void requireReason(VisibilityRequest req) {
        if (req.reason() == null || req.reason().isBlank()) {
            throw new BizException("监管原因不能为空");
        }
    }

    private Map<String, Object> toRow(InteractionQuestion q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(q.getId()));
        m.put("title", q.getTitle());
        m.put("description", q.getDescription());
        m.put("courseId", String.valueOf(q.getCourseId()));
        m.put("userId", String.valueOf(q.getUserId()));
        m.put("status", q.getStatus()); // 原始语义 0未查看/1已查看（不改变）
        m.put("hidden", q.getHidden());
        m.put("answerTimes", q.getAnswerTimes());
        m.put("latestAnswerId", q.getLatestAnswerId() == null ? null : String.valueOf(q.getLatestAnswerId()));
        m.put("createTime", q.getCreateTime());
        m.put("updateTime", q.getUpdateTime());
        return m;
    }

    private static LocalDateTime parseTime(String s, boolean start) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(s.length() == 10 ? s + (start ? "T00:00:00" : "T23:59:59") : s);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
