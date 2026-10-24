package com.kzai.trade.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程服务返回的课程快照（交易服务内部契约）。
 * <p>
 * 价格一律以「分」传递；是否可购买由课程服务判定，交易服务不自行猜测。
 */
@Data
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
    /** 是否允许购买（已上架 + 分类启用 + 在购买时间范围内） */
    private Boolean purchasable;
    /** 不可购买原因，便于交易服务给出明确提示 */
    private String reason;
    private LocalDateTime purchaseStartTime;
    private LocalDateTime purchaseEndTime;
}
