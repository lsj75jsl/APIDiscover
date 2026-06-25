// CLI 내보내기 실행체 — 한 도메인 결합 Discovery → CSV 파일 + exit code (doc/31 B1)
package com.pentasecurity.apidiscover.cli;

import com.pentasecurity.apidiscover.batch.CombinedDiscoveryService;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRecord;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRepository;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code --adc.cli.export-domain=<domain>} → {@link CombinedDiscoveryService#forHost} → CSV(doc/31 §B2) → 파일 + exit code.
 * {@code @Profile("cli")} 라 서버 모드 미로딩(무회귀). 웹·스케줄 미기동 → 1 명령 후 명시 exit 로 종료.
 */
@Component
@Profile("cli")
public class CliExportRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliExportRunner.class);

    static final int EXIT_OK = 0;
    static final int EXIT_NO_DOMAIN = 2;
    static final int EXIT_EMPTY = 3;
    static final int EXIT_IO = 4;

    private final CombinedDiscoveryService discovery;
    private final DiscoveredEndpointRepository discoveredRepo;
    private final CliProperties props;
    private final ConfigurableApplicationContext context;

    public CliExportRunner(CombinedDiscoveryService discovery, DiscoveredEndpointRepository discoveredRepo,
                           CliProperties props, ConfigurableApplicationContext context) {
        this.discovery = discovery;
        this.discoveredRepo = discoveredRepo;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        int code = export(System.currentTimeMillis());
        // 웹/스케줄 미기동(비데몬 스레드 없음) → 자연 종료. 명시 exit 로 코드 보장(Spring 컨텍스트 정리 포함).
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /** 내보내기 본체 — exit code 반환(System.exit 안 함, 테스트 가능). stamp=파일명 타임스탬프. */
    int export(long stamp) {
        String domain = props.exportDomain();
        if (domain == null || domain.isBlank()) {
            System.err.println("adc.cli.export-domain 미지정 — 내보낼 도메인이 없습니다");
            return EXIT_NO_DOMAIN;
        }
        CombinedDiscovery combined = discovery.forHost(domain);
        if (combined.findings().isEmpty()) {
            System.err.println("도메인 '" + domain + "' 의 엔드포인트가 없습니다(미존재 또는 검출 0건)");
            return EXIT_EMPTY;
        }

        Map<String, DiscoveredEndpointRecord> bySig = new LinkedHashMap<>();
        for (DiscoveredEndpointRecord r : discoveredRepo.findByHost(domain)) {
            bySig.put(DomainCsvWriter.key(r.getMethod(), r.getHost(), r.getPathTemplate()), r);
        }
        String csv = DomainCsvWriter.toCsv(combined.findings(), bySig);

        try {
            Path dir = Path.of(props.outputDir());
            Files.createDirectories(dir);
            Path file = dir.resolve(domain + "-" + stamp + ".csv");
            Files.writeString(file, csv);
            log.info("exported {} findings for {} → {}", combined.findings().size(), domain, file);
            System.out.println(file);
            return EXIT_OK;
        } catch (IOException e) {
            System.err.println("CSV 쓰기 실패: " + e.getMessage());
            return EXIT_IO;
        }
    }
}
