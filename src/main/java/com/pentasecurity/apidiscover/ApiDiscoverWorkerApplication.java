// API Discovery Worker 서비스의 Spring Boot 진입점
package com.pentasecurity.apidiscover;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

// @EnableScheduling 은 SchedulingConfig(@Profile("!cli")) 로 분리 — CLI 모드는 스케줄러 미기동(doc/31 B1).
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiDiscoverWorkerApplication {

    // CLI 모드 트리거 인자 — export(CSV, doc/31)·scan(온디맨드, doc/33 §7). 하나라도 있으면 CLI.
    private static final String[] CLI_TRIGGERS = {"--adc.cli.export-domain=", "--adc.cli.scan-domain="};

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
            for (String trigger : CLI_TRIGGERS) {
                if (a.startsWith(trigger)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 레이트 예산(LokiBudget)의 시간창 롤오버용 시계 — 테스트는 고정/가변 Clock 주입(doc/33 §3). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
