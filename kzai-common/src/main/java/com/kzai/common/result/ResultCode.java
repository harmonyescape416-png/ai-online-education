package com.kzai.common.result;

/**
 * 业务状态码，对应《系统设计说明书》7.3 错误码设计。
 */
public final class ResultCode {

    private ResultCode() {
    }

    public static final int SUCCESS = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int TOO_MANY_REQUESTS = 429;
    public static final int ERROR = 500;
}
