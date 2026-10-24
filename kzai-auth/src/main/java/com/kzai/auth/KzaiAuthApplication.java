package com.kzai.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.kzai.auth.client")
@SpringBootApplication(scanBasePackages = "com.kzai")
public class KzaiAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(KzaiAuthApplication.class, args);
    }
}
