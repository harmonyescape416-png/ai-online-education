package com.kzai.course.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 课程详情（需求分析说明书 3.2.3.2）：主体 + 内容长文本 + 章节目录。
 */
@Data
@Builder
public class CourseDetailVO {

    private Long id;
    private String name;
    private Integer courseType;
    private String coverUrl;
    private Integer free;
    /** 单位分 */
    private Integer price;
    private BigDecimal priceYuan;
    private Integer status;
    private Integer sectionNum;
    private Integer score;
    private BigDecimal scoreStar;
    private Integer validDuration;

    private String courseIntroduce;
    private String usePeople;
    private String courseDetail;

    /** 创建人（讲师）ID */
    private Long creater;

    private List<ChapterVO> chapters;

    @Data
    @Builder
    public static class ChapterVO {
        private Long id;
        private String name;
        private Integer chapterOrder;
        private String description;
        /** 需求 3.2.3.2 / M5：可试看由教师显式设置（course_chapter_extra），不再默认首章免费 */
        private Boolean tryWatch;
        /** M5：视频总时长（秒） */
        private Integer videoDurationSec;
    }
}
