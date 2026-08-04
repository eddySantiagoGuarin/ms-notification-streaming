package com.world_dance.ms_notification_._streaming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories; 

@SpringBootApplication
@EnableMongoRepositories(basePackages = "com.world_dance") 
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}