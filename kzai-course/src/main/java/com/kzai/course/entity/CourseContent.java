package com.kzai.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * course_content 表映射（数据库设计说明书 3.3.4，10 个字段）。
 * id 与 course.id 一致（1:1）。
 */
@Data
@TableName("`course_content`")
public class CourseContent {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String courseIntroduce;

    private String usePeople;

    private String courseDetail;

    private Long depId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;

    @TableLogic
    private Integer deleted;
}
