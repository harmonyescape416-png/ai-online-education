package com.kzai.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知（M11）。
 * <p>
 * 幂等由 {@code UNIQUE(recipient_id, event_key)} 保证：同一业务事件向同一接收者
 * 至多生成一条通知（重复投递/Feign 重试只命中同一行）。
 * <p>
 * 归属由 {@code recipient_id} 单一来源决定，Service 层只使用 JWT 解析出的可信用户身份，
 * 不信任客户端传入的 recipientId。
 */
@Data
@TableName("sys_notification")
public class SysNotification {

    /** 已读 */
    public static final int READ = 1;
    /** 未读 */
    public static final int UNREAD = 0;
    /** 正常（未删除） */
    public static final int NOT_DELETED = 0;
    /** 接收者逻辑删除 */
    public static final int DELETED = 1;

    public static final String TYPE_AI_REPLY = "AI_REPLY";
    public static final String TYPE_QUESTION_REPLY = "QUESTION_REPLY";
    public static final String TYPE_COURSE_APPROVED = "COURSE_APPROVED";

    public static final String BIZ_QA = "QA";
    public static final String BIZ_COURSE = "COURSE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long recipientId;
    private String eventKey;
    private String type;
    private String title;
    private String content;
    private String bizType;
    private String bizId;
    private Integer readStatus;
    private LocalDateTime readTime;
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
