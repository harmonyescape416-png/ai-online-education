package com.kzai.trade.payment;

import com.kzai.common.exception.BizException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 模拟渠道自己的 HMAC 契约，只用于演示和测试。
 * 不是微信支付或支付宝的签名算法。
 */
public final class MockPayCodec {

    private MockPayCodec() {
    }

    public static String sign(Map<String, String> params, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(canonical(params).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception ex) {
            throw new BizException("模拟支付签名失败");
        }
    }

    public static boolean verify(Map<String, String> params, String secret) {
        String sign = params.get("sign");
        if (sign == null || sign.isBlank()) {
            return false;
        }
        String expected = sign(params, secret);
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    static String canonical(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if (!"sign".equals(key) && value != null && !value.isBlank()) {
                sorted.put(key, value);
            }
        });
        StringBuilder builder = new StringBuilder();
        sorted.forEach((key, value) -> {
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(key).append('=').append(value);
        });
        return builder.toString();
    }
}
