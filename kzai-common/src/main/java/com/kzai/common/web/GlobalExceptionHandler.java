package com.kzai.common.web;

import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一异常处理（系统设计说明书 7.2）：转换为 R.error(code, msg)，前端按 code 提示。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("[biz] {}", e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 鉴权异常：HTTP 状态码跟随业务码，401 未登录、403 权限不足，
     * 避免前端只能靠 body.code 区分（M2 权限提示需求）。
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<R<Void>> handleAuth(AuthException e) {
        log.warn("[auth] {}", e.getMessage());
        return ResponseEntity.status(httpStatus(e.getCode()))
                .body(R.fail(e.getCode(), e.getMessage()));
    }

    private HttpStatus httpStatus(int code) {
        try {
            return HttpStatus.valueOf(code);
        } catch (IllegalArgumentException ex) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    /** M3：上传文件超出容器限制时给出明确提示，避免落到 500 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleUploadSize(MaxUploadSizeExceededException e) {
        log.warn("[upload] file too large: {}", e.getMessage());
        return R.badRequest("文件大小超过限制");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public R<Void> handleValid(Exception e) {
        String msg = e instanceof MethodArgumentNotValidException m
                ? m.getBindingResult().getFieldError() != null
                        ? m.getBindingResult().getFieldError().getDefaultMessage()
                        : "参数错误"
                : "参数错误";
        return R.badRequest(msg);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("[not-found] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(R.fail(HttpStatus.NOT_FOUND.value(), "请求的内容不存在"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleOther(Exception e) {
        log.error("[unexpected] {}", e.getMessage(), e);
        return R.fail(ResultCode.ERROR, "服务暂时不可用，请稍后重试");
    }
}
