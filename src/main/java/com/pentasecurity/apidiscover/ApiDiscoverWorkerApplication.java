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
        if (isListDomains(args)) {
            // 단일대시 `-domain -ls`(사용자 지정 형식, doc/33 §15) → CLI 모드 + list-domains 프로퍼티 주입(내부 일관)
            new SpringApplicationBuilder(ApiDiscoverWorkerApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("cli")
                    .run(append(args, "--adc.cli.list-domains=true"));
        } else if (isCliMode(args)) {
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

    /** 단일대시 `-domain` AND `-ls` 동시 존재(Spring 미바인딩 non-option arg) → 도메인 목록 모드(doc/33 §15). */
    static boolean isListDomains(String[] args) {
        boolean domain = false;
        boolean ls = false;
        for (String a : args) {
            if ("-domain".equals(a)) {
                domain = true;
            } else if ("-ls".equals(a)) {
                ls = true;
            }
        }
        return domain && ls;
    }

    private static String[] append(String[] args, String extra) {
        String[] out = java.util.Arrays.copyOf(args, args.length + 1);
        out[args.length] = extra;
        return out;
    }

    /** 레이트 예산(LokiBudget)의 시간창 롤오버용 시계 — 테스트는 고정/가변 Clock 주입(doc/33 §3). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
