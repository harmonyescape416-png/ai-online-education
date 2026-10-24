package com.kzai.auth.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * M15.17：MOCK 短信服务（开发/集成测试/浏览器 E2E 专用）。
 * 不调用真实短信平台、不产生费用；验证码经 SmsCodeService 的测试夹具通道获取
 * （严格受控：仅 MOCK 模式+隔离环境），不写普通业务日志、不提供公开 API 读取。
 * 正式环境（kzai.sms.mode=real 且未配置服务商）不得使用本实现。
 */
@Component
@ConditionalOnProperty(name = "kzai.sms.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockSmsService implements SmsService {

    @Override
    public String mode() {
        return "mock";
    }

    @Override
    public boolean send(String phone, String code) {
        // MOCK：掩码记录元信息（不记录验证码明文）
        log.info("[sms:mock] 已生成验证码并发送至 {}（内容不落日志）", mask(phone));
        return true;
    }

    private String mask(String phone) {
        return phone.length() >= 7 ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) : phone;
    }
}
