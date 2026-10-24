package com.kzai.aigc.supervision.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * M13 AI 会话监管复核（sql/14_m13_management.sql，用户批准）。
 * 只插入不覆盖：同一会话多次标记/处理各成一行，复核历史完整可追溯；
 * 不存消息正文（正文仅在 Redis，过期即如实显示"消息内容已过期或不可用"）。
 */
@Data
@TableName("sys_session_review")
public class SysSessionReview {

    public static final int ST_PENDING = 0;
    public static final int ST_HANDLED_OK = 1;
    public static final int ST_HANDLED_VIOLATION = 2;
    public static final int ST_HANDLED_OTHER = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String sessionId;
    private Long userId;
    private Integer status;
    private String reason;
    private String handleNote;
    private Long markedBy;
    private String markedByName;
    private LocalDateTime markedAt;
    private Long handledBy;
    private String handledByName;
    private LocalDateTime handledAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
