// API Discovery Worker 서비스의 Spring Boot 진입점
package com.pentasecurity.apidiscover;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ApiDiscoverWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiDiscoverWorkerApplication.class, args);
    }
}
