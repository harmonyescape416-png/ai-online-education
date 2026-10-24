package com.kzai.course.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 管理员审核预览：课程信息 + 内容 + 章节。 */
@Data
@Builder
public class AuditPreviewVO {

    private Long id;
    private String name;
    private String coverUrl;
    private Integer courseType;
    private String categoryName;
    private Integer free;
    private Integer price;
    private BigDecimal priceYuan;
    private Integer status;
    private String statusText;
    private Integer validDuration;
    private String usePeople;
    private String courseIntroduce;
    private String courseDetail;
    private Long creater;
    private String createrName;
    private LocalDateTime createTime;

    private Integer auditStatus;
    private String auditStatusText;
    private String auditReason;

    private List<ChapterManageVO> chapters;
    private List<ResourceVO> resources;
}
