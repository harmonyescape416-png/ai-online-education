package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.exception.BizException;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.QuestionCreateRequest;
import com.kzai.learning.dto.QuestionReplyRequest;
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
 * 学员课程问答（M10）。
 * <p>
 * 约束（任务书第五/八部分）：
 * <ul>
 *   <li>提问者身份一律取服务端登录用户（不接收前端 userId，防冒充）；</li>
 *   <li>课程必须存在且对学员可见（上架），章节必须归属该课程；</li>
 *   <li>关联受保护章节的提问复用学习权益校验（EntitlementService）；</li>
 *   <li>status 保持原始语义（0未查看/1已查看），AI 状态在任务表；</li>
 *   <li>answer_times 初始 0、latest_answer_id 空；成功落库后才触发异步 AI 任务；</li>
 *   <li>任务经 DB 唯一约束（同一问题仅一个进行中）保证不重复、不丢失（持久化任务表，
 *       不依赖不可靠的内存/消息投递）；</li>
 *   <li>追问正确使用 answer_id/target_reply_id/target_user_id 原始字段并校验关联；</li>
 *   <li>隐藏问题/回复对普通学员不可见。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionService {

    private final InteractionQuestionMapper questionMapper;
    private final InteractionReplyMapper replyMapper;
    private final InteractionReplyExtraMapper extraMapper;
    private final QuestionReplyTaskMapper taskMapper;
    private final CourseClient courseClient;
    private final EntitlementService entitlementService;
    private final NotificationOutboxService notificationOutbox;
    private final com.kzai.learning.points.PointService pointService;

    /** 提问（课程/章节/权益校验 + 落库 + 入队异步 AI 任务） */
    @Transactional
    public Map<String, Object> createQuestion(long userId, QuestionCreateRequest req) {
        validateAndAuthorize(userId, req.getCourseId(), req.getChapterId());

        InteractionQuestion q = new InteractionQuestion();
        q.setTitle(req.getTitle().trim());
        q.setDescription(req.getDescription().trim());
        q.setCourseId(req.getCourseId());
        q.setChapterId(req.getChapterId());
        q.setSectionId(req.getSectionId());
        q.setUserId(userId);
        q.setLatestAnswerId(null);
        q.setAnswerTimes(0);
        q.setAnonymity(req.getAnonymity() != null && req.getAnonymity() == 1 ? 1 : 0);
        q.setHidden(InteractionReply.HIDDEN_NO);
        q.setStatus(InteractionQuestion.STATUS_UNVIEWED);
        LocalDateTime now = LocalDateTime.now();
        q.setCreateTime(now);
        q.setUpdateTime(now);
        questionMapper.insert(q);
        pointService.award(userId, "QUESTION", "QUESTION:" + q.getId(),
                com.kzai.learning.stats.StatsWindow.businessDate(now));

        // 问题已真实落库 → 创建持久化异步任务（DB 唯一约束防重复；Worker 轮询恢复，不丢任务）
        QuestionReplyTask task = new QuestionReplyTask();
        task.setQuestionId(q.getId());
        task.setAction(QuestionReplyTask.ACTION_GENERATE);
        task.setStatus(QuestionReplyTask.STATUS_PENDING);
        task.setAttempt(0);
        task.setOperator(userId);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        try {
            taskMapper.insert(task);
        } catch (DataIntegrityViolationException ex) {
            // uk_question_active：同一问题已有进行中任务——落库成功但任务幂等拒绝
            throw new BizException("该问题已有进行中的 AI 回复任务，请勿重复提交");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questionId", q.getId());
        out.put("taskId", task.getId());
        out.put("taskStatus", "PENDING");
        return out;
    }

    /** 我的问答（含每问回复数；按 updateTime 倒序分页） */
    public Map<String, Object> mine(long userId, long page, long size) {
        Page<InteractionQuestion> p = questionMapper.selectPage(new Page<>(page, Math.min(size, 50)),
                new LambdaQueryWrapper<InteractionQuestion>()
                        .eq(InteractionQuestion::getUserId, userId)
                        .orderByDesc(InteractionQuestion::getUpdateTime));
        return pageOf(p);
    }

    /** 课程问答区（普通学员：过滤隐藏；教师/管理员可见全部由教师接口承担） */
    public Map<String, Object> courseQuestions(long viewerId, List<String> roles,
                                               long courseId, long page, long size) {
        boolean staff = roles != null && (roles.contains("ADMIN"));
        Page<InteractionQuestion> p = questionMapper.selectPage(new Page<>(page, Math.min(size, 50)),
                new LambdaQueryWrapper<InteractionQuestion>()
                        .eq(InteractionQuestion::getCourseId, courseId)
                        .eq(!staff, InteractionQuestion::getHidden, 0)
                        .orderByDesc(InteractionQuestion::getCreateTime));
        return pageOf(p);
    }

    /** 问题详情 + 回复树（学员视角过滤隐藏；匿名脱敏） */
    public Map<String, Object> detail(long viewerId, List<String> roles, long questionId) {
        InteractionQuestion q = requireQuestion(questionId);
        boolean staff = roles != null && roles.contains("ADMIN");
        boolean owner = q.getUserId() != null && q.getUserId() == viewerId;
        if (q.getHidden() == 1 && !staff && !owner) {
            throw new BizException("该问题已隐藏或不存在");
        }
        List<InteractionReply> replies = replyMapper.selectList(
                new LambdaQueryWrapper<InteractionReply>()
                        .eq(InteractionReply::getQuestionId, questionId)
                        .orderByAsc(InteractionReply::getCreateTime));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", questionVO(q, viewerId));
        // 隐藏回复仅教师/管理端可见（问题 owner 与普通学员一致，不得看到隐藏内容）
        out.put("replies", replies.stream()
                .filter(r -> r.getHidden() == 0 || staff)
                .map(r -> replyVO(r, viewerId))
                .toList());
        out.put("aiTask", latestTaskVO(questionId, staff || owner));
        return out;
    }

    /** 学员追问/回复（target 校验：必须属于同一问题且可见） */
    @Transactional
    public Map<String, Object> reply(long userId, long questionId, QuestionReplyRequest req) {
        InteractionQuestion q = requireQuestion(questionId);
        if (q.getHidden() == 1) {
            throw new BizException("该问题已隐藏，无法回复");
        }
        InteractionReply root = resolveRoot(q, req.getTargetReplyId());
        InteractionReply created = insertReply(userId, q, root, req.getTargetReplyId(),
                root == null ? null : root.getUserId(),
                req.getContent().trim(),
                req.getAnonymity() != null && req.getAnonymity() == 1,
                InteractionReplyExtra.SOURCE_STUDENT, null, null);
        bumpReplyCount(q.getId());
        // M11：同一事务内入队通知事件（业务回滚则事件不产生）
        // 收件人：追问→被追问回复作者（AI 回复不重复通知）；直接回答→问题提出者；本人不通知
        Long recipient = root != null ? root.getUserId() : null;
        if (recipient == null || recipient == AiReplyTxService.AI_SYSTEM_USER_ID
                || recipient.longValue() == userId) {
            recipient = null;
        }
        if (recipient == null && root == null) {
            Long owner = q.getUserId();
            if (owner != null && owner.longValue() != userId) {
                recipient = owner;
            }
        }
        if (recipient != null) {
            notificationOutbox.enqueue("QUESTION_REPLY:" + created.getId(), "QA",
                    String.valueOf(q.getId()), recipient, "QUESTION_REPLY",
                    root != null ? "你的回复收到了新追问" : "你的问题有了新回答",
                    NotificationTexts.summary(created.getContent()),
                    NotificationTexts.payload(q.getId(), created.getId()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("replied", true);
        return out;
    }

    // ------------------------------------------------------------------

    /**
     * 学员能否查看并反馈这条回复。复用提问时的课程可见性：
     * 课程级问题不要求购买；关联章节的问题沿用学习权益。隐藏的问题或回复不可见。
     */
    public void assertReplyFeedbackAllowed(long userId, long replyId) {
        InteractionReply reply = replyMapper.selectById(replyId);
        if (reply == null || (reply.getHidden() != null && reply.getHidden() == InteractionReply.HIDDEN_YES)) {
            throw new BizException("回复不存在或已隐藏");
        }
        InteractionQuestion question = questionMapper.selectById(reply.getQuestionId());
        if (question == null || (question.getHidden() != null && question.getHidden() == 1)) {
            throw new BizException("问题不存在或已隐藏");
        }
        validateAndAuthorize(userId, question.getCourseId(), question.getChapterId());
    }

    /** 课程可见性 + 章节归属 + 受保护章节权益（单次 learnOutline 完成三项校验） */
    private void validateAndAuthorize(long userId, Long courseId, Long chapterId) {
        if (courseId == null) {
            throw new BizException("必须指定课程");
        }
        CourseOutlineDTO outline;
        try {
            var r = courseClient.learnOutline(courseId);
            outline = r == null ? null : r.getData();
        } catch (RuntimeException ex) {
            throw new BizException("课程服务暂不可用，请稍后再试");
        }
        if (outline == null || outline.getId() == null) {
            throw new BizException("课程不存在或已下架");
        }
        // M15.12-R1 安全修复：课程状态以课程服务权威快照为准（course.learnOutline 已回填
        // status/categoryEnabled/studentVisible）——下架课程、禁用分类课程、学员不可见课程
        // 一律拒绝提问（fail-closed）；课程服务数据为准，不信任前端。
        // 保持原有课程级提问不要求购买权益的规则不变（权益校验仅在章节关联提问）。
        if (!Integer.valueOf(2).equals(outline.getStatus())
                || !Boolean.TRUE.equals(outline.getCategoryEnabled())
                || !Boolean.TRUE.equals(outline.getStudentVisible())) {
            throw new BizException("课程不存在或已下架");
        }
        if (chapterId != null) {
            boolean belongs = outline.getChapters() != null && outline.getChapters().stream()
                    .anyMatch(c -> chapterId.equals(c.getId()));
            if (!belongs) {
                throw new BizException("章节与课程不匹配");
            }
            // 章节关联提问涉及受保护学习内容：复用学习权益校验（fail-closed）
            var decision = entitlementService.resolve(userId, outline);
            if (decision == null || !Boolean.TRUE.equals(decision.getAllowed())) {
                throw new BizException("暂无该章节对应课程的学习权益，无法在此提问");
            }
        }
    }

    private InteractionQuestion requireQuestion(long questionId) {
        InteractionQuestion q = questionMapper.selectById(questionId);
        if (q == null || q.getHidden() == null) {
            throw new BizException("问题不存在");
        }
        return q;
    }

    /** 解析追问根：targetReplyId 必须属于本问题且未隐藏；根回复（无 target）返回其 answerId 对应根 */
    private InteractionReply resolveRoot(InteractionQuestion q, Long targetReplyId) {
        if (targetReplyId == null) {
            return null;
        }
        InteractionReply target = replyMapper.selectById(targetReplyId);
        // Long 装箱值比较必须用 equals/longValue（!= 会变成引用比较）
        if (target == null || target.getQuestionId() == null
                || target.getQuestionId().longValue() != q.getId().longValue()
                || target.getHidden() == 1) {
            log.warn("[qa.reply] 追问目标校验失败 target={} found={} targetQ={} q={} hidden={}",
                    targetReplyId, target != null,
                    target == null ? null : target.getQuestionId(), q.getId(), target == null ? null : target.getHidden());
            throw new BizException("被追问的回复不存在或不可见");
        }
        Long rootId = target.getAnswerId() == null ? target.getId() : target.getAnswerId();
        InteractionReply root = replyMapper.selectById(rootId);
        if (root == null) {
            throw new BizException("被追问的回复不存在");
        }
        return root;
    }

    /** 统一回复落库（学员追问/AI/教师共用；answer_id 指向回复链根） */
    InteractionReply insertReply(long authorId, InteractionQuestion q, InteractionReply root,
                                 Long targetReplyId, Long targetUserId, String content,
                                 boolean anonymity, String source, Long aiTaskId, String modelCode) {
        LocalDateTime now = LocalDateTime.now();
        InteractionReply r = new InteractionReply();
        r.setQuestionId(q.getId());
        r.setUserId(authorId);
        r.setContent(content);
        r.setTargetReplyId(targetReplyId);
        r.setTargetUserId(targetUserId);
        r.setReplyTimes(0);
        r.setLikedTimes(0);
        r.setHidden(InteractionReply.HIDDEN_NO);
        r.setAnonymity(anonymity ? 1 : 0);
        r.setIsStudent(InteractionReplyExtra.SOURCE_STUDENT.equals(source)
                ? InteractionReply.IS_STUDENT_YES : InteractionReply.IS_STUDENT_NO);
        r.setCreateTime(now);
        r.setUpdateTime(now);
        replyMapper.insert(r);
        r.setAnswerId(root != null ? root.getId() : r.getId());
        replyMapper.updateById(r);

        InteractionReplyExtra extra = new InteractionReplyExtra();
        extra.setReplyId(r.getId());
        extra.setQuestionId(q.getId());
        extra.setSource(source);
        extra.setAiTaskId(aiTaskId);
        extra.setModelCode(modelCode);
        extra.setCorrected(0);
        extra.setFeatured(0);
        extraMapper.insert(extra);
        return r;
    }

    /** answer_times 乐观自增（防并发丢失更新；latest_answer_id 由事务服务显式管理） */
    void bumpReplyCount(long questionId) {
        InteractionQuestion q = questionMapper.selectById(questionId);
        if (q == null) {
            return;
        }
        InteractionQuestion upd = new InteractionQuestion();
        upd.setId(questionId);
        upd.setAnswerTimes((q.getAnswerTimes() == null ? 0 : q.getAnswerTimes()) + 1);
        upd.setUpdateTime(LocalDateTime.now());
        questionMapper.updateById(upd);
    }

    private Map<String, Object> pageOf(Page<InteractionQuestion> p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.getTotal());
        out.put("records", p.getRecords().stream().map(q -> questionVO(q, null)).toList());
        return out;
    }

    private Map<String, Object> questionVO(InteractionQuestion q, Long viewerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(q.getId()));
        m.put("title", q.getTitle());
        m.put("description", q.getDescription());
        m.put("courseId", q.getCourseId() == null ? null : String.valueOf(q.getCourseId()));
        m.put("chapterId", q.getChapterId() == null ? null : String.valueOf(q.getChapterId()));
        m.put("answerTimes", q.getAnswerTimes());
        m.put("latestAnswerId", q.getLatestAnswerId() == null ? null : String.valueOf(q.getLatestAnswerId()));
        m.put("status", q.getStatus());
        m.put("anonymity", q.getAnonymity());
        m.put("hidden", q.getHidden());
        m.put("createTime", q.getCreateTime());
        // 匿名仅影响展示：作者 id/名称对非本人且非管理端隐藏
        boolean reveal = q.getAnonymity() == null || q.getAnonymity() == 0
                || (viewerId != null && q.getUserId() != null && q.getUserId() == viewerId);
        m.put("userId", reveal ? String.valueOf(q.getUserId()) : null);
        return m;
    }

    private Map<String, Object> replyVO(InteractionReply r, Long viewerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r.getId()));
        m.put("answerId", r.getAnswerId() == null ? null : String.valueOf(r.getAnswerId()));
        m.put("content", r.getContent());
        m.put("targetReplyId", r.getTargetReplyId() == null ? null : String.valueOf(r.getTargetReplyId()));
        m.put("isStudent", r.getIsStudent());
        m.put("anonymity", r.getAnonymity());
        m.put("hidden", r.getHidden());
        m.put("createTime", r.getCreateTime());
        InteractionReplyExtra extra = extraMapper.selectById(r.getId());
        if (extra != null) {
            m.put("source", extra.getSource());
            m.put("modelCode", extra.getModelCode());
            m.put("corrected", extra.getCorrected());
            m.put("featured", extra.getFeatured());
        }
        boolean reveal = r.getAnonymity() == null || r.getAnonymity() == 0
                || (viewerId != null && r.getUserId() != null && r.getUserId() == viewerId);
        m.put("userId", reveal ? String.valueOf(r.getUserId()) : null);
        return m;
    }

    private Map<String, Object> latestTaskVO(long questionId, boolean include) {
        if (!include) {
            return null;
        }
        QuestionReplyTask t = taskMapper.selectOne(
                new LambdaQueryWrapper<QuestionReplyTask>()
                        .eq(QuestionReplyTask::getQuestionId, questionId)
                        .orderByDesc(QuestionReplyTask::getCreateTime)
                        .last("LIMIT 1"));
        if (t == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(t.getId()));
        m.put("status", t.getStatus());
        m.put("action", t.getAction());
        m.put("error", t.getError());
        m.put("attempt", t.getAttempt());
        return m;
    }
}
