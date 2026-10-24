package com.kzai.gateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 网关注入身份后调用业务服务内部接口的 WebClient。
 * 走服务名 + 负载均衡，不硬编码业务服务地址。
 */
@Configuration
public class InternalWebClientConfig {

    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }
}
