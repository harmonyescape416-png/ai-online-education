package com.kzai.course.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 课程列表项（需求分析说明书 3.2.3.1 课程浏览与检索）。
 * 金额以「分」为准，展示层再转元（数据库设计说明书 2.3 金额规范）。
 */
@Data
@Builder
public class CourseVO {

    private Long id;
    private String name;
    /** 1直播，2录播 */
    private Integer courseType;
    private String coverUrl;
    private Long firstCateId;
    private Long secondCateId;
    /** 0付费，1免费 */
    private Integer free;
    /** 课程价格，单位分 */
    private Integer price;
    /** 展示用价格，单位元 */
    private BigDecimal priceYuan;
    private Integer status;
    private Integer sectionNum;
    /** 评分，45 代表 4.5 星 */
    private Integer score;
    private BigDecimal scoreStar;
    private String introduce;
    private LocalDateTime createTime;
}
