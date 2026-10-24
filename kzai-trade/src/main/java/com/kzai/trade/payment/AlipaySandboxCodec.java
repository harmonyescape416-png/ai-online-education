package com.kzai.trade.payment;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝开放平台 RSA2 验签。
 * 步骤来自支付宝文档「异步通知说明」和「签名机制」：去掉 sign、sign_type 和空值，
 * 参数名按 ASCII 排序，拼成 key=value&amp;key=value，再用支付宝公钥做 SHA256WithRSA。
 * 文档：https://opendocs.alipay.com/open/270/105902
 * 沙箱网关：https://openapi-sandbox.dl.alipaydev.com/gateway.do
 */
public final class AlipaySandboxCodec {

    private AlipaySandboxCodec() {
    }

    public static boolean verify(Map<String, String> params, String alipayPublicKey) {
        String sign = params.get("sign");
        if (sign == null || sign.isBlank() || alipayPublicKey == null || alipayPublicKey.isBlank()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey(alipayPublicKey));
            signature.update(canonical(params).getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception ex) {
            return false;
        }
    }

    public static String canonical(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if (!"sign".equals(key) && !"sign_type".equals(key) && value != null && !value.isBlank()) {
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

    private static PublicKey publicKey(String key) throws Exception {
        String normalized = key.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }
}
