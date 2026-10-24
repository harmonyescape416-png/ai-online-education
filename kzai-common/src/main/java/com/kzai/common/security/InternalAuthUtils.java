package com.kzai.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 内部请求签名（M2 遗留修复④：身份信任边界）。
 * <p>
 * 背景：业务服务此前无条件信任 X-User-Id / X-User-Roles 请求头，
 * 一旦绕过网关直连业务端口（如 18082/18083）即可伪造任意身份与角色。
 * <p>
 * 方案：网关与 Feign 内部调用方使用共享密钥对身份头计算 HMAC-SHA256 签名，
 * 业务服务校验签名后才采信身份头；签名缺失或不匹配时丢弃身份（视为匿名），
 * 内部接口 /internal/** 签名无效直接拒绝。
 */
public final class InternalAuthUtils {

    private static final String ALGORITHM = "HmacSHA256";

    private InternalAuthUtils() {
    }

    /** 拼接待签名内容，顺序固定：requestId|userId|username|role|roles */
    public static String payload(String requestId, String userId, String username,
                                 String role, String roles) {
        return String.join("|", nz(requestId), nz(userId), nz(username), nz(role), nz(roles));
    }

    public static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("内部签名计算失败", e);
        }
    }

    /** 恒定时间比对，避免时序侧信道 */
    public static boolean verify(String secret, String payload, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expected = sign(secret, payload);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
