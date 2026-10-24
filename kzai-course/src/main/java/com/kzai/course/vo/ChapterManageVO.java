package com.kzai.course.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 章节管理视图（原始 course_chapter 8 字段 + 关联资源）。 */
@Data
@Builder
public class ChapterManageVO {

    private Long id;
    private Long courseId;
    private String name;
    private Integer chapterOrder;
    private String description;

    /** M5：是否可试看（course_chapter_extra.free_preview，教师显式设置） */
    private Boolean freePreview;
    /** M5：视频总时长（秒） */
    private Integer videoDurationSec;

    /** 关联的视频/资料（来自 course_resource，原表无视频地址字段） */
    private List<ResourceVO> resources;

    private LocalDateTime createTime;
}
