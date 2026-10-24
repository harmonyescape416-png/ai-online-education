package com.kzai.common.web;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.ResultCode;
import com.kzai.common.security.InternalAuthUtils;
import com.kzai.common.security.RoleAccessInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * 业务服务从网关注入的请求头恢复 UserContext（系统设计说明书 6.1）。
 * <p>
 * M2：追加 RoleAccessInterceptor 做接口级角色鉴权，注册顺序保证先恢复上下文再鉴权。
 * <p>
 * M2 遗留修复④：身份头只有在内部签名校验通过后才被采信。
 * 直接访问业务端口伪造 X-User-Id / X-User-Roles 会被丢弃身份（按匿名处理），
 * /internal/** 接口签名无效直接 403，避免绕过网关提权。
 */
@Configuration
public class UserContextWebConfig implements WebMvcConfigurer {

    private final String internalSecret;

    public UserContextWebConfig(@Value("${kzai.internal.secret}") String internalSecret) {
        this.internalSecret = internalSecret;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        String userId = request.getHeader(Constants.HEADER_USER_ID);
                        String username = request.getHeader(Constants.HEADER_USERNAME);
                        String role = request.getHeader(Constants.HEADER_USER_ROLE);
                        String roles = request.getHeader(Constants.HEADER_USER_ROLES);
                        String requestId = request.getHeader(Constants.HEADER_REQUEST_ID);
                        String signature = request.getHeader(Constants.HEADER_INTERNAL_SIGN);

                        boolean signed = InternalAuthUtils.verify(internalSecret,
                                InternalAuthUtils.payload(requestId, userId, username, role, roles),
                                signature);

                        if (request.getRequestURI().startsWith(Constants.INTERNAL_PATH_PREFIX) && !signed) {
                            throw new AuthException(ResultCode.FORBIDDEN, "内部接口签名校验失败");
                        }
                        // 签名无效时不信任任何身份头：按匿名请求处理
                        if (!signed) {
                            userId = null;
                            username = null;
                            role = null;
                            roles = null;
                        }

                        UserContext.set(
                                userId == null || userId.isBlank() ? null : Long.valueOf(userId),
                                username,
                                role == null || role.isBlank() ? null : Integer.valueOf(role),
                                parseRoles(roles),
                                requestId);
                        return true;
                    }

                    @Override
                    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                                Object handler, Exception ex) {
                        UserContext.clear();
                    }
                })
                .addPathPatterns("/**")
                .order(1);
        registry.addInterceptor(new RoleAccessInterceptor())
                .addPathPatterns("/**")
                .order(2);
    }

    private List<String> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
