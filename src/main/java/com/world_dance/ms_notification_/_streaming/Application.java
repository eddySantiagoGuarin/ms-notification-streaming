package com.world_dance.ms_notification_._streaming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.world_dance")
@ComponentScan(basePackages = "com.world_dance")
@EnableMongoRepositories(basePackages = "com.world_dance")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}