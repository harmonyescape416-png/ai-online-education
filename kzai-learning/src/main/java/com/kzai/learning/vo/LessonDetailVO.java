package com.kzai.learning.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 课程学习详情：课程信息 + 进度 + 章节目录。 */
@Data
@Builder
public class LessonDetailVO {

    private Long courseId;
    private String courseName;
    private String coverUrl;
    private Integer free;
    private String introduce;
    /** 0未开始，1学习中，2已完成 */
    private Integer status;
    private String statusText;
    private Integer progress;
    private Integer totalChapterCount;
    private Integer finishedChapterCount;
    private Long lastChapterId;
    private String lastChapterName;
    private Integer lastVideoPositionSec;
    private Long studySeconds;
    private LocalDateTime lastStudyTime;

    /** 权益信息 */
    private Boolean entitlementValid;
    private String entitlementSource;
    private String entitlementReason;
    private LocalDateTime expireTime;

    /** 上一章 / 下一章（按 chapter_order） */
    private Long prevChapterId;
    private Long nextChapterId;

    private List<ChapterLearnVO> chapters;
}
