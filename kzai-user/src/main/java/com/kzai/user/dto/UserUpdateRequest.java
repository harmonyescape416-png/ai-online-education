package com.kzai.user.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 管理员编辑用户（仅允许编辑可编辑字段：手机号）。
 * 账号、类型与角色分别由专门接口处理，避免通过本接口越权改角色。
 */
@Data
public class UserUpdateRequest {

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String cellPhone;
}
