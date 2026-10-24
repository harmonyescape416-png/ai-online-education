package com.kzai.common.security;

import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

/**
 * 后端接口级角色鉴权拦截器（M2：不能只依赖前端隐藏按钮控制权限）。
 * 角色取自 UserContext（网关注入），绝不读取请求参数或请求体中的身份信息。
 */
public class RoleAccessInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRoles require = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequireRoles.class);
        if (require == null) {
            require = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), RequireRoles.class);
        }
        if (require == null) {
            return true;
        }
        if (UserContext.getUserId() == null) {
            throw new AuthException(ResultCode.UNAUTHORIZED, "未登录");
        }
        List<String> owned = UserContext.getRoles();
        boolean allowed = Arrays.stream(require.value()).anyMatch(owned::contains);
        if (!allowed) {
            throw new AuthException(ResultCode.FORBIDDEN, require.message());
        }
        return true;
    }
}
