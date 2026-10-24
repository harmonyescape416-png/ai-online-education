package com.kzai.learning.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 最近学习记录（教师学情用，不暴露敏感信息）。 */
@Data
@Builder
public class RecentStudyVO {

    private Long userId;
    private String username;
    private Long courseId;
    private String courseName;
    private Long chapterId;
    private String chapterName;
    /** 0未开始，1学习中，2已完成 */
    private Integer status;
    private String statusText;
    private Integer progress;
    private Long studySeconds;
    private LocalDateTime lastStudyTime;
    /**
     * true：progress/status 是学员当前进度，不是区间结束时的历史快照。
     * 区间列表里的 studySeconds 才是该区间内的增量。
     */
    private Boolean progressIsCurrentSnapshot;
}
