package com.kzai.learning;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.kzai.learning.client")
@EnableScheduling
@MapperScan({"com.kzai.learning.mapper", "com.kzai.learning.points", "com.kzai.learning.note"})
@SpringBootApplication(scanBasePackages = "com.kzai")
public class KzaiLearningApplication {

    public static void main(String[] args) {
        SpringApplication.run(KzaiLearningApplication.class, args);
    }
}
