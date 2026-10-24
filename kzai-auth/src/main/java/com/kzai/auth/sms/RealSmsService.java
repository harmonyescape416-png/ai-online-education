package com.kzai.auth.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * M15.17：REAL 短信服务（结构就绪；服务商适配待用户批准后接入）。
 * 当前状态：未配置服务商 → isConfigured()=false → /auth/sms/send 安全拒绝
 * （绝不伪造发送成功、不用 MOCK 冒充正式验证码服务）。
 * 待接入事项（需用户批准）：服务商选择（阿里云/腾讯云）、AccessKey 外部注入、
 * 签名与模板报备、费用与调用量控制、发送审计（仅脱敏元信息）。
 */
@Component
@ConditionalOnProperty(name = "kzai.sms.mode", havingValue = "real")
@Slf4j
public class RealSmsService implements SmsService {

    private final boolean configured;

    public RealSmsService(org.springframework.core.env.Environment env) {
        String accessKey = env.getProperty("kzai.sms.real.access-key", "");
        String secret = env.getProperty("kzai.sms.real.secret-key", "");
        String sign = env.getProperty("kzai.sms.real.sign-name", "");
        String template = env.getProperty("kzai.sms.real.template-code", "");
        this.configured = !accessKey.isBlank() && !secret.isBlank() && !sign.isBlank() && !template.isBlank();
        if (!configured) {
            log.warn("[sms:real] 短信服务商未配置（access-key/sign-name/template-code 缺失）——注册验证码发送将安全拒绝");
        }
    }

    @Override
    public String mode() {
        return "real";
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public boolean send(String phone, String code) {
        // 服务商适配待批准接入；未配置时调用方已在发送前安全拒绝，此处兜底防御
        log.error("[sms:real] 服务商未接入，拒绝发送至 {}（不得伪造发送成功）", mask(phone));
        return false;
    }

    private String mask(String phone) {
        return phone.length() >= 7 ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) : phone;
    }
}
