package com.kzai.auth.sms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * M15.17：SMS 发送编排——按配置选择实现（mock/real）。
 * ObjectProvider 注入所有 SmsService Bean（@ConditionalOnProperty 决定哪个存在），
 * 按 kzai.sms.mode 选择；REAL 未配置/不存在时安全拒绝，不伪造成功。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmsSender {

    private final ObjectProvider<SmsService> services;
    private final SmsProperties props;

    private SmsService active() {
        SmsService svc = services.stream()
                .filter(s -> props.getMode().equals(s.mode()))
                .findFirst()
                .orElse(null);
        if (svc == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "短信服务实现不可用（模式：" + props.getMode() + "）");
        }
        return svc;
    }

    /** REAL 模式且服务商未配置 → 安全拒绝（不伪造成功、不用 MOCK 冒充） */
    public void requireSendable() {
        if (!active().isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "短信服务未配置，注册功能暂不可用");
        }
    }

    /** 发送验证码短信（不记录码值明文） */
    public boolean send(String phone, String code) {
        return active().send(phone, code);
    }

    public String mode() {
        return active().mode();
    }

    /** MOCK 模式判定（夹具写入仅 MOCK 执行） */
    public boolean isMock() {
        return "mock".equals(active().mode());
    }
}
