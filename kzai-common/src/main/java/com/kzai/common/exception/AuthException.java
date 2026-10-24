package com.kzai.common.exception;

import com.kzai.common.result.ResultCode;
import lombok.Getter;

/**
 * 认证/权限异常：token 过期、越权（系统设计说明书 7.1）。
 */
@Getter
public class AuthException extends RuntimeException {

    private final int code;

    public AuthException(String message) {
        super(message);
        this.code = ResultCode.UNAUTHORIZED;
    }

    public AuthException(int code, String message) {
        super(message);
        this.code = code;
    }
}
