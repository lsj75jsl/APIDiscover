// API Discovery Worker 서비스의 Spring Boot 진입점
package com.pentasecurity.apidiscover;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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

    public static void main(String[] args) {
        CliArgs cli = parseCli(args);
        if (cli.usageError()) {
            System.err.println(
                    "usage: -domain (-ls | -register <도메인> | -export <도메인> | -scan <도메인> [-window <ISO8601>] [-edge <hostname>])");
            System.exit(2);
            return;
        }
        if (cli.inject() != null) {
            // CLI 모드: 웹 미기동 + "cli" 프로파일(SchedulingConfig 제외) → 1 명령 실행 후 종료(doc/31 B1).
            // 신문법(-domain 서브커맨드)을 내부 프로퍼티(--adc.cli.*)로 translate 후 주입 — 런너/바인딩 불변(doc/33 §15, D47).
            new SpringApplicationBuilder(ApiDiscoverWorkerApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("cli")
                    .run(append(args, cli.inject()));
        } else {
            SpringApplication.run(ApiDiscoverWorkerApplication.class, args);
        }
    }

    /**
     * main() 신문법(전부 단일대시 {@code -domain} 서브커맨드) 파싱 결과(doc/33 §15, D47). 순수(static·System.exit 없음) → 단위 테스트 가능.
     * {@code inject==null && !usageError}=서버 모드(-domain 없음), {@code inject!=null}=CLI 모드(주입할 --adc.cli.* 인자),
     * {@code usageError}=문법 오류(-domain 단독·도메인 누락) → main 이 usage+exit(2).
     */
    record CliArgs(boolean usageError, String[] inject) {
        static CliArgs server() {
            return new CliArgs(false, null);
        }

        static CliArgs error() {
            return new CliArgs(true, null);
        }

        static CliArgs run(String... inject) {
            return new CliArgs(false, inject);
        }
    }

    /**
     * 신문법 → 주입 인자(doc/33 §15, D47):
     * {@code -domain -ls} / {@code -domain -register <도메인>} / {@code -domain -export <도메인>} /
     * {@code -domain -scan <도메인> [-window <ISO8601>] [-edge <hostname>]}.
     * {@code -domain} 없으면 서버 모드. {@code -domain} 만(서브커맨드 없음)·{@code -register}/{@code -export}/{@code -scan} 도메인 누락 = 문법 오류.
     */
    static CliArgs parseCli(String[] args) {
        if (!has(args, "-domain")) {
            return CliArgs.server(); // -domain 없음 = 서버 모드(무회귀)
        }
        int subcommands = (has(args, "-ls") ? 1 : 0) + (has(args, "-register") ? 1 : 0)
                + (has(args, "-export") ? 1 : 0) + (has(args, "-scan") ? 1 : 0);
        if (subcommands > 1) {
            return CliArgs.error(); // 복수 서브커맨드 모호 → fail-loud(조용히 하나 선택 금지)
        }
        if (has(args, "-ls")) {
            return CliArgs.run("--adc.cli.list-domains=true");
        }
        if (has(args, "-register")) {
            String domain = valueAfter(args, "-register");
            return (domain == null) ? CliArgs.error() : CliArgs.run("--adc.cli.register-domain=" + domain);
        }
        if (has(args, "-export")) {
            String domain = valueAfter(args, "-export");
            return (domain == null) ? CliArgs.error() : CliArgs.run("--adc.cli.export-domain=" + domain);
        }
        if (has(args, "-scan")) {
            String domain = valueAfter(args, "-scan");
            if (domain == null) {
                return CliArgs.error();
            }
            List<String> inject = new ArrayList<>();
            inject.add("--adc.cli.scan-domain=" + domain);
            String window = valueAfter(args, "-window"); // 없으면 미주입(런너 기본=max-window)
            if (window != null) {
                inject.add("--adc.cli.window=" + window);
            }
            String edge = valueAfter(args, "-edge");      // 없으면 미주입(런너 기본=전체 hostname)
            if (edge != null) {
                inject.add("--adc.cli.edge=" + edge);
            }
            return CliArgs.run(inject.toArray(new String[0]));
        }
        return CliArgs.error(); // -domain 만(서브커맨드 없음)
    }

    private static boolean has(String[] args, String flag) {
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /** flag 바로 뒤 토큰(positional 값). 없거나 다음 토큰이 다른 플래그(`-`로 시작)면 null = 값 누락. */
    private static String valueAfter(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                String next = args[i + 1];
                return next.startsWith("-") ? null : next;
            }
        }
        return null;
    }

    private static String[] append(String[] args, String[] extra) {
        String[] out = java.util.Arrays.copyOf(args, args.length + extra.length);
        System.arraycopy(extra, 0, out, args.length, extra.length);
        return out;
    }

    /** 레이트 예산(LokiBudget)의 시간창 롤오버용 시계 — 테스트는 고정/가변 Clock 주입(doc/33 §3). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
