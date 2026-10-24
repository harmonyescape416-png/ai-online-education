package com.kzai.course.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 教师课程列表项（M3）。 */
@Data
@Builder
public class TeacherCourseVO {

    private Long id;
    private String name;
    private String coverUrl;
    private Integer courseType;
    private Long firstCateId;
    private Long secondCateId;
    private String categoryName;
    private Integer free;
    /** 价格（分） */
    private Integer price;
    private BigDecimal priceYuan;
    private Integer status;
    private String statusText;
    private Integer sectionNum;
    private Integer validDuration;
    /** 最新审核状态：1待审核，2通过，3驳回，4强制下架 */
    private Integer auditStatus;
    private String auditStatusText;
    private String auditReason;
    private LocalDateTime createTime;
}
