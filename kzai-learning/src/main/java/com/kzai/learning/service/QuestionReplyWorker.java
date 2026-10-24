package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kzai.learning.client.AigcClient;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.entity.InteractionQuestion;
import com.kzai.learning.entity.QuestionReplyTask;
import com.kzai.learning.mapper.InteractionQuestionMapper;
import com.kzai.learning.mapper.QuestionReplyTaskMapper;
import com.kzai.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 异步 AI 回复 Worker（M10，用户确认的租约方案）。
 * <p>
 * 领取（原子条件更新，配合 uk_question_active 唯一约束）：
 * <pre>
 * UPDATE ... SET status=RUNNING, worker_id=?, lease_until=now+5m, attempt=attempt+1
 * WHERE id=? AND (status=PENDING AND (next_retry_time IS NULL OR next_retry_time&lt;=now)
 *            OR  status=RUNNING AND lease_until&lt;now)   -- 租约过期才允许接管（验证所有权超时）
 * </pre>
 * 影响 1 行才视为领取成功——并发 Worker/重启双跑仅一个胜出。
 * <p>
 * 执行：调 aigc 内部 /generate（可信 Feign；以提问学员身份 RAG 过滤）→
 * {@link AiReplyTxService#completeGeneration} 单事务落库。失败退避重试（≤5），超限 FAILED；
 * 任务持久化于 DB，服务重启后 PENDING/RUNNING（租约过期）自动恢复，不丢任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionReplyWorker {

    private final QuestionReplyTaskMapper taskMapper;
    private final InteractionQuestionMapper questionMapper;
    private final CourseClient courseClient;
    private final AigcClient aigcClient;
    private final AiReplyTxService txService;

    private final String workerId = initWorkerId();

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        LocalDateTime now = LocalDateTime.now();
        // 1) 可领取任务：PENDING（重试时间到）；2) 租约过期的 RUNNING（接管）
        List<QuestionReplyTask> candidates = taskMapper.selectList(
                new LambdaQueryWrapper<QuestionReplyTask>()
                        .and(w -> w
                                .and(w2 -> w2.eq(QuestionReplyTask::getStatus, QuestionReplyTask.STATUS_PENDING)
                                        .and(w3 -> w3.isNull(QuestionReplyTask::getNextRetryTime)
                                                .or().le(QuestionReplyTask::getNextRetryTime, now)))
                                .or(w2 -> w2.eq(QuestionReplyTask::getStatus, QuestionReplyTask.STATUS_RUNNING)
                                        .isNotNull(QuestionReplyTask::getLeaseUntil)
                                        .lt(QuestionReplyTask::getLeaseUntil, now)))
                        .orderByAsc(QuestionReplyTask::getCreateTime)
                        .last("LIMIT 3"));
        for (QuestionReplyTask t : candidates) {
            if (tryClaim(t, now)) {
                execute(t.getId());
            }
        }
    }

    /** 原子领取：条件更新影响 1 行才算成功（防并发双领） */
    private boolean tryClaim(QuestionReplyTask t, LocalDateTime now) {
        QuestionReplyTask upd = new QuestionReplyTask();
        upd.setStatus(QuestionReplyTask.STATUS_RUNNING);
        upd.setWorkerId(workerId);
        upd.setLeaseUntil(now.plusMinutes(QuestionReplyTask.LEASE_MINUTES));
        upd.setAttempt((t.getAttempt() == null ? 0 : t.getAttempt()) + 1);
        upd.setUpdateTime(now);
        boolean claimed;
        if (t.getStatus() == QuestionReplyTask.STATUS_PENDING) {
            claimed = taskMapper.update(upd,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<QuestionReplyTask>()
                            .eq(QuestionReplyTask::getId, t.getId())
                            .eq(QuestionReplyTask::getStatus, QuestionReplyTask.STATUS_PENDING)
                            .and(w -> w.isNull(QuestionReplyTask::getNextRetryTime)
                                    .or().le(QuestionReplyTask::getNextRetryTime, now))) == 1;
        } else {
            // 接管过期租约：必须仍然匹配过期租约条件（所有权已失效）
            claimed = taskMapper.update(upd,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<QuestionReplyTask>()
                            .eq(QuestionReplyTask::getId, t.getId())
                            .eq(QuestionReplyTask::getStatus, QuestionReplyTask.STATUS_RUNNING)
                            .lt(QuestionReplyTask::getLeaseUntil, now)) == 1;
        }
        if (claimed) {
            if (t.getStartedAt() == null) {
                QuestionReplyTask mark = new QuestionReplyTask();
                mark.setId(t.getId());
                mark.setStartedAt(now);
                taskMapper.updateById(mark);
            }
            log.info("[qa.task] 领取 task={} question={} attempt={} worker={}",
                    t.getId(), t.getQuestionId(), upd.getAttempt(), workerId);
        }
        return claimed;
    }

    private void execute(long taskId) {
        QuestionReplyTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        try {
            InteractionQuestion q = questionMapper.selectById(task.getQuestionId());
            if (q == null) {
                txService.markFailed(taskId, "问题不存在（可能已被清理）");
                return;
            }
            String courseName = null;
            String chapterTitle = null;
            try {
                if (q.getCourseId() != null) {
                    R<CourseOutlineDTO> r = courseClient.learnOutline(q.getCourseId());
                    CourseOutlineDTO outline = r == null ? null : r.getData();
                    if (outline != null) {
                        courseName = outline.getName();
                        if (q.getChapterId() != null && outline.getChapters() != null) {
                            chapterTitle = outline.getChapters().stream()
                                    .filter(c -> q.getChapterId().equals(c.getId()))
                                    .map(com.kzai.learning.dto.LearnChapterDTO::getName)
                                    .findFirst().orElse(null);
                        }
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("[qa.task] 课程上下文获取失败（不阻断生成）: {}", ex.getMessage());
            }

            Map<String, Object> body = Map.of(
                    "askerUserId", q.getUserId(),
                    "askerRoles", List.of("STUDENT"),
                    "title", q.getTitle() == null ? "" : q.getTitle(),
                    "description", q.getDescription() == null ? "" : q.getDescription(),
                    "courseName", courseName == null ? "" : courseName,
                    "chapterTitle", chapterTitle == null ? "" : chapterTitle);
            R<Map<String, Object>> resp = aigcClient.generate(body);
            Map<String, Object> data = resp == null ? null : resp.getData();
            if (data == null || data.get("content") == null
                    || String.valueOf(data.get("content")).isBlank()) {
                txService.markFailed(taskId, "AI 服务返回空内容");
                return;
            }
            txService.completeGeneration(taskId,
                    String.valueOf(data.get("content")),
                    String.valueOf(data.getOrDefault("modelCode", "UNKNOWN")),
                    data.getOrDefault("sources", List.of()));
        } catch (RuntimeException ex) {
            // aigc 不可用/超时/限流/无 Key 失败：明确失败状态 + 退避重试
            log.warn("[qa.task] 生成失败 task={}: {}", taskId, ex.getMessage());
            txService.markFailed(taskId, ex.getMessage());
        }
    }

    private static String initWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception ex) {
            return "worker-" + ProcessHandle.current().pid();
        }
    }
}
