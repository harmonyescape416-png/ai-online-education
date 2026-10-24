package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 学习活动日事实。只累计最终成功写入进度表的秒数增量。 */
@Data
@TableName("learning_activity_fact")
public class LearningActivityFact {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long courseId;
    private Long chapterId;
    private LocalDate bizDate;
    private Long studySeconds;
    private Integer chapterFirstFinished;
    private Integer courseFirstFinished;
    private LocalDateTime activityTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
