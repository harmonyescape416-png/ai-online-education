package com.kzai.learning.vo;

import lombok.Builder;
import lombok.Data;

/** 章节学习人数与完成分布。 */
@Data
@Builder
public class ChapterStatVO {

    private Long chapterId;
    private String chapterName;
    private Integer chapterOrder;
    /** 学过的用户数（有进度记录） */
    private Long learnerCount;
    /** 已完成用户数 */
    private Long finishedCount;
    /** 完成率（百分比，保留 1 位小数） */
    private Double finishRate;
    /** 本章累计有效学习时长（秒） */
    private Long totalStudySeconds;
}
