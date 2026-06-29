// CliRegisterRunner.register() 단위 테스트 — 신규 등록·멱등(이미 존재)·정규화·도메인누락·DB오류 exit code (doc/33 §7)
package com.pentasecurity.apidiscover.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.batch.DomainRegistrar;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

class CliRegisterRunnerTest {

    private static final String HOST = "foo.com";

    private final DomainRegistrar registrar = mock(DomainRegistrar.class);
    private final DomainConfigRepository domainRepo = mock(DomainConfigRepository.class);
    private final ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);

    @Test
    void registersNewDomainReturnsOk() {
        when(domainRepo.existsById(HOST)).thenReturn(false);
        when(registrar.registerIfAbsent(HOST)).thenReturn(cfg(HOST));
        assertThat(runner(HOST).register()).isEqualTo(CliRegisterRunner.EXIT_OK);
        verify(registrar).registerIfAbsent(HOST);
    }

    @Test
    void existingDomainIsIdempotentReturnsOk() {
        when(domainRepo.existsById(HOST)).thenReturn(true); // 이미 등록 → no-op 메시지·성공
        when(registrar.registerIfAbsent(HOST)).thenReturn(cfg(HOST));
        assertThat(runner(HOST).register()).isEqualTo(CliRegisterRunner.EXIT_OK);
    }

    @Test
    void normalizesUppercaseBeforeRegister() {
        when(domainRepo.existsById(HOST)).thenReturn(false);
        when(registrar.registerIfAbsent(HOST)).thenReturn(cfg(HOST));
        assertThat(runner("FOO.COM").register()).isEqualTo(CliRegisterRunner.EXIT_OK);
        verify(registrar).registerIfAbsent(HOST); // 정규화 "foo.com" 으로 등록
    }

    @Test
    void blankDomainReturnsNoDomain() {
        assertThat(runner("  ").register()).isEqualTo(CliRegisterRunner.EXIT_NO_DOMAIN);
        verify(registrar, never()).registerIfAbsent(any());
    }

    @Test
    void dbFailureReturnsExitDb() {
        when(domainRepo.existsById(HOST)).thenReturn(false);
        when(registrar.registerIfAbsent(HOST)).thenThrow(new IllegalStateException("db down"));
        assertThat(runner(HOST).register()).isEqualTo(CliRegisterRunner.EXIT_DB);
    }

    private CliRegisterRunner runner(String registerDomain) {
        CliProperties props = new CliProperties(null, "/exports", null, null, null, false, registerDomain);
        return new CliRegisterRunner(registrar, domainRepo, props, ctx);
    }

    private static DomainConfig cfg(String host) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        return d;
    }
}
