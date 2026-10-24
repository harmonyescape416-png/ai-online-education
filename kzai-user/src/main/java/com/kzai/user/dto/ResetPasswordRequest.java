package com.kzai.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员重置密码。
 */
@Data
public class ResetPasswordRequest {

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需为 6~32 位")
    private String newPassword;
}
