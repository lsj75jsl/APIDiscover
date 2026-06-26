// CliScanRunner.scan() 단위 테스트 — exit code(미존재/미enabled=비0·성공=0·Loki실패=비0), scanOnDemand 위임 (doc/33 §7)
package com.pentasecurity.apidiscover.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
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
    private final ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);

    @Test
    void returnsNonZeroWhenDomainAbsent() {
        when(domainRepo.findById(HOST)).thenReturn(Optional.empty());
        assertThat(runner(HOST, null, null).scan()).isEqualTo(CliScanRunner.EXIT_NOT_SCANNABLE);
    }

    @Test
    void returnsNonZeroWhenDomainDisabled() {
        when(domainRepo.findById(HOST)).thenReturn(Optional.of(domain(false)));
        assertThat(runner(HOST, null, null).scan()).isEqualTo(CliScanRunner.EXIT_NOT_SCANNABLE);
    }

    @Test
    void scansAndReturnsZeroDelegatingToScanOnDemand() {
        when(domainRepo.findById(HOST)).thenReturn(Optional.of(domain(true)));
        LogWindow w = new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
        when(jobService.onDemandWindow(any())).thenReturn(w);
        when(jobService.scanOnDemand(eq(HOST), eq(w), any())).thenReturn(scanResult());

        assertThat(runner(HOST, Duration.ofHours(1), "EDGE1").scan()).isEqualTo(CliScanRunner.EXIT_OK);
        // ★watermark 미전진 경로(scanOnDemand) 위임, edge 전달
        verify(jobService).scanOnDemand(HOST, w, "EDGE1");
    }

    @Test
    void returnsNonZeroOnLokiFailure() {
        when(domainRepo.findById(HOST)).thenReturn(Optional.of(domain(true)));
        when(jobService.onDemandWindow(any())).thenReturn(
                new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(60)));
        when(jobService.scanOnDemand(any(), any(), any())).thenThrow(new IllegalStateException("Loki query failed"));

        assertThat(runner(HOST, null, null).scan()).isEqualTo(CliScanRunner.EXIT_LOKI);
    }

    // --- helpers ---

    private CliScanRunner runner(String scanDomain, Duration window, String edge) {
        CliProperties props = new CliProperties(null, "/exports", scanDomain, window, edge);
        return new CliScanRunner(jobService, domainRepo, props, ctx);
    }

    private static DomainConfig domain(boolean enabled) {
        DomainConfig d = new DomainConfig();
        d.setHost(HOST);
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
