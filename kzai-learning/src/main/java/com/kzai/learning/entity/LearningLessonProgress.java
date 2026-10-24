package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * learning_lesson_progress：章节级学习进度明细（M5 扩展表 sql/07_m5_learning.sql）。
 * <p>
 * 一行 = 一个用户在某门课程下某一个章节的学习状态。
 * study_seconds 只按视频位置真实前进量累计，重复上报同一位置不会产生重复累计。
 */
@Data
@TableName("`learning_lesson_progress`")
public class LearningLessonProgress {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long courseId;

    private Long chapterId;

    /** 0未开始，1学习中，2已完成 */
    private Integer status;

    /** 章节内进度百分比 0~100 */
    private Integer progress;

    /** 视频断点位置（秒） */
    private Integer videoPositionSec;

    /** 视频总时长（秒） */
    private Integer videoDurationSec;

    /** 累计有效学习时长（秒） */
    private Long studySeconds;

    private LocalDateTime finishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
