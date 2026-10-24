package com.kzai.trade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 预下单入参。
 * <p>
 * 不接收价格、折扣、用户ID、课程名称等任何可由服务端推导的字段，
 * 避免前端篡改金额。
 */
@Data
public class PrePlaceRequest {

    @NotEmpty(message = "课程ID列表不能为空")
    @Size(max = 20, message = "单笔订单最多 20 门课程")
    private List<Long> courseIds;

    /** 幂等请求号：同一用户同一 requestId 重复提交返回同一订单 */
    @NotBlank(message = "requestId 不能为空")
    @Size(max = 64, message = "requestId 长度不能超过 64")
    private String requestId;

    /** 可选。服务端按该券重新计算优惠，不接受前端传入的优惠金额。 */
    private Long userCouponId;
}
