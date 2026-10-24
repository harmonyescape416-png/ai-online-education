package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.exception.BizException;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.QuestionReplyRequest;
import com.kzai.learning.dto.TeacherCorrectRequest;
import com.kzai.learning.entity.InteractionQuestion;
import com.kzai.learning.entity.InteractionReply;
import com.kzai.learning.entity.InteractionReplyExtra;
import com.kzai.learning.entity.QuestionReplyTask;
import com.kzai.learning.mapper.InteractionQuestionMapper;
import com.kzai.learning.mapper.InteractionReplyExtraMapper;
import com.kzai.learning.mapper.InteractionReplyMapper;
import com.kzai.learning.mapper.QuestionReplyTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师问答管理（M10，任务书第七部分）。
 * <p>
 * 归属红线：教师只能管理<b>本人负责课程</b>的问答（courseClient.byCreator 归属校验，
 * ADMIN 豁免）；不得修改其他教师课程的问答。
 * <ul>
 *   <li>直接回复：is_student=0、source=TEACHER（区分 AI 与教师的原始局限由此解决）；</li>
 *   <li>人工修正：保留修正前内容（extra.prev_content + sys_operation_log 审计时间线），
 *       标记 corrected/corrector/correct_time；<b>AI 已被修正的回复不允许重生成覆盖</b>；</li>
 *   <li>重新生成：独立任务（uk_question_active 防并发），原回复保留可见，失败不清空；
 *       同一问题同时仅一个进行中任务；</li>
 *   <li>精华/隐藏：真实持久化（featured/hidden），精华≠AI 采纳事件（采纳率口径用户确认维持 null）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeacherQuestionService {

    private final InteractionQuestionMapper questionMapper;
    private final InteractionReplyMapper replyMapper;
    private final InteractionReplyExtraMapper extraMapper;
    private final QuestionReplyTaskMapper taskMapper;
    private final CourseClient courseClient;
    private final QuestionService questionService;
    private final AiReplyTxService txService;
    private final com.kzai.learning.util.OperationAuditLogger auditLogger;
    private final NotificationOutboxService notificationOutbox;

    /** 问答管理列表（仅本人课程；ADMIN 全量；可按课程/隐藏过滤） */
    public Map<String, Object> list(long teacherId, List<String> roles, Long courseId,
                                    Integer hidden, long page, long size) {
        boolean admin = roles != null && roles.contains("ADMIN");
        LambdaQueryWrapper<InteractionQuestion> wrapper = new LambdaQueryWrapper<InteractionQuestion>()
                .eq(hidden != null, InteractionQuestion::getHidden, hidden)
                .orderByDesc(InteractionQuestion::getUpdateTime);
        if (courseId != null) {
            requireOwnedCourse(teacherId, roles, courseId);
            wrapper.eq(InteractionQuestion::getCourseId, courseId);
        } else if (!admin) {
            List<Long> owned;
            try {
                var r = courseClient.byCreator(teacherId);
                owned = (r == null || r.getData() == null) ? List.of()
                        : r.getData().stream().map(CourseOutlineDTO::getId).toList();
            } catch (RuntimeException ex) {
                throw new BizException("课程服务暂不可用，无法确认课程归属");
            }
            if (owned.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("total", 0L);
                empty.put("records", List.of());
                return empty;
            }
            wrapper.in(InteractionQuestion::getCourseId, owned);
        }
        Page<InteractionQuestion> p = questionMapper.selectPage(new Page<>(page, Math.min(size, 50)), wrapper);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.getTotal());
        out.put("records", p.getRecords());
        return out;
    }

    /** 课程归属校验（ADMIN 豁免） */
    private void requireOwnedCourse(long teacherId, List<String> roles, long courseId) {
        if (roles != null && roles.contains("ADMIN")) {
            return;
        }
        try {
            var r = courseClient.ownership(teacherId, List.of(courseId));
            Map<Long, Boolean> own = r == null ? null : r.getData();
            if (own == null || !Boolean.TRUE.equals(own.get(courseId))) {
                throw new BizException("无权管理该课程的问答");
            }
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException("课程服务暂不可用，无法确认课程归属");
        }
    }

    /** 教师直接回复（根回复或追问） */
    @Transactional
    public Map<String, Object> reply(long teacherId, List<String> roles, long questionId,
                                     QuestionReplyRequest req) {
        InteractionQuestion q = requireOwnedQuestion(teacherId, roles, questionId);
        InteractionReply root = resolveRoot(q, req.getTargetReplyId());
        InteractionReply created = questionService.insertReply(teacherId, q, root, req.getTargetReplyId(),
                root == null ? null : root.getUserId(), req.getContent().trim(),
                req.getAnonymity() != null && req.getAnonymity() == 1,
                InteractionReplyExtra.SOURCE_TEACHER, null, null);
        questionService.bumpReplyCount(q.getId());
        // M11：教师回答 → 通知提问学员（同一事务入队）；回答者本人不重复通知
        Long owner = q.getUserId();
        if (owner != null && owner.longValue() != teacherId) {
            notificationOutbox.enqueue("QUESTION_REPLY:" + created.getId(), "QA",
                    String.valueOf(q.getId()), owner, "QUESTION_REPLY",
                    NotificationTexts.teacherReplyTitle(),
                    NotificationTexts.summary(created.getContent()),
                    NotificationTexts.payload(q.getId(), created.getId()));
        }
        return Map.of("replied", true);
    }

    /**
     * 人工修正 AI 回复：保留修正前完整内容（prev_content）+ 统一审计时间线；
     * 修正后该回复被标记，AI 重试/重生成不得覆盖。
     */
    @Transactional
    public Map<String, Object> correct(long teacherId, List<String> roles, long replyId,
                                       TeacherCorrectRequest req) {
        InteractionReply reply = requireReply(replyId);
        InteractionQuestion q = requireOwnedQuestion(teacherId, roles, reply.getQuestionId());
        InteractionReplyExtra extra = extraOf(replyId);
        if (!InteractionReplyExtra.SOURCE_SYSTEM_AI.equals(extra.getSource())) {
            throw new BizException("仅 AI 回复支持人工修正");
        }
        String prev = reply.getContent();
        reply.setContent(req.getContent().trim());
        reply.setUpdateTime(LocalDateTime.now());
        replyMapper.updateById(reply);

        extra.setCorrected(1);
        extra.setCorrector(teacherId);
        extra.setCorrectTime(LocalDateTime.now());
        extra.setPrevContent(prev);
        extraMapper.updateById(extra);

        // 统一审计时间线（修正前后内容 JSON 摘要 + prev_content 完整留存于 extra）
        auditLogger.log(teacherId, "teacher", "QA", "CORRECT_REPLY",
                "interaction_reply", replyId,
                "{\"questionId\":\"" + q.getId() + "\",\"prevLen\":" + prev.length()
                        + ",\"nextLen\":" + req.getContent().length() + "}");
        return Map.of("corrected", true);
    }

    /** 重新生成：独立任务；已教师修正的 AI 回复不允许覆盖；原回复保留可见 */
    @Transactional
    public Map<String, Object> regenerate(long teacherId, List<String> roles, long questionId) {
        InteractionQuestion q = requireOwnedQuestion(teacherId, roles, questionId);
        // 已修正的 AI 回复 → 拒绝重生成（教师审核内容优先）
        List<InteractionReply> aiReplies = replyMapper.selectList(
                new LambdaQueryWrapper<InteractionReply>()
                        .eq(InteractionReply::getQuestionId, questionId)
                        .orderByDesc(InteractionReply::getCreateTime));
        for (InteractionReply r : aiReplies) {
            InteractionReplyExtra extra = extraMapper.selectById(r.getId());
            if (extra != null && InteractionReplyExtra.SOURCE_SYSTEM_AI.equals(extra.getSource())
                    && extra.getCorrected() != null && extra.getCorrected() == 1) {
                throw new BizException("该问题的 AI 回复已经教师人工修正，不可重新生成覆盖");
            }
        }
        QuestionReplyTask task = new QuestionReplyTask();
        task.setQuestionId(questionId);
        task.setAction(QuestionReplyTask.ACTION_REGENERATE);
        task.setStatus(QuestionReplyTask.STATUS_PENDING);
        task.setAttempt(0);
        task.setOperator(teacherId);
        LocalDateTime now = LocalDateTime.now();
        task.setCreateTime(now);
        task.setUpdateTime(now);
        try {
            taskMapper.insert(task);
        } catch (DataIntegrityViolationException ex) {
            throw new BizException("该问题已有进行中的 AI 回复任务，请稍后再试");
        }
        return Map.of("taskId", String.valueOf(task.getId()));
    }

    /** 问题/回复可见性（隐藏即普通学员不可见；恢复可见） */
    @Transactional
    public Map<String, Object> setQuestionVisibility(long teacherId, List<String> roles,
                                                     long questionId, boolean visible) {
        InteractionQuestion q = requireOwnedQuestion(teacherId, roles, questionId);
        q.setHidden(visible ? InteractionReply.HIDDEN_NO : InteractionReply.HIDDEN_YES);
        q.setUpdateTime(LocalDateTime.now());
        questionMapper.updateById(q);
        auditLogger.log(teacherId, "teacher", "QA", visible ? "SHOW_QUESTION" : "HIDE_QUESTION",
                "interaction_question", questionId, "{\"hidden\":" + (visible ? 0 : 1) + "}");
        return Map.of("hidden", q.getHidden());
    }

    @Transactional
    public Map<String, Object> setReplyVisibility(long teacherId, List<String> roles,
                                                  long replyId, boolean visible) {
        InteractionReply reply = requireReply(replyId);
        requireOwnedQuestion(teacherId, roles, reply.getQuestionId());
        reply.setHidden(visible ? InteractionReply.HIDDEN_NO : InteractionReply.HIDDEN_YES);
        reply.setUpdateTime(LocalDateTime.now());
        replyMapper.updateById(reply);
        auditLogger.log(teacherId, "teacher", "QA", visible ? "SHOW_REPLY" : "HIDE_REPLY",
                "interaction_reply", replyId, "{\"hidden\":" + (visible ? 0 : 1) + "}");
        return Map.of("hidden", reply.getHidden());
    }

    /** 精华标记（问答管理能力；≠AI 采纳事件——采纳率口径用户确认维持 null） */
    @Transactional
    public Map<String, Object> feature(long teacherId, List<String> roles, long replyId, boolean on) {
        InteractionReply reply = requireReply(replyId);
        requireOwnedQuestion(teacherId, roles, reply.getQuestionId());
        InteractionReplyExtra extra = extraOf(replyId);
        extra.setFeatured(on ? 1 : 0);
        extra.setFeatureTime(on ? LocalDateTime.now() : null);
        extra.setFeatureOperator(on ? teacherId : null);
        extraMapper.updateById(extra);
        auditLogger.log(teacherId, "teacher", "QA", on ? "FEATURE_REPLY" : "UNFEATURE_REPLY",
                "interaction_reply", replyId, "{\"featured\":" + (on ? 1 : 0) + "}");
        return Map.of("featured", extra.getFeatured());
    }

    /** 教师问题详情：归属校验 + 含隐藏的完整回复树 + 来源/修正/精华元数据 + 任务状态 */
    public Map<String, Object> detail(long teacherId, List<String> roles, long questionId) {
        InteractionQuestion q = requireOwnedQuestion(teacherId, roles, questionId);
        List<InteractionReply> replies = replyMapper.selectList(
                new LambdaQueryWrapper<InteractionReply>()
                        .eq(InteractionReply::getQuestionId, questionId)
                        .orderByAsc(InteractionReply::getCreateTime));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", q);
        out.put("replies", replies.stream().map(r -> {
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
                m.put("source", extra.getSource());
                m.put("modelCode", extra.getModelCode());
                m.put("corrected", extra.getCorrected());
                m.put("featured", extra.getFeatured());
                m.put("aiTaskId", extra.getAiTaskId() == null ? null : String.valueOf(extra.getAiTaskId()));
            }
            return m;
        }).toList());
        QuestionReplyTask t = taskMapper.selectOne(
                new LambdaQueryWrapper<QuestionReplyTask>()
                        .eq(QuestionReplyTask::getQuestionId, questionId)
                        .orderByDesc(QuestionReplyTask::getCreateTime)
                        .last("LIMIT 1"));
        if (t != null) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", String.valueOf(t.getId()));
            task.put("status", t.getStatus());
            task.put("action", t.getAction());
            task.put("error", t.getError());
            task.put("attempt", t.getAttempt());
            out.put("aiTask", task);
        }
        return out;
    }

    // ------------------------------------------------------------------

    /** 教师课程范围：ADMIN 全量；教师必须拥有目标课程（courseId 指定时仅校验该课） */
    private List<Long> ownedCourseIds(long teacherId, List<String> roles, Long courseId) {
        if (roles != null && roles.contains("ADMIN")) {
            return courseId != null ? List.of(courseId) : List.of(0L);
        }
        List<Long> owned;
        try {
            var r = courseClient.byCreator(teacherId);
            owned = (r == null || r.getData() == null) ? List.of()
                    : r.getData().stream().map(CourseOutlineDTO::getId).toList();
        } catch (RuntimeException ex) {
            throw new BizException("课程服务暂不可用，无法确认课程归属");
        }
        if (courseId != null) {
            if (!owned.contains(courseId)) {
                throw new BizException("无权管理该课程的问答");
            }
            return List.of(courseId);
        }
        if (owned.isEmpty()) {
            throw new BizException("您名下暂无课程");
        }
        return owned;
    }

    private InteractionQuestion requireOwnedQuestion(long teacherId, List<String> roles, long questionId) {
        InteractionQuestion q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException("问题不存在");
        }
        if (!(roles != null && roles.contains("ADMIN"))) {
            try {
                var r = courseClient.ownership(teacherId, List.of(q.getCourseId()));
                Map<Long, Boolean> own = r == null ? null : r.getData();
                if (own == null || !Boolean.TRUE.equals(own.get(q.getCourseId()))) {
                    throw new BizException("无权管理该课程的问答");
                }
            } catch (BizException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new BizException("课程服务暂不可用，无法确认课程归属");
            }
        }
        return q;
    }

    private InteractionReply requireReply(long replyId) {
        InteractionReply reply = replyMapper.selectById(replyId);
        if (reply == null) {
            throw new BizException("回复不存在");
        }
        return reply;
    }

    private InteractionReplyExtra extraOf(long replyId) {
        InteractionReplyExtra extra = extraMapper.selectById(replyId);
        if (extra == null) {
            // 兼容历史数据（无 extra 的回复视为教师/学员来源，但不可修正 AI 字段）
            throw new BizException("回复缺少扩展元数据，无法执行该操作");
        }
        return extra;
    }

    private InteractionReply resolveRoot(InteractionQuestion q, Long targetReplyId) {
        if (targetReplyId == null) {
            return null;
        }
        InteractionReply target = replyMapper.selectById(targetReplyId);
        // Long 装箱值比较用 longValue（避免引用比较陷阱）
        if (target == null || target.getQuestionId() == null
                || target.getQuestionId().longValue() != q.getId().longValue()
                || target.getHidden() == 1) {
            throw new BizException("被回复目标不存在或不可见");
        }
        Long rootId = target.getAnswerId() == null ? target.getId() : target.getAnswerId();
        InteractionReply root = replyMapper.selectById(rootId);
        if (root == null) {
            throw new BizException("被回复目标不存在");
        }
        return root;
    }

/**
 * M15.21：校验回复属于本人教授课程（教师检查 AI 回复前的归属链：reply→question→course→teacher_course）。
 * 不属于本人课程抛 403。
 */
public void requireReplyOfTeacher(Long replyId, Long teacherId, List<String> roles) {
    com.kzai.learning.entity.InteractionReply reply = replyMapper.selectById(replyId);
    if (reply == null || (reply.getHidden() != null && reply.getHidden() == 1)) {
        throw new com.kzai.common.exception.BizException("回复不存在或已隐藏");
    }
    var question = questionMapper.selectById(reply.getQuestionId());
    if (question == null || question.getCourseId() == null) {
        throw new com.kzai.common.exception.BizException("回复所属问题不存在");
    }
    List<Long> owned = ownedCourseIds(teacherId, roles, null);
    if (!owned.contains(question.getCourseId())) {
        throw new com.kzai.common.exception.AuthException(com.kzai.common.result.ResultCode.FORBIDDEN,
                "该回复不属于本人教授课程");
    }
}

}