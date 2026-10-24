package com.kzai.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 登录结果（系统设计说明书 5.2.3 /auth/login）。
 */
@Data
@Builder
public class LoginVO {

    private String token;
    private String tokenType;
    private Long expiresIn;
    private Long userId;
    private String username;
    /** 原始用户类型：1员工，2学员，3老师 */
    private Integer role;
    /** M2 扩展：权限角色编码，供前端动态菜单与后端鉴权使用 */
    private List<String> roles;
}
