package com.kzai.course.dto;

import lombok.Data;

/** 学习大纲章节（内部契约）：章节原文字段 + M5 扩展的试看标识与视频时长。 */
@Data
public class LearnChapterDTO {

    private Long id;
    private Long courseId;
    private String name;
    private Integer chapterOrder;
    private String description;
    /** 是否可试看（course_chapter_extra.free_preview） */
    private Boolean freePreview;
    private Integer videoDurationSec;
}
