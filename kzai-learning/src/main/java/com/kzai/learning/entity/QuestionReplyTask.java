package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步 AI 回复任务（M10 扩展表，用户批准含租约调整）。
 * <p>
 * 并发与可靠性（用户确认方案）：
 * <ul>
 *   <li>生成列 active_flag（进行中=1 否则 NULL）+ UNIQUE(question_id, active_flag)
 *       ——同一问题同时仅一个进行中任务（DB 级防重复生成/并发重生成/重启双跑）；</li>
 *   <li>领取 = 原子条件更新（status=PENDING 或 租约过期 RUNNING → RUNNING + worker_id + lease_until）；
 *       超时接管必须验证租约已过期；</li>
 *   <li>失败退避重试 attempt≤5（next_retry_time），超限 FAILED 落库原因；</li>
 *   <li>同一任务最多对应一条成功回复（reply_id 唯一回填，事务一致）。</li>
 * </ul>
 */
@Data
@TableName("ai_question_reply_task")
public class QuestionReplyTask {

    public static final String ACTION_GENERATE = "GENERATE";
    public static final String ACTION_REGENERATE = "REGENERATE";

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    public static final int MAX_ATTEMPT = 5;
    /** 租约时长（分钟）：超时未完成允许其他 Worker 安全接管 */
    public static final int LEASE_MINUTES = 5;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long questionId;
    private String action;
    private Long replyId;
    private Integer status;
    private Integer attempt;
    private String error;
    private Long operator;
    private String workerId;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextRetryTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
