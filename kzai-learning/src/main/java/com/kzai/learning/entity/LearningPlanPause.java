package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次暂停到恢复的区间。区间内首次完成的章节不计入当周目标。 */
@Data
@TableName("learning_plan_pause")
public class LearningPlanPause {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long planId;
    private LocalDateTime pausedAt;
    private LocalDateTime resumedAt;
    private LocalDateTime createTime;
}
