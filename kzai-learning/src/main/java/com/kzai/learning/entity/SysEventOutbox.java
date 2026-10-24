package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知事件 Outbox（M11，本地表，与课程问答业务<b>同库同事务</b>）。
 * <p>
 * 可靠投递语义：
 * <ul>
 *   <li>业务事务内插入本表（业务回滚 → 事件行不产生，绝不会出现"业务失败但有通知"）；</li>
 *   <li>由 {@code NotificationOutboxWorker} 原子领取（条件更新 + 租约）后投递到 user 服务；</li>
 *   <li>投递失败保留行并指数退避重试（≤5 次），服务重启后自动恢复；</li>
 *   <li>user 侧 {@code UNIQUE(recipient_id, event_key)} 保证重复投递不重复生成通知；</li>
 *   <li>通知失败<b>永不</b>触发业务回滚或重复生成回复（业务与通知解耦）。</li>
 * </ul>
 */
@Data
@TableName("sys_event_outbox")
public class SysEventOutbox {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENDING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    /** 最大尝试次数（超限置失败，人工/补偿处理） */
    public static final int MAX_ATTEMPT = 5;
    /** 租约时长（分钟）：超过未完成的 SENDING 可被接管 */
    public static final int LEASE_MINUTES = 5;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String eventKey;
    private String bizType;
    private String bizId;
    private Long recipientId;
    private String type;
    private String title;
    private String content;
    private String payloadJson;
    private Integer status;
    private Integer attempt;
    private LocalDateTime nextRetryTime;
    private String workerId;
    private LocalDateTime leaseUntil;
    private String error;
    private String requestId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
