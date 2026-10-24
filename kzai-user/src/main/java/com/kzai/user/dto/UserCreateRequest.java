package com.kzai.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * 管理员新增账号（需求 3.2.5.2：账号由管理员创建，不开放学员自助注册）。
 */
@Data
public class UserCreateRequest {

    @NotBlank(message = "登录账号不能为空")
    private String username;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String cellPhone;

    /** 用户类型：1员工，2学员，3老师 */
    @NotNull(message = "用户类型不能为空")
    private Integer type;

    /** 权限角色，为空时按 type 推导 */
    private List<String> roleCodes;

    /** 初始密码，为空时使用默认密码 */
    private String password;
}
