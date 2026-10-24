package com.kzai.learning.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 学习大纲中的章节（课程服务内部契约，只读，学习服务不落库课程数据）。 */
@Data
public class LearnChapterDTO {

    private Long id;
    private Long courseId;
    private String name;
    private Integer chapterOrder;
    private String description;
    /** 是否可试看（M5 扩展表 course_chapter_extra.free_preview） */
    private Boolean freePreview;
    /** 视频总时长（秒） */
    private Integer videoDurationSec;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
