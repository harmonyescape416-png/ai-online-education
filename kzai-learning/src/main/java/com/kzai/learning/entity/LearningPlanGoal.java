package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 某一周生效的每周目标。修改目标只写未来的周，不覆盖已经生效的周。 */
@Data
@TableName("learning_plan_goal")
public class LearningPlanGoal {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long planId;
    private Integer weeklyGoal;
    private LocalDate effectiveWeek;
    private LocalDateTime createTime;
}
