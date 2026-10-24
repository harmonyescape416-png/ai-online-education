package com.kzai.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程审核记录（M3 扩展表 course_audit，见 sql/05_m3_course_manage.sql）。
 * <p>
 * 原始 course.status 枚举（1待上架/2已上架/3下架/4已完结）保持不变，
 * 草稿、待审核、驳回、强制下架等过程态由本表表达，不重定义原文枚举。
 */
@Data
@TableName("course_audit")
public class CourseAudit {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long courseId;

    /** 1待审核，2通过，3驳回，4强制下架 */
    private Integer auditStatus;

    /** 驳回/下架原因 */
    private String reason;

    private Long auditorId;

    private String auditorName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
