package com.kzai.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * course_chapter 表映射（数据库设计说明书 3.3.5，8 个字段）。
 */
@Data
@TableName("`course_chapter`")
public class CourseChapter {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long courseId;

    private String name;

    private Integer chapterOrder;

    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
