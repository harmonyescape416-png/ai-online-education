package com.kzai.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 审核处理入参：驳回与强制下架必须填写原因。 */
@Data
public class AuditHandleRequest {

    @NotBlank(message = "处理原因不能为空")
    @Size(max = 255, message = "处理原因不能超过 255 个字符")
    private String reason;
}
