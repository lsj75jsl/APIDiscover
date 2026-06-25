// API Discovery Worker 서비스의 Spring Boot 진입점
package com.pentasecurity.apidiscover;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @EnableScheduling 은 SchedulingConfig(@Profile("!cli")) 로 분리 — CLI 모드는 스케줄러 미기동(doc/31 B1).
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiDiscoverWorkerApplication {

    private static final String CLI_TRIGGER = "--adc.cli.export-domain=";

    public static void main(String[] args) {
        if (isCliMode(args)) {
            // CLI 모드: 웹 미기동 + "cli" 프로파일(SchedulingConfig 제외) → 1 명령 실행 후 종료(doc/31 B1)
            new SpringApplicationBuilder(ApiDiscoverWorkerApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("cli")
                    .run(args);
        } else {
            SpringApplication.run(ApiDiscoverWorkerApplication.class, args);
        }
    }

    private static boolean isCliMode(String[] args) {
        for (String a : args) {
            if (a.startsWith(CLI_TRIGGER)) {
                return true;
            }
        }
        return false;
    }
}
