package com.kzai.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * course_chapter_extra（M5 扩展表，非原始 12 张核心表）。
 * <p>
 * 只承载原始 course_chapter（8 字段）无法表达的"本章是否可试看"与视频时长，
 * 不对原始表做任何结构修改。
 */
@Data
@TableName("`course_chapter_extra`")
public class CourseChapterExtra {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long courseId;

    private Long chapterId;

    /** 0不可试看，1可试看 */
    private Integer freePreview;

    /** 视频总时长（秒） */
    private Integer videoDurationSec;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
