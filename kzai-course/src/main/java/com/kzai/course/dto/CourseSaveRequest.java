package com.kzai.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 教师课程新增/编辑入参（M3）。
 * 价格统一以「分」为单位入库，禁止浮点金额。
 */
@Data
public class CourseSaveRequest {

    @NotBlank(message = "课程名称不能为空")
    @Size(max = 100, message = "课程名称不能超过 100 个字符")
    private String name;

    /** 封面访问路径，由文件上传后返回 */
    private String coverUrl;

    private Long firstCateId;

    private Long secondCateId;

    /** 1直播，2录播 */
    private Integer courseType;

    /** 价格（分），0 表示免费 */
    private Integer price;

    /**
     * 价格（元，字符串）。与 price 二选一，优先使用本字段：
     * 由服务端用 BigDecimal 精确换算成分，避免前端浮点乘法误差。
     */
    private String priceYuan;

    /** 有效期（月） */
    private Integer validDuration;

    /** 适用人群，落 course_content.use_people */
    @Size(max = 500, message = "适用人群不能超过 500 个字符")
    private String usePeople;

    /** 课程简介，落 course_content.course_introduce */
    @Size(max = 1000, message = "课程简介不能超过 1000 个字符")
    private String courseIntroduce;

    /** 课程详情，落 course_content.course_detail */
    @Size(max = 4000, message = "课程详情不能超过 4000 个字符")
    private String courseDetail;
}
