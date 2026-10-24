package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * learning_lesson 表映射（数据库设计说明书 3.3.12，原始 10 个字段，M5 不做任何结构变更）。
 * <p>
 * 语义定位（M5）：记录"用户在某门课程上的最近学习状态"一门课一行。
 * 章节级明细（视频断点、累计时长、完成状态）落在扩展表 learning_lesson_progress。
 * section_id 原文可为 NULL，故唯一键 (user_id, course_id, section_id) 无法阻止重复行，
 * 由应用层保证"用户 + 课程"唯一。
 */
@Data
@TableName("`learning_lesson`")
public class LearningLesson {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long courseId;

    /** 最近学习的章ID */
    private Long chapterId;

    /** 节ID：当前无"节"数据模型，恒为 NULL（保留原字段不删除） */
    private Long sectionId;

    /** 0未开始，1学习中，2已完成 */
    private Integer status;

    /** 课程进度百分比 0~100，服务端按已完成章节数计算 */
    private Integer progress;

    private LocalDateTime lastStudyTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
