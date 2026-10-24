package com.kzai.course.dto;

import lombok.Data;

/** 教师课程列表查询条件。 */
@Data
public class TeacherCourseQuery {

    private Integer pageNum = 1;

    private Integer pageSize = 10;

    /** 课程名称模糊查询 */
    private String name;

    /** 课程状态：1待上架，2已上架，3下架，4已完结 */
    private Integer status;
}
