package com.kzai.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.kzai.user.client")
@MapperScan("com.kzai.user.mapper")
@SpringBootApplication(scanBasePackages = "com.kzai")
public class KzaiUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(KzaiUserApplication.class, args);
    }
}
