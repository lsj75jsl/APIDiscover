// DiscoveryScheduler.scanTick 단위 테스트 — 커서 전진(skip 포함·실패 포함)·예산 소진 이월 (doc/33 §1 B, §3 E)
package com.pentasecurity.apidiscover.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.ingest.LokiBudget;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoverySchedulerTest {

    private final ScanSelector selector = mock(ScanSelector.class);
    private final DiscoveryJobService jobService = mock(DiscoveryJobService.class);
    private final DomainConfigRepository domains = mock(DomainConfigRepository.class);
    private final LokiBudget budget = mock(LokiBudget.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-26T10:00:00Z"), ZoneOffset.UTC);

    private DiscoveryScheduler scheduler() {
        return new DiscoveryScheduler(selector, jobService, domains, budget, clock);
    }

    @Test
    void advancesCursorPerAttemptIncludingScanThatSkipsOrFails() {
        when(budget.hasBudget()).thenReturn(true);
        when(selector.selectForTick()).thenReturn(List.of(domain("a.com"), domain("b.com"), domain("c.com")));
        // b 의 runScan 이 예외(스캔 실패)여도 커서는 전진하고 c 로 진행(도메인 격리)
        doThrow(new IllegalStateException("loki down")).when(jobService).runScan("b.com");

        scheduler().scanTick();

        // ★커서 전진은 attempt 마다(성공·skip·실패 무관) — 3건 모두
        verify(domains).touchLastScanAttempt(eq("a.com"), any());
        verify(domains).touchLastScanAttempt(eq("b.com"), any());
        verify(domains).touchLastScanAttempt(eq("c.com"), any());
        verify(jobService).runScan("a.com");
        verify(jobService).runScan("b.com");
        verify(jobService).runScan("c.com"); // b 실패가 c 를 막지 않음
    }

    @Test
    void stopsTickWhenBudgetExhaustedAndDefersRest() {
        // 첫 도메인은 예산 있음, 둘째부터 소진 → 둘째 이후 미처리(이월)
        when(budget.hasBudget()).thenReturn(true, false);
        when(selector.selectForTick()).thenReturn(List.of(domain("a.com"), domain("b.com"), domain("c.com")));

        scheduler().scanTick();

        verify(domains).touchLastScanAttempt(eq("a.com"), any());
        verify(jobService).runScan("a.com");
        // 예산 소진 → b·c 는 커서 전진·스캔 모두 안 함(다음 틱 이월)
        verify(domains, never()).touchLastScanAttempt(eq("b.com"), any());
        verify(jobService, never()).runScan("b.com");
        verify(jobService, times(1)).runScan(any());
    }

    private static DomainConfig domain(String host) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(true);
        return d;
    }
}
