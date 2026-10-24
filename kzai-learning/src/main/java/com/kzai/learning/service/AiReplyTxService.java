package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 回复事务落库（M10，用户确认的可靠性方案）。
 * <p>
 * <b>单一事务</b>：插入回复 → 写扩展元数据 → 更新问题聚合（latest_answer_id/answer_times）
 * → 任务置 SUCCESS（回填 reply_id）。任一步失败全部回滚，绝不出现「回复已生成但任务仍待处理」
 * 或「计数与回复不一致」。同一任务最多对应一条成功回复（reply_id 幂等回填）。
 * <p>
 * 计数一致性：answer_times 读取后 +1 并在<b>同一事务</b>内提交；
 * 教师回复与 AI 回复并发时由行级锁（UPDATE 主键）串行化，不丢失更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReplyTxService {

    public static final long AI_SYSTEM_USER_ID = 9999L;

    private final InteractionQuestionMapper questionMapper;
    private final NotificationOutboxService notificationOutbox;
    private final InteractionReplyMapper replyMapper;
    private final InteractionReplyExtraMapper extraMapper;
    private final QuestionReplyTaskMapper taskMapper;
    private final com.kzai.learning.util.OperationAuditLogger auditLogger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 生成成功：单一事务落库（task → reply → extra → question 聚合）；sources 仅作审计 JSON（List/Map 均可） */
    @Transactional
    public void completeGeneration(long taskId, String content, String modelCode,
                                   Object sources) {
        QuestionReplyTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        // 幂等：任务已成功（可能由接管 Worker 重复回调）→ 不再插入第二条回复
        if (task.getStatus() == QuestionReplyTask.STATUS_SUCCESS && task.getReplyId() != null) {
            log.info("[qa.task] 任务已完成，跳过重复落库 task={} reply={}", taskId, task.getReplyId());
            return;
        }
        InteractionQuestion q = questionMapper.selectById(task.getQuestionId());
        if (q == null) {
            throw new IllegalStateException("问题不存在 task=" + taskId);
        }

        LocalDateTime now = LocalDateTime.now();
        InteractionReply reply = new InteractionReply();
        reply.setQuestionId(q.getId());
        reply.setUserId(AI_SYSTEM_USER_ID); // 经核验的系统 AI 账号（ai_bot），is_student=0
        reply.setContent(content);
        reply.setReplyTimes(0);
        reply.setLikedTimes(0);
        reply.setHidden(InteractionReply.HIDDEN_NO);
        reply.setAnonymity(0);
        reply.setIsStudent(InteractionReply.IS_STUDENT_NO);
        reply.setCreateTime(now);
        reply.setUpdateTime(now);
        replyMapper.insert(reply);
        reply.setAnswerId(reply.getId()); // 回答链根 = 自身
        replyMapper.updateById(reply);

        InteractionReplyExtra extra = new InteractionReplyExtra();
        extra.setReplyId(reply.getId());
        extra.setQuestionId(q.getId());
        extra.setSource(InteractionReplyExtra.SOURCE_SYSTEM_AI);
        extra.setAiTaskId(taskId);
        extra.setModelCode(modelCode);
        extra.setCorrected(0);
        extra.setFeatured(0);
        extraMapper.insert(extra);

        // 生成来源审计（sys_operation_log：AI 回复事件，含来源文档 JSON 摘要）
        auditLogger.log(AI_SYSTEM_USER_ID, "ai_bot", "QA", "AI_REPLY",
                "interaction_reply", reply.getId(),
                toJsonSummary(Map.of("questionId", String.valueOf(q.getId()),
                        "modelCode", String.valueOf(modelCode), "sources", sources)));

        // 问题聚合（同一事务；latest_answer_id 指向真实回复）
        InteractionQuestion upd = new InteractionQuestion();
        upd.setId(q.getId());
        upd.setLatestAnswerId(reply.getId());
        upd.setAnswerTimes((q.getAnswerTimes() == null ? 0 : q.getAnswerTimes()) + 1);
        upd.setUpdateTime(now);
        questionMapper.updateById(upd);

        task.setReplyId(reply.getId());
        task.setStatus(QuestionReplyTask.STATUS_SUCCESS);
        task.setFinishedAt(now);
        task.setUpdateTime(now);
        taskMapper.updateById(task);

        // M11：AI 回复真实落库且任务进入 SUCCESS 后，才在同一事务内入队通知事件
        // （失败/重试中/回滚不会有成功通知；MOCK 来源在标题与正文中显著标识）
        if (q.getUserId() != null) {
            notificationOutbox.enqueue("AI_REPLY:" + reply.getId(), "QA",
                    String.valueOf(q.getId()), q.getUserId(), "AI_REPLY",
                    NotificationTexts.aiReplyTitle(modelCode),
                    NotificationTexts.aiReplyContent(modelCode, content),
                    NotificationTexts.payload(q.getId(), reply.getId()));
        }
    }

    /** 任务失败：attempt 已在领取时递增；超限置 FAILED（保留失败原因），否则退避重试 */
    @Transactional
    public void markFailed(long taskId, String error) {
        QuestionReplyTask task = taskMapper.selectById(taskId);
        if (task == null || task.getStatus() == QuestionReplyTask.STATUS_SUCCESS) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        task.setError(error == null ? "未知错误" : truncate(error, 480));
        task.setWorkerId(null);
        task.setLeaseUntil(null);
        if (task.getAttempt() != null && task.getAttempt() >= QuestionReplyTask.MAX_ATTEMPT) {
            task.setStatus(QuestionReplyTask.STATUS_FAILED);
            task.setFinishedAt(now);
            log.warn("[qa.task] 任务最终失败 task={} question={} error={}",
                    taskId, task.getQuestionId(), task.getError());
        } else {
            task.setStatus(QuestionReplyTask.STATUS_PENDING);
            long backoffMinutes = 1L << Math.min(task.getAttempt() == null ? 1 : task.getAttempt(), 5);
            task.setNextRetryTime(now.plusMinutes(backoffMinutes));
        }
        task.setUpdateTime(now);
        taskMapper.updateById(task);
    }

    /** 生成来源审计 JSON 摘要（截断到 480，超出部分截断说明见报告） */
    private String toJsonSummary(Object data) {
        try {
            return truncate(objectMapper.writeValueAsString(data), 480);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
