// CLI 도메인 즉시 등록 — --adc.cli.register-domain 을 DB 에 enabled=true 로 등록(멱등, Loki 미호출) (doc/33 §7)
package com.pentasecurity.apidiscover.cli;

import com.pentasecurity.apidiscover.batch.DomainRegistrar;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.util.DomainNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code --adc.cli.register-domain=<domain>} → 정규화 host 로 등록(없으면 enabled=true, 있으면 no-op). 멱등이라 "이미 존재"=성공(0).
 * 신규 도메인을 스캔 전에 미리 잡아두는 용도(Loki 미호출). 등록 로직은 {@link DomainRegistrar} 공유(-scan 자동등록과 동일 규칙).
 */
@Component
@Profile("cli")
public class CliRegisterRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRegisterRunner.class);

    static final int EXIT_OK = 0;
    static final int EXIT_NO_DOMAIN = 2; // 도메인 누락(정규화 후 null)
    static final int EXIT_DB = 4;        // 등록 실패(DB 오류 등)

    private final DomainRegistrar registrar;
    private final DomainConfigRepository domainRepo;
    private final CliProperties props;
    private final ConfigurableApplicationContext context;

    public CliRegisterRunner(DomainRegistrar registrar, DomainConfigRepository domainRepo,
                             CliProperties props, ConfigurableApplicationContext context) {
        this.registrar = registrar;
        this.domainRepo = domainRepo;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        // register-domain 미지정 = 이 명령 대상 아님 → no-op(다른 runner 가 처리)
        if (props.registerDomain() == null || props.registerDomain().isBlank()) {
            return;
        }
        System.exit(SpringApplication.exit(context, this::register));
    }

    /** 등록 본체 — exit code 반환(System.exit 안 함, 테스트 가능). */
    int register() {
        String host = DomainNames.normalize(props.registerDomain());
        if (host == null) {
            System.err.println("adc.cli.register-domain 미지정 — 등록할 도메인이 없습니다");
            return EXIT_NO_DOMAIN;
        }
        try {
            boolean existed = domainRepo.existsById(host); // 멱등 메시지 구분용 사전 판정
            registrar.registerIfAbsent(host);
            System.out.println(existed
                    ? "도메인 '" + host + "' 이미 등록됨 (no-op)"
                    : "도메인 '" + host + "' 등록 완료 (enabled=true)");
            return EXIT_OK;
        } catch (RuntimeException e) {
            log.warn("domain register failed for {}", host, e);
            System.err.println("등록 실패: " + e.getMessage());
            return EXIT_DB;
        }
    }
}
