package com.kzai.learning.vo;

import lombok.Data;

/** 一门课在本周计划中的进度。完成量来自首次完课事实，不是累计进度倒推。 */
@Data
public class LearningPlanItemVO {

    private String id;
    private String courseId;
    private String courseName;
    private String status;
    private String statusText;
    private Integer weeklyGoal;
    private String goalEffectiveWeek;
    private Integer nextWeekGoal;
    private String nextGoalWeek;
    private Integer finishedThisWeek;
    private Integer remaining;
    private Double ratio;
    private String weekStatus;
    private String weekStatusText;
    private Boolean canContinue;
    private String continuePath;
    private String accessReason;
    private String coverage;
    private String coverageText;
    private Boolean alreadyJoined;
    private String joinedAt;
}
