package com.kzai.common.result;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一返回结构：code / msg / data / requestId（系统设计说明书 5.2.2）。
 */
@Data
@NoArgsConstructor
public class R<T> implements Serializable {

    private int code;
    private String msg;
    private T data;
    private String requestId;

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.SUCCESS, "ok", data);
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public static <T> R<T> error(String msg) {
        return fail(ResultCode.ERROR, msg);
    }

    public static <T> R<T> badRequest(String msg) {
        return fail(ResultCode.BAD_REQUEST, msg);
    }

    public static <T> R<T> unauthorized(String msg) {
        return fail(ResultCode.UNAUTHORIZED, msg);
    }

    public static <T> R<T> forbidden(String msg) {
        return fail(ResultCode.FORBIDDEN, msg);
    }

    public R<T> requestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS;
    }
}
