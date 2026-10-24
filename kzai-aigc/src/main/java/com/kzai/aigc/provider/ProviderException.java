package com.kzai.aigc.provider;

import lombok.Getter;

/**
 * 大模型提供方异常（M6）。
 * 区分：
 *  - UNAUTHORIZED：API Key 无效 / 401
 *  - RATE_LIMIT：限流 / 429
 *  - TIMEOUT：网络或模型超时
 *  - UPSTREAM：上游其它错误（4xx/5xx）
 *  - UNAVAILABLE：依赖不可用（fallback/MOCK 决策入口）
 */
@Getter
public class ProviderException extends RuntimeException {

    public enum Kind { UNAUTHORIZED, RATE_LIMIT, TIMEOUT, UPSTREAM, UNAVAILABLE, BAD_REQUEST }

    private final Kind kind;
    private final String provider;

    public ProviderException(Kind kind, String provider, String message) {
        super(message);
        this.kind = kind;
        this.provider = provider;
    }

    public ProviderException(Kind kind, String provider, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.provider = provider;
    }
}
