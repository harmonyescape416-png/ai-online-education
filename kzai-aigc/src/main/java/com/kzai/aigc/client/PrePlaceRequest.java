package com.kzai.aigc.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 预下单请求（M7，镜像 kzai-trade PrePlaceRequest 契约）。
 * courseIds 来自「用户确认过的购买提案」，绝不由模型自由填写；
 * userId 不在此对象中——交易服务从签名头中的可信 UserContext 取得。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrePlaceRequest {

    private List<Long> courseIds;

    /** 幂等请求号（稳定：同一确认提案固定不变，超时重试不复用新号） */
    private String requestId;
}
