package com.kzai.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程文件与资料（M3 扩展表 course_resource，见 sql/05_m3_course_manage.sql）。
 * <p>
 * 原始表没有文件、视频地址、课时等载体字段，故用本表承载：
 * 封面、教学资料、章节视频均通过 course_id / chapter_id 关联表达，course_chapter 表零改动。
 */
@Data
@TableName("course_resource")
public class CourseResource {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long courseId;

    private Long chapterId;

    /** 1封面，2教学资料，3视频 */
    private Integer kind;

    private String fileName;

    /** 对外可访问路径：/files/{id}/download */
    private String fileUrl;

    /** 服务端存储相对路径，不对外暴露 */
    private String filePath;

    private Long fileSize;

    private String contentType;

    private Long uploader;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;

    /** M15.20-E：被新版本资源 ID 替换（历史链） */
    private Long replacedBy;
    /** 0 未提交，1 已有关联知识文档。展示以 kbPhase 为准。 */
    private Integer kbStatus;
    private Long kbDocId;
    private Integer kbReviewStatus;
    private Integer kbIndexStatus;
    private String kbFailReason;
    private String kbVersion;
    /** M15.20-E：替换时间 */
    private LocalDateTime replaceTime;
}
