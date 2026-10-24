package com.kzai.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * course_category 表映射（数据库设计说明书 3.3.2，8 个字段）。
 */
@Data
@TableName("`course_category`")
public class CourseCategory {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** 父分类ID，0为一级 */
    private Long parentId;

    /** 层级：1/2/3 */
    private Integer level;

    private Integer sort;

    /** 0禁用，1启用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
