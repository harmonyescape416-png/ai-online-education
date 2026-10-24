package com.kzai.common.exception;

import com.kzai.common.result.ResultCode;

/**
 * 兜底异常：未预期的运行时异常（系统设计说明书 7.1）。
 */
public class GlobalException extends RuntimeException {

    public GlobalException(String message) {
        super(message);
    }

    public GlobalException(String message, Throwable cause) {
        super(message, cause);
    }

    public int code() {
        return ResultCode.ERROR;
    }
}
