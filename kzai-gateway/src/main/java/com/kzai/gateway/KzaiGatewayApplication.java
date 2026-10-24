package com.kzai.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication
public class KzaiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(KzaiGatewayApplication.class, args);
    }
}
