package com.kzai.trade.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付配置。密钥只从环境变量读取，仓库里不放沙箱或生产私钥。
 */
@Data
@Component
@ConfigurationProperties(prefix = "kzai.trade.payment")
public class PaymentProperties {

    /** mock 或 alipay-sandbox。未配置沙箱密钥时，支付宝选择仍走模拟渠道。 */
    private String provider = "mock";
    private String mockSecret = "kzai-dev-mock-pay-secret";
    private String merchantId = "kzai-mock-merchant";
    private int expireMinutes = 30;
    private Alipay alipay = new Alipay();

    public boolean alipaySandboxReady() {
        return "alipay-sandbox".equals(provider)
                && filled(alipay.appId) && filled(alipay.privateKey) && filled(alipay.alipayPublicKey);
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }

    @Data
    public static class Alipay {
        private String appId = "";
        private String privateKey = "";
        private String alipayPublicKey = "";
        /** 官方沙箱网关，见支付宝开放平台沙箱说明。 */
        private String gateway = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
        private String notifyUrl = "";
    }
}
