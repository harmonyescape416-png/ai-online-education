package com.kzai.common.context;

import java.util.List;

/**
 * 用户上下文（ThreadLocal）。
 * 网关校验 JWT 后注入请求头，业务服务从请求头恢复 UserContext（系统设计说明书 6.1、5.5.2）。
 * <p>
 * M2：增加权限角色 roles，用于后端接口级鉴权（@RequireRoles）。
 * 角色只能来自网关注入的请求头，前端无法伪造（网关会先移除客户端同名请求头）。
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<Integer> ROLE = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> ROLES = new ThreadLocal<>();
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId, String username, Integer role, String requestId) {
        set(userId, username, role, List.of(), requestId);
    }

    public static void set(Long userId, String username, Integer role,
                           List<String> roles, String requestId) {
        USER_ID.set(userId);
        USERNAME.set(username);
        ROLE.set(role);
        ROLES.set(roles == null ? List.of() : roles);
        REQUEST_ID.set(requestId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static Integer getRole() {
        return ROLE.get();
    }

    public static List<String> getRoles() {
        List<String> roles = ROLES.get();
        return roles == null ? List.of() : roles;
    }

    public static String getRequestId() {
        return REQUEST_ID.get();
    }

    public static boolean hasRole(String roleCode) {
        return getRoles().contains(roleCode);
    }

    /** 管理员或员工（user.type=1）具备后台访问权限 */
    public static boolean isAdminOrStaff() {
        return hasRole("ADMIN") || hasRole("STAFF");
    }

    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
        ROLE.remove();
        ROLES.remove();
        REQUEST_ID.remove();
    }
}
