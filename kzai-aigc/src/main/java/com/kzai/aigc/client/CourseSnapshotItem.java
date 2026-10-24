package com.kzai.aigc.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 课程快照条目（M7，镜像 kzai-course CourseSnapshot 内部契约），
 * 供订单工具在预下单前核验课程实时状态。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CourseSnapshotItem {

    private Long id;
    private String name;
    private String coverUrl;
    private Integer price;
    private Integer validDuration;
    /** 1待上架，2已上架，3下架，4已完结 */
    private Integer status;
    private Integer deleted;
    private Boolean categoryEnabled;
    private Boolean purchasable;
    private String reason;
}
