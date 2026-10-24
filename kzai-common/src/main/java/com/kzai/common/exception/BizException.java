package com.kzai.common.exception;

import com.kzai.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常：课程不存在、重复下单等（系统设计说明书 7.1）。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = ResultCode.BAD_REQUEST;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
