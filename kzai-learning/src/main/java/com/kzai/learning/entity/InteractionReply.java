package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程互动回复（M10，原始 14 字段零增删）。
 * <p>
 * is_student=0 表示非学员回答（AI 或教师），来源区分由
 * {@link InteractionReplyExtra} 的 source 元数据承担，不改动本表语义。
 * 追问链：answer_id=所属回答根、target_reply_id/target_user_id=被追问对象。
 */
@Data
@TableName("interaction_reply")
public class InteractionReply {

    public static final int HIDDEN_NO = 0;
    public static final int HIDDEN_YES = 1;
    public static final int IS_STUDENT_YES = 1;
    public static final int IS_STUDENT_NO = 0;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long questionId;
    /** 所属回答根（追问链根回复 id；根回复自身=本条 id） */
    private Long answerId;
    /** 回答作者（学员/教师/经验证的系统 AI 账号 9999） */
    private Long userId;
    private String content;
    private Long targetUserId;
    private Long targetReplyId;
    private Integer replyTimes;
    private Integer likedTimes;
    private Integer hidden;
    private Integer anonymity;
    /** 0=非学员（AI/教师） 1=学员 */
    private Integer isStudent;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
