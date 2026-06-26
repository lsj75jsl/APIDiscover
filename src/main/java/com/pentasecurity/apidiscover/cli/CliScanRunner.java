// CLI 온디맨드 스캔 — --adc.cli.scan-domain 즉시 스캔 → 요약 + exit code (doc/33 §7)
package com.pentasecurity.apidiscover.cli;

import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code --adc.cli.scan-domain=<domain>} [--adc.cli.window=PT1H] [--adc.cli.edge=<hostname>] → 즉시 스캔(doc/33 §7).
 * runOnDemand/scanOnDemand 재사용(B 패턴, @Profile cli·web NONE). ★watermark 미전진(임시 스냅샷). 스캔 후 검출/분류 요약 + exit code.
 */
@Component
@Profile("cli")
public class CliScanRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliScanRunner.class);

    static final int EXIT_OK = 0;
    static final int EXIT_NO_DOMAIN = 2;
    static final int EXIT_NOT_SCANNABLE = 3; // 도메인 미존재·미enabled
    static final int EXIT_LOKI = 4;          // Loki 수집/분석 실패

    private final DiscoveryJobService jobService;
    private final DomainConfigRepository domainRepo;
    private final CliProperties props;
    private final ConfigurableApplicationContext context;

    public CliScanRunner(DiscoveryJobService jobService, DomainConfigRepository domainRepo,
                         CliProperties props, ConfigurableApplicationContext context) {
        this.jobService = jobService;
        this.domainRepo = domainRepo;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        // scan-domain 미지정 = 이 명령 대상 아님(export-domain 등) → no-op(다른 runner 가 처리)
        if (props.scanDomain() == null || props.scanDomain().isBlank()) {
            return;
        }
        System.exit(SpringApplication.exit(context, this::scan));
    }

    /** 온디맨드 스캔 본체 — exit code 반환(System.exit 안 함, 테스트 가능). */
    int scan() {
        String domain = props.scanDomain();
        if (domain == null || domain.isBlank()) {
            System.err.println("adc.cli.scan-domain 미지정 — 스캔할 도메인이 없습니다");
            return EXIT_NO_DOMAIN;
        }
        DomainConfig cfg = domainRepo.findById(domain).orElse(null);
        if (cfg == null || !cfg.isEnabled()) {
            System.err.println("도메인 '" + domain + "' 미존재 또는 비활성(enabled=false) — 스캔 불가");
            return EXIT_NOT_SCANNABLE;
        }
        try {
            LogWindow window = jobService.onDemandWindow(props.window()); // 상한=scan.max-window
            ScanResult r = jobService.scanOnDemand(domain, window, props.edge()); // ★watermark 미전진
            System.out.printf("scan %s [%s ~ %s]%s%n", domain, window.from(), window.to(),
                    (props.edge() != null && !props.edge().isBlank()) ? " edge=" + props.edge() : "");
            System.out.printf("  discovered=%d active=%d shadow=%d zombie=%d unused=%d%n",
                    r.getDiscovered(), r.getActive(), r.getShadow(), r.getZombie(), r.getUnused());
            return EXIT_OK;
        } catch (RuntimeException e) {
            log.warn("on-demand scan failed for {}", domain, e);
            System.err.println("스캔 실패: " + e.getMessage());
            return EXIT_LOKI;
        }
    }
}
