// CLI 도메인 목록 — 수집 도메인을 stdout 출력(read-only, Loki 무관) (doc/33 §15, D47)
package com.pentasecurity.apidiscover.cli;

import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * 운영자 도메인 목록 확인 — main() 이 raw-arg {@code -domain -ls} 감지 시 {@code --adc.cli.list-domains=true} 주입(doc/33 §15).
 * {@code domainRepo.findAll()} → stdout(host·enabled·#hostnames·discovered_at·last_seen_at). 빈 목록=정상 exit 0, DB 오류=비0. Loki 무관.
 */
@Component
@Profile("cli")
public class CliListRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliListRunner.class);

    static final int EXIT_OK = 0;
    static final int EXIT_DB = 4;

    private final DomainConfigRepository domainRepo;
    private final CliProperties props;
    private final ConfigurableApplicationContext context;

    public CliListRunner(DomainConfigRepository domainRepo, CliProperties props,
                         ConfigurableApplicationContext context) {
        this.domainRepo = domainRepo;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        // list-domains 미요청 = 이 명령 대상 아님 → no-op(다른 CLI 명령일 수 있음)
        if (!props.listDomains()) {
            return;
        }
        System.exit(SpringApplication.exit(context, this::list));
    }

    /** 목록 출력 본체 — exit code 반환(System.exit 안 함, 테스트 가능). */
    int list() {
        try {
            List<DomainConfig> domains = domainRepo.findAll(Sort.by("host"));
            System.out.printf("%-40s %-8s %-11s %-26s %-26s%n",
                    "host", "enabled", "#hostnames", "discovered_at", "last_seen_at");
            for (DomainConfig d : domains) {
                System.out.printf("%-40s %-8s %-11d %-26s %-26s%n",
                        d.getHost(), d.isEnabled(),
                        (d.getHostnames() != null) ? d.getHostnames().size() : 0,
                        String.valueOf(d.getDiscoveredAt()), String.valueOf(d.getLastSeenAt()));
            }
            System.out.println("(" + domains.size() + " domains)");
            return EXIT_OK;
        } catch (RuntimeException e) {
            log.warn("domain list failed", e);
            System.err.println("도메인 목록 조회 실패: " + e.getMessage());
            return EXIT_DB;
        }
    }
}
