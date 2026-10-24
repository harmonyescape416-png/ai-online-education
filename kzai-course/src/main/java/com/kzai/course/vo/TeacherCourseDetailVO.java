package com.kzai.course.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 教师课程详情（含内容、章节与资料）。 */
@Data
@Builder
public class TeacherCourseDetailVO {

    private Long id;
    private String name;
    private String coverUrl;
    private Integer courseType;
    private Long firstCateId;
    private Long secondCateId;
    private String categoryName;
    private Integer free;
    private Integer price;
    private BigDecimal priceYuan;
    private Integer status;
    private String statusText;
    private Integer sectionNum;
    private Integer validDuration;

    private String usePeople;
    private String courseIntroduce;
    private String courseDetail;

    private Integer auditStatus;
    private String auditStatusText;
    private String auditReason;
    private String auditorName;
    private LocalDateTime auditTime;

    /** 待审核中不允许编辑 */
    private Boolean editable;

    private List<ChapterManageVO> chapters;
    private List<ResourceVO> resources;

    private LocalDateTime createTime;
}
