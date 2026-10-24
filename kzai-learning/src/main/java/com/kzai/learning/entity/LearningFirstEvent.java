package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 首次完成章节或课程。唯一键保证并发重复完成只记一次。 */
@Data
@TableName("learning_first_event")
public class LearningFirstEvent {

    public static final String CHAPTER_FINISH = "CHAPTER_FINISH";
    public static final String COURSE_FINISH = "COURSE_FINISH";
    public static final long COURSE_CHAPTER = 0L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long courseId;
    private Long chapterId;
    private String eventType;
    private LocalDate bizDate;
    private LocalDateTime createTime;
}
