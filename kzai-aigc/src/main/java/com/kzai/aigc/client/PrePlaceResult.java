package com.kzai.aigc.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 预下单结果（M7，镜像 kzai-trade PrePlaceVO 契约）。
 * 全部字段来自交易服务的真实数据库写入结果，AI 工具不得伪造。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrePlaceResult {

    /** 订单 ID（全局 Jackson 已将 Long 序列化为字符串） */
    private String orderId;
    private Long userId;
    /** 1待支付，2已支付，3已关闭，4已完成，5已报名，6已申请退款 */
    private Integer status;
    private String statusText;
    /** 总金额，单位分 */
    private Integer totalAmount;
    /** 元展示值 */
    private String totalAmountYuan;
    private Integer realAmount;
    private Integer discountAmount;
    private String requestId;
    /** true 表示幂等命中（返回的是既有订单，没有创建新订单） */
    private Boolean idempotentHit;
    private String createTime;
    private List<Item> items;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        private String id;
        /** 课程 ID（字符串传输） */
        private String courseId;
        private String name;
        private String coverUrl;
        /** 价格，单位分 */
        private Integer price;
        private String priceYuan;
        private Integer validDuration;
        private Integer status;
        private String statusText;
    }
}
