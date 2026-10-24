package com.kzai.trade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.kzai.trade.client")
@org.springframework.scheduling.annotation.EnableScheduling
@MapperScan("com.kzai.trade")
@SpringBootApplication(scanBasePackages = "com.kzai")
public class KzaiTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KzaiTradeApplication.class, args);
    }
}
