// CLI 도메인 목록 — 수집 도메인을 CSV 파일(output-dir)로 내보냄(read-only, Loki 무관) (doc/33 §15, D47)
package com.pentasecurity.apidiscover.cli;

import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * {@code domainRepo.findAll(Sort host)} → CSV 파일({@code output-dir}/domains-&lt;stamp&gt;.csv, export-domain 동형). 빈 목록=헤더만·exit 0. Loki 무관.
 */
@Component
@Profile("cli")
public class CliListRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliListRunner.class);

    static final int EXIT_OK = 0;
    static final int EXIT_DB = 4;
    static final int EXIT_IO = 4;

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
        System.exit(SpringApplication.exit(context, () -> list(System.currentTimeMillis())));
    }

    /** 목록 CSV 파일 작성 본체 — exit code 반환(System.exit 안 함, 테스트 가능). stamp=파일명 타임스탬프. */
    int list(long stamp) {
        List<DomainConfig> domains;
        try {
            domains = domainRepo.findAll(Sort.by("host"));
        } catch (RuntimeException e) {
            log.warn("domain list query failed", e);
            System.err.println("도메인 목록 조회 실패: " + e.getMessage());
            return EXIT_DB;
        }
        String csv = DomainCsvWriter.domainsToCsv(domains);
        try {
            Path dir = Path.of(props.outputDir());
            Files.createDirectories(dir);
            Path file = dir.resolve("domains-" + stamp + ".csv");
            Files.writeString(file, csv);
            log.info("exported {} domains → {}", domains.size(), file);
            System.out.println(file);
            return EXIT_OK;
        } catch (IOException e) {
            System.err.println("CSV 쓰기 실패: " + e.getMessage());
            return EXIT_IO;
        }
    }
}
