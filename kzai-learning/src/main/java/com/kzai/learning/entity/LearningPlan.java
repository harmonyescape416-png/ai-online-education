package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 学员学习计划。进行中和暂停同一课程只能有一条，由数据库生成列约束。 */
@Data
@TableName("learning_plan")
public class LearningPlan {

    public static final int ACTIVE = 1;
    public static final int PAUSED = 2;
    public static final int QUIT = 3;
    public static final int COMPLETED = 4;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long courseId;
    private Integer status;
    private LocalDateTime joinedAt;
    private LocalDateTime quitAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
