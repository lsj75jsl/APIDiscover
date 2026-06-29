// CliScanRunner.scan() 단위 테스트 — 미등록=자동등록 후 스캔·비활성=스캔불가(자동활성 안함)·정규화·exit code·scanOnDemand 위임 (doc/33 §7)
package com.pentasecurity.apidiscover.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
import com.pentasecurity.apidiscover.batch.DomainRegistrar;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

class CliScanRunnerTest {

    private static final String HOST = "api.example.com";

    private final DiscoveryJobService jobService = mock(DiscoveryJobService.class);
    private final DomainConfigRepository domainRepo = mock(DomainConfigRepository.class);
    private final DomainRegistrar registrar = mock(DomainRegistrar.class);
    private final ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);

    @Test
    void autoRegistersThenScansWhenDomainAbsent() {
        when(domainRepo.findById(HOST)).thenReturn(Optional.empty());
        when(registrar.registerIfAbsent(HOST)).thenReturn(domain(HOST, true));
        LogWindow w = stubWindowAndScan(HOST);

        assertThat(runner(HOST, null, null).scan()).isEqualTo(CliScanRunner.EXIT_OK);
        verify(registrar).registerIfAbsent(HOST);           // 미등록 → 자동등록(enabled=true)
        verify(jobService).scanOnDemand(eq(HOST), eq(w), any()); // 그 뒤 스캔
    }

    @Test
    void disabledDomainIsNotScannableAndNotAutoActivated() {
        when(domainRepo.findById(HOST)).thenReturn(Optional.of(domain(HOST, false)));
        assertThat(runner(HOST, null, null).scan()).isEqualTo(CliScanRunner.EXIT_NOT_SCANNABLE);
        verify(registrar, never()).registerIfAbsent(any()); // 비활성=운영자 결정 존중, 자동 활성화 안 함
    }

    @Test
    void scansAndReturnsZeroDelegatingToScanOnDemand() {
        when(domainRepo.findById(HOST)).thenReturn(Optional.of(domain(HOST, true)));
        LogWindow w = stubWindowAndScan(HOST);

        assertThat(runner(HOST, Duration.ofHours(1), "EDGE1").scan()).isEqualTo(CliScanRunner.EXIT_OK);
        // ★watermark 미전진 경로(scanOnDemand) 위임, edge 전달
        verify(jobService).scanOnDemand(HOST, w, "EDGE1");
        verify(registrar, never()).registerIfAbsent(any()); // 기존·enabled = 자동등록 불요
    }

    @Test
    void normalizesScanDomainBeforeLookupAndScan() {
        // 대문자 입력 → 정규화 host("api.example.com")로 findById/scanOnDemand (자동등록 중복키·Loki 쿼리 정합)
        when(domainRepo.findById(HOST)).thenReturn(Optional.of(domain(HOST, true)));
        LogWindow w = stubWindowAndScan(HOST);

        assertThat(runner("API.Example.COM", null, null).scan()).isEqualTo(CliScanRunner.EXIT_OK);
        verify(jobService).scanOnDemand(HOST, w, null);
    }

    @Test
    void returnsNonZeroOnLokiFailure() {
        when(domainRepo.findById(HOST)).thenReturn(Optional.of(domain(HOST, true)));
        when(jobService.onDemandWindow(any())).thenReturn(
                new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(60)));
        when(jobService.scanOnDemand(any(), any(), any())).thenThrow(new IllegalStateException("Loki query failed"));

        assertThat(runner(HOST, null, null).scan()).isEqualTo(CliScanRunner.EXIT_LOKI);
    }

    @Test
    void blankDomainReturnsNoDomain() {
        assertThat(runner("  ", null, null).scan()).isEqualTo(CliScanRunner.EXIT_NO_DOMAIN);
    }

    // --- helpers ---

    private LogWindow stubWindowAndScan(String host) {
        LogWindow w = new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
        when(jobService.onDemandWindow(any())).thenReturn(w);
        when(jobService.scanOnDemand(eq(host), eq(w), any())).thenReturn(scanResult());
        return w;
    }

    private CliScanRunner runner(String scanDomain, Duration window, String edge) {
        CliProperties props = new CliProperties(null, "/exports", scanDomain, window, edge, false, null);
        return new CliScanRunner(jobService, domainRepo, registrar, props, ctx);
    }

    private static DomainConfig domain(String host, boolean enabled) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(enabled);
        return d;
    }

    private static ScanResult scanResult() {
        ScanResult r = new ScanResult();
        r.setHost(HOST);
        r.setDiscovered(5);
        r.setShadow(2);
        return r;
    }
}
