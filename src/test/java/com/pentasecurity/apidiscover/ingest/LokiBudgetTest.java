// LokiBudget 단위 테스트 — 시간당 쿼리/바이트 하드캡·시간창 롤오버 리셋 (doc/33 §3 E)
package com.pentasecurity.apidiscover.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LokiBudgetTest {

    /** 가변 시계 — 시간창 롤오버 테스트용. */
    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant start) { this.instant = start; }
        void advance(Duration d) { this.instant = this.instant.plus(d); }
        @Override public Instant instant() { return instant; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    @Test
    void queryCapBlocksThenResetsNextHour() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-26T10:00:00Z"));
        LokiBudget budget = new LokiBudget(props(3, 0), new SimpleMeterRegistry(), clock);

        assertThat(budget.hasBudget()).isTrue();
        budget.record(200, 100);
        budget.record(200, 100);
        assertThat(budget.hasBudget()).isTrue();   // 2 < 3
        budget.record(200, 100);
        assertThat(budget.hasBudget()).isFalse();  // 3 >= 3 캡 → 이월

        clock.advance(Duration.ofHours(1));         // 시간창 롤오버
        assertThat(budget.hasBudget()).isTrue();    // 리셋
    }

    @Test
    void byteCapBlocks() {
        LokiBudget budget = new LokiBudget(props(0, 500),
                new SimpleMeterRegistry(), Clock.fixed(Instant.parse("2026-06-26T10:00:00Z"), ZoneOffset.UTC));
        budget.record(200, 400);
        assertThat(budget.hasBudget()).isTrue();
        budget.record(200, 200);                    // 누적 600 >= 500
        assertThat(budget.hasBudget()).isFalse();
    }

    @Test
    void zeroMeansUnlimited() {
        LokiBudget budget = new LokiBudget(props(0, 0),
                new SimpleMeterRegistry(), Clock.systemUTC());
        for (int i = 0; i < 100; i++) {
            budget.record(200, 10_000);
        }
        assertThat(budget.hasBudget()).isTrue();     // 0=무제한
    }

    @Test
    void recordsMicrometerCountersIncludingErrors() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LokiBudget budget = new LokiBudget(props(0, 0), registry,
                Clock.fixed(Instant.parse("2026-06-26T10:00:00Z"), ZoneOffset.UTC));
        budget.record(200, 1000);
        budget.record(429, 50);
        budget.record(503, 50);

        assertThat(registry.get("loki.queries").counter().count()).isEqualTo(3.0);
        assertThat(registry.get("loki.response.bytes").counter().count()).isEqualTo(1100.0);
        assertThat(registry.get("loki.errors").tag("status", "429").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("loki.errors").tag("status", "503").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordFailureCountsTowardQueryCapAndErrorMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LokiBudget budget = new LokiBudget(props(2, 0), registry,
                Clock.fixed(Instant.parse("2026-06-26T10:00:00Z"), ZoneOffset.UTC));
        budget.recordFailure("io");                 // D58: I/O 실패도 1쿼리로 계상 → 무한 hammering 방지
        assertThat(budget.hasBudget()).isTrue();    // 1 < 2
        budget.recordFailure("io");
        assertThat(budget.hasBudget()).isFalse();   // 2 >= 2 캡 (실패도 시간당 예산 소진)
        assertThat(registry.get("loki.queries").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("loki.errors").tag("status", "io").counter().count()).isEqualTo(2.0);
    }

    private static ApiDiscoverProperties props(int maxQueries, long maxBytes) {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://loki.local:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(1)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 0, "^x$", java.util.List.of()),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), 100, Duration.ZERO,
                        maxQueries, maxBytes, true, Duration.ZERO, 0, false, Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(24), 500, Duration.ofHours(24), "", Duration.ofDays(14), Duration.ofDays(1), Duration.ZERO));
    }
}
