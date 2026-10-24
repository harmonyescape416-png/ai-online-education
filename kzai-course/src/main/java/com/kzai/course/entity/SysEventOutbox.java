package com.kzai.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知事件 Outbox（M11，course 库本地表，与课程审核业务<b>同库同事务</b>）。
 * <p>
 * 与 learning 侧同结构：业务事务内写入 → 本地 Worker 原子领取（租约）→ 投递 user 服务；
 * 审核驳回/失败不会产生本行（事件只在真实状态变更后产生）。
 */
@Data
@TableName("sys_event_outbox")
public class SysEventOutbox {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENDING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    public static final int MAX_ATTEMPT = 5;
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
