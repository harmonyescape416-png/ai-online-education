package com.kzai.auth.sms;

/**
 * M15.17：短信服务抽象（MOCK/REAL 双模式，Spring Profile/配置项选择，不混合实现）。
 * 实现类仅负责「发送」行为；验证码生成/存储/校验/限流统一由 SmsCodeService 承担。
 */
public interface SmsService {

    /** 当前模式（mock/real）——日志与审计用 */
    String mode();

    /**
     * 发送短信验证码。
     *
     * @param phone 手机号（已格式校验）
     * @param code  验证码明文（仅本调用栈内使用，实现方不得写日志/返回）
     * @return true=发送成功；false=发送失败（REAL 服务商超时/失败不得伪装成功）
     */
    boolean send(String phone, String code);

    /** REAL 模式是否已完成服务商配置（未配置时 /auth/sms/send 必须安全拒绝） */
    default boolean isConfigured() {
        return true;
    }
}
