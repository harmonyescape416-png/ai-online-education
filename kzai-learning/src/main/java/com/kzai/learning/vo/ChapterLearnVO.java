package com.kzai.learning.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 学习页面的章节项：目录、锁定状态、本章进度。 */
@Data
@Builder
public class ChapterLearnVO {

    private Long id;
    private String name;
    private Integer chapterOrder;
    private String description;
    /** 是否可试看（未购学员可访问） */
    private Boolean freePreview;
    /** 当前用户是否可访问本章（权益 + 试看综合判定，服务端计算） */
    private Boolean accessible;
    /** 不可访问原因 */
    private String lockReason;
    /** 0未开始，1学习中，2已完成 */
    private Integer status;
    private String statusText;
    private Integer progress;
    private Integer videoPositionSec;
    private Integer videoDurationSec;
    private Long studySeconds;
    private LocalDateTime finishTime;
    /** 本章资料（受控下载路径） */
    private List<ResourceLearnVO> resources;
}
