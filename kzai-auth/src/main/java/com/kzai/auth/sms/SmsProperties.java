package com.kzai.auth.sms;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** M15.17：短信验证码安全阈值（集中配置，可按环境覆盖）。 */
@Data
@Component
@ConfigurationProperties(prefix = "kzai.sms")
public class SmsProperties {
    /** mock / real */
    private String mode = "mock";
    /** 验证码有效期（秒） */
    private long codeTtlSeconds = 300;
    /** 同一手机号发送间隔（秒） */
    private long sendIntervalSeconds = 60;
    /** 单验证码最多验证失败次数（超限失效） */
    private int maxVerifyFailures = 5;
    /** 单手机号每日发送上限 */
    private int dailySendLimit = 10;
    /** 单 IP 每小时发送上限 */
    private int ipHourlyLimit = 20;
    /** MOCK 模式测试夹具密钥（仅隔离开发/测试环境注入；不提供则不暴露测试验证码通道） */
    private String mockFixtureKey = "";
}
