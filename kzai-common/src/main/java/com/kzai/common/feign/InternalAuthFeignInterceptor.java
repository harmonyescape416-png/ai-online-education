package com.kzai.common.feign;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.security.InternalAuthUtils;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Feign 内部调用签名（M2 遗留修复④）。
 * <p>
 * 服务间调用携带本服务已鉴权通过的身份头与 HMAC 签名；接收方校验通过后才采信，
 * 无签名者按匿名处理，/internal/** 接口直接 403，避免绕过网关伪造身份。
 * <p>
 * 通过 {@link InternalAuthFeignConfig} 注册到各 @FeignClient（全局 @Component 方式在
 * spring-cloud-openfeign 4.x 不会被自动应用，故显式声明 configuration）。
 */
@Slf4j
public class InternalAuthFeignInterceptor implements RequestInterceptor {

    private final String internalSecret;

    public InternalAuthFeignInterceptor(String internalSecret) {
        this.internalSecret = internalSecret;
    }

    @Override
    public void apply(RequestTemplate template) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        Long userId = UserContext.getUserId();
        String uid = userId == null ? "" : String.valueOf(userId);
        String username = UserContext.getUsername() == null ? "" : UserContext.getUsername();
        String role = UserContext.getRole() == null ? "" : String.valueOf(UserContext.getRole());
        String roles = String.join(",", UserContext.getRoles());

        template.header(Constants.HEADER_REQUEST_ID, requestId);
        if (userId != null) {
            // 可信用户上下文传播：只传播本服务已鉴权通过的身份，不传播任何客户端入参
            template.header(Constants.HEADER_USER_ID, uid);
            template.header(Constants.HEADER_USERNAME, username);
            template.header(Constants.HEADER_USER_ROLE, role);
            template.header(Constants.HEADER_USER_ROLES, roles);
        }
        template.header(Constants.HEADER_INTERNAL_SIGN, InternalAuthUtils.sign(internalSecret,
                InternalAuthUtils.payload(requestId, uid, username, role, roles)));
    }
}
