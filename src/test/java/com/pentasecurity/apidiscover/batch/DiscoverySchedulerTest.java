// DiscoveryScheduler.scanTick 단위 테스트 — 스케줄 커서 전진(skip·실패)·예산 이월·due=now+effectiveInterval·off-peak 윈도우 (doc/33 §4–5, D48)
package com.pentasecurity.apidiscover.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.ingest.LokiBudget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoverySchedulerTest {

    private static final Instant NOON = Instant.parse("2026-06-26T12:00:00Z");     // peak
    private static final Instant OFF_PEAK = Instant.parse("2026-06-26T03:00:00Z"); // off-peak(UTC)
    private static final Duration PEAK_MAX_WINDOW = Duration.ofMinutes(30);
    private static final Duration OFF_PEAK_MAX_WINDOW = Duration.ofHours(24);
    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(2); // lastSeenAt null → default tier

    private final ScanSelector selector = mock(ScanSelector.class);
    private final DiscoveryJobService jobService = mock(DiscoveryJobService.class);
    private final DomainConfigRepository domains = mock(DomainConfigRepository.class);
    private final LokiBudget budget = mock(LokiBudget.class);

    private DiscoveryScheduler scheduler(boolean tiering, Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new DiscoveryScheduler(selector, jobService, domains, budget, props(tiering), clock);
    }

    @Test
    void advancesScheduleCursorPerAttemptIncludingScanThatSkipsOrFails() {
        when(budget.hasBudget()).thenReturn(true);
        when(selector.selectForTick()).thenReturn(List.of(domain("a.com"), domain("b.com"), domain("c.com")));
        doThrow(new IllegalStateException("loki down")).when(jobService).runScan(eq("b.com"), any());

        scheduler(true, NOON).scanTick();

        // ★스케줄 커서 전진은 attempt 마다(성공·skip·실패 무관) — 3건 모두
        verify(domains).touchScanSchedule(eq("a.com"), any(), any());
        verify(domains).touchScanSchedule(eq("b.com"), any(), any());
        verify(domains).touchScanSchedule(eq("c.com"), any(), any());
        verify(jobService).runScan(eq("a.com"), any());
        verify(jobService).runScan(eq("b.com"), any());
        verify(jobService).runScan(eq("c.com"), any()); // b 실패가 c 를 막지 않음
    }

    @Test
    void stopsTickWhenBudgetExhaustedAndDefersRest() {
        when(budget.hasBudget()).thenReturn(true, false);
        when(selector.selectForTick()).thenReturn(List.of(domain("a.com"), domain("b.com"), domain("c.com")));

        scheduler(true, NOON).scanTick();

        verify(domains).touchScanSchedule(eq("a.com"), any(), any());
        verify(jobService).runScan(eq("a.com"), any());
        // 예산 소진 → b·c 는 커서 전진·스캔 모두 안 함(다음 틱 이월)
        verify(domains, never()).touchScanSchedule(eq("b.com"), any(), any());
        verify(jobService, never()).runScan(eq("b.com"), any());
        verify(jobService, times(1)).runScan(any(), any());
    }

    @Test
    void schedulesNextDueAtNowPlusEffectiveInterval() {
        when(budget.hasBudget()).thenReturn(true);
        when(selector.selectForTick()).thenReturn(List.of(domain("a.com"))); // lastSeenAt null → default PT2H

        scheduler(true, NOON).scanTick();

        verify(domains).touchScanSchedule(eq("a.com"), eq(NOON), eq(NOON.plus(DEFAULT_INTERVAL)));
    }

    @Test
    void tieringDisabledSchedulesDueAtNowMatchingPr1Lrs() {
        when(budget.hasBudget()).thenReturn(true);
        when(selector.selectForTick()).thenReturn(List.of(domain("a.com")));

        scheduler(false, NOON).scanTick(); // tiering off → effectiveInterval=ZERO → nextDue=now

        verify(domains).touchScanSchedule(eq("a.com"), eq(NOON), eq(NOON));
    }

    @Test
    void injectsPeakOrOffPeakMaxWindowIntoRunScan() {
        when(budget.hasBudget()).thenReturn(true);
        when(selector.selectForTick()).thenReturn(List.of(domain("a.com")));

        scheduler(true, NOON).scanTick();
        verify(jobService).runScan("a.com", PEAK_MAX_WINDOW); // peak

        scheduler(true, OFF_PEAK).scanTick();
        verify(jobService).runScan("a.com", OFF_PEAK_MAX_WINDOW); // off-peak 윈도우 상향
    }

    private static DomainConfig domain(String host) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(true);
        return d; // lastSeenAt null → default 티어
    }

    private static ApiDiscoverProperties props(boolean tiering) {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://loki.local:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(1)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 0, "^x$"),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), 100, PEAK_MAX_WINDOW, 0, 0L, true,
                        Duration.ZERO, 0, tiering, Duration.ofMinutes(30), DEFAULT_INTERVAL, Duration.ofHours(6),
                        Duration.ofHours(24), 500, OFF_PEAK_MAX_WINDOW, "UTC",
                        Duration.ofDays(14), Duration.ofDays(1)));
    }
}
