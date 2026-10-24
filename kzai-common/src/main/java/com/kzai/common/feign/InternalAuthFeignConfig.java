package com.kzai.common.feign;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 客户端的内部签名配置：各 @FeignClient 通过 configuration 引用。
 * <p>
 * 未引入 openfeign 的服务（如课程服务）不能加载该类，故以类是否存在为条件。
 */
@Configuration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class InternalAuthFeignConfig {

    @Bean
    public InternalAuthFeignInterceptor internalAuthFeignInterceptor(
            @Value("${kzai.internal.secret}") String internalSecret) {
        return new InternalAuthFeignInterceptor(internalSecret);
    }
}
