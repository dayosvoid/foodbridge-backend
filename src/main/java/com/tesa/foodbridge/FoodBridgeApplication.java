package com.tesa.foodbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FoodBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoodBridgeApplication.class, args);
    }
}