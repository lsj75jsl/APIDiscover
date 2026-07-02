// collectBounded 단위 테스트 — 슬라이스 부분전진·gap-free·per-scan 캡·무제한 현행 (doc/33 §14 ①, D46)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.ingest.LokiBudget;
import com.pentasecurity.apidiscover.ingest.LokiClient;
import com.pentasecurity.apidiscover.ingest.LokiQueryBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ① 슬라이스 외부·hostname 내부 순회 + 부분 watermark 전진의 핵심 동작을 검증한다(doc/33 §14):
 * per-scan 캡/예산 소진 시 <b>마지막 완료 슬라이스 끝</b>까지만 consumedUpTo(gap-free·resume), 무제한이면 전 윈도우(현행 동치).
 */
class ScanSliceBoundedTest {

    private static final Instant T0 = Instant.parse("2026-06-26T00:00:00Z");
    private final List<LogWindow> queried = new ArrayList<>();
    private final LokiClient loki = mock(LokiClient.class);

    @Test
    void capStopsAtLastCompletedSliceBoundaryPartialAdvance() {
        // window 30m, slice 10m(3슬라이스), 2 hostname, cap=4쿼리 → 2슬라이스(4쿼리) 후 정지
        DiscoveryJobService.BoundedCollection bc = collectBounded(
                hostnames("E1", "E2"), window(30), 10, /*cap*/4, /*maxQueriesPerHour*/0);

        assertThat(bc.consumedUpTo()).isEqualTo(T0.plus(Duration.ofMinutes(20))); // 2슬라이스만(부분 전진)
        assertThat(bc.consumedUpTo()).isBefore(T0.plus(Duration.ofMinutes(30)));  // 윈도우 끝 못 감
        assertThat(bc.lines()).hasSize(4);                                        // 2슬라이스 × 2 hostname
        // gap-free: 수집 슬라이스는 [0,10)·[10,20) 만(consumedUpTo 초과 슬라이스 미시작), 각 hostname 완료
        assertThat(queried).allSatisfy(w -> assertThat(w.to()).isBeforeOrEqualTo(bc.consumedUpTo()));
        assertThat(queried.stream().map(LogWindow::to).distinct().toList())
                .containsExactly(T0.plus(Duration.ofMinutes(10)), T0.plus(Duration.ofMinutes(20)));
    }

    @Test
    void unlimitedCapConsumesWholeWindowLikeCurrent() {
        DiscoveryJobService.BoundedCollection bc = collectBounded(
                hostnames("E1", "E2"), window(30), 10, /*cap*/0, /*maxQueriesPerHour*/0);

        assertThat(bc.consumedUpTo()).isEqualTo(T0.plus(Duration.ofMinutes(30))); // 전 윈도우(현행 동치)
        assertThat(bc.lines()).hasSize(6);                                        // 3슬라이스 × 2 hostname
    }

    @Test
    void globalBudgetExhaustionAlsoPartialAdvances() {
        // maxQueriesPerHour=2 → 첫 슬라이스(2쿼리) 후 예산 소진 → 둘째 슬라이스 전 정지
        DiscoveryJobService.BoundedCollection bc = collectBounded(
                hostnames("E1", "E2"), window(30), 10, /*cap*/0, /*maxQueriesPerHour*/2);

        assertThat(bc.consumedUpTo()).isEqualTo(T0.plus(Duration.ofMinutes(10))); // 1슬라이스만
        assertThat(bc.lines()).hasSize(2);
    }

    // --- helpers ---

    private DiscoveryJobService.BoundedCollection collectBounded(
            DomainConfig cfg, LogWindow window, int sliceMin, int cap, int maxQueriesPerHour) {
        ApiDiscoverProperties props = props(sliceMin, cap, maxQueriesPerHour);
        // collectBounded 는 loki/queryBuilder/budget/props 만 사용 — 나머지 분석경로 의존은 null/미사용
        LokiBudget budget = new LokiBudget(props, new SimpleMeterRegistry(), Clock.systemUTC());
        // record() 가 budget 시간당 카운트 증가 → hasBudget 가 슬라이스 경계에서 소진 반영하도록 loki stub 이 budget.record 호출
        when(loki.queryRange(any(), any())).thenAnswer(inv -> {
            queried.add(inv.getArgument(1));
            budget.record(200, 10); // 전역 예산 누적(실제 LokiClient 가 응답마다 record 하는 것 모사)
            return List.of("line");
        });
        DiscoveryJobService svc = new DiscoveryJobService(
                null, null, null, null, null, null, null, null, null, null, null, null,
                loki, new LokiQueryBuilder(props), budget, null, props);
        return svc.collectBounded(cfg, window);
    }

    private static DomainConfig hostnames(String... edges) {
        DomainConfig d = new DomainConfig();
        d.setHost("api.example.com");
        d.setHostnames(new ArrayList<>(List.of(edges)));
        return d;
    }

    private static LogWindow window(int minutes) {
        return new LogWindow(T0, T0.plus(Duration.ofMinutes(minutes)));
    }

    private static ApiDiscoverProperties props(int sliceMin, int cap, int maxQueriesPerHour) {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://loki.local:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(0)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 0, "^x$", java.util.List.of()),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), 100, Duration.ofMinutes(30),
                        maxQueriesPerHour, 0L, true, Duration.ofMinutes(sliceMin), cap,
                        false, Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6),
                        Duration.ofHours(24), 500, Duration.ofHours(24), "", Duration.ofDays(14), Duration.ofDays(1), Duration.ZERO, 0));
    }
}
