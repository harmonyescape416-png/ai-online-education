package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程互动问题（M10，原始 14 字段零增删）。
 * <p>
 * 语义红线（任务书第三部分）：status 仅保留原始含义 0未查看/1已查看；
 * AI 生成状态由 {@link QuestionReplyTask} 承担，与本表解耦。
 */
@Data
@TableName("interaction_question")
public class InteractionQuestion {

    public static final int STATUS_UNVIEWED = 0;
    public static final int STATUS_VIEWED = 1;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String title;
    private String description;
    private Long courseId;
    private Long chapterId;
    private Long sectionId;
    /** 提问者（服务端取登录身份，匿名仅影响展示） */
    private Long userId;
    private Long latestAnswerId;
    private Integer answerTimes;
    /** 匿名：0实名 1匿名（仅展示层生效） */
    private Integer anonymity;
    /** 隐藏：0否 1是（隐藏后普通学员列表不可见） */
    private Integer hidden;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
