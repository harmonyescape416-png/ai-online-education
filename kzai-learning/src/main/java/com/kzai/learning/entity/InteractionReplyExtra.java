package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 互动回复扩展元数据（M10 扩展表，与 interaction_reply 1:1）。
 * <p>
 * 来源标识（source）：SYSTEM_AI/TEACHER/STUDENT——解决 is_student=0 无法区分 AI 与教师的原始局限。
 * 修正审计：prev_content 存最近一次修正前的完整内容；完整修正历史链由
 * sys_operation_log（每次修正一条，含前后内容 JSON 摘要）承担（detail 列 512 的限制见执行报告）。
 * featured 仅是精华标记，<b>不等同于 AI 采纳事件</b>（学情 aiAdoptRate 维持 null，用户确认口径）。
 */
@Data
@TableName("interaction_reply_extra")
public class InteractionReplyExtra {

    public static final String SOURCE_SYSTEM_AI = "SYSTEM_AI";
    public static final String SOURCE_TEACHER = "TEACHER";
    public static final String SOURCE_STUDENT = "STUDENT";

    public static final String MODEL_MOCK = "MOCK";
    public static final String MODEL_DASHSCOPE = "DASHSCOPE";

    @TableId(type = IdType.INPUT)
    private Long replyId;
    private Long questionId;
    private String source;
    private Long aiTaskId;
    private String modelCode;
    private Integer corrected;
    private Long corrector;
    /** M15.20-B：AI 回复教师检查状态（0 待检查/1 已检查） */
    private Integer aiChecked;
    /** M15.20-B：学员采纳标记（1=有帮助） */
    private Integer helpful;
    private LocalDateTime correctTime;
    private String prevContent;
    private Integer featured;
    private LocalDateTime featureTime;
    private Long featureOperator;
}
