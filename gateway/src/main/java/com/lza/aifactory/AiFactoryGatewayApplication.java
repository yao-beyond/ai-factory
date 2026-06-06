package com.lza.aifactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiFactoryGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiFactoryGatewayApplication.class, args);
    }
}
