package com.kzai.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级角色鉴权注解（M2 权限控制）。
 * 标注在 Controller 类或方法上，由 RoleAccessInterceptor 统一拦截，
 * 角色来源为网关注入的 X-User-Roles，不信任前端传入的任何身份参数。
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRoles {

    /** 命中任一角色即可访问 */
    String[] value();

    /** 权限不足时的提示语 */
    String message() default "权限不足，无法访问该资源";
}
