package com.kzai.aigc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 服务入口（M6）。
 * <p>
 * 集成 Spring AI 1.0.0-M6 + Spring AI Alibaba 1.0.0-M6.1；未配置 DASHSCOPE_API_KEY 时
 * 降级为 MOCK Provider，UI/接口/报告均显著标识 MOCK。
 */
@SpringBootApplication(scanBasePackages = {"com.kzai.aigc", "com.kzai.common.web"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
public class KzaiAigcApplication {
    public static void main(String[] args) {
        SpringApplication.run(KzaiAigcApplication.class, args);
    }
}
