package com.kzai.course.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程快照（内部契约）：供交易服务预下单时使用实时课程数据。
 * <p>
 * 价格以「分」传递；是否可购买由课程服务判定，交易服务不自行推断。
 */
@Data
@Builder
public class CourseSnapshot {

    private Long id;
    private String name;
    private String coverUrl;
    /** 价格，单位分 */
    private Integer price;
    /** 有效期，单位月 */
    private Integer validDuration;
    /** 1待上架，2已上架，3下架，4已完结 */
    private Integer status;
    private Integer deleted;
    /** 所属分类是否均启用 */
    private Boolean categoryEnabled;
    /** 是否允许购买 */
    private Boolean purchasable;
    /** 不可购买原因 */
    private String reason;
    private LocalDateTime purchaseStartTime;
    private LocalDateTime purchaseEndTime;
}
