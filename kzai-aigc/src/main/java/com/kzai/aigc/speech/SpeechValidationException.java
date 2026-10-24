package com.kzai.aigc.speech;

/**
 * M14 语音校验受控异常（业务异常 HTTP 200 + code=400，异常码供 E2E 断言与前端提示）。
 */
public class SpeechValidationException extends RuntimeException {

    private final String errorCode;

    public SpeechValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
