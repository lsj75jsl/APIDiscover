// ScanSelector due 선택 테스트 — @DataJpaTest(H2): due 도래 술어·nullsFirst asc·K 상한·off-peak K 스위치 (doc/33 §4, D48)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.Watermark;
import com.pentasecurity.apidiscover.domain.WatermarkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class ScanSelectorTest {

    private static final Instant NOON = Instant.parse("2026-06-26T12:00:00Z"); // peak(01:00-06:00 밖)
    private static final Instant OFF_PEAK = Instant.parse("2026-06-26T03:00:00Z"); // off-peak(UTC zone)

    @Autowired
    private DomainConfigRepository repo;

    @Autowired
    private WatermarkRepository wmRepo;

    @Test
    void selectsDueDomainsNullsFirstAscExcludingFutureAndDisabled() {
        repo.save(domain("immediate.example.com", null, true));                             // null=즉시 due(맨 앞)
        repo.save(domain("overdue.example.com", Instant.parse("2026-06-26T11:00:00Z"), true)); // 과거=due
        repo.save(domain("future.example.com", Instant.parse("2026-06-26T13:00:00Z"), true));  // 미래=미due(제외)
        repo.save(domain("disabled.example.com", null, false));                             // enabled=false(제외)

        ScanSelector selector = new ScanSelector(repo, props(10, 10, "UTC"), fixed(NOON));
        var selected = selector.selectForTick().stream().map(DomainConfig::getHost).toList();

        assertThat(selected).containsExactly("immediate.example.com", "overdue.example.com"); // nulls-first, asc
        assertThat(selected).doesNotContain("future.example.com", "disabled.example.com");
    }

    @Test
    void limitsToKAtPeak() {
        repo.save(domain("a.example.com", null, true));
        repo.save(domain("b.example.com", null, true));
        repo.save(domain("c.example.com", null, true));

        ScanSelector selector = new ScanSelector(repo, props(2, 99, "UTC"), fixed(NOON)); // K=2 (peak)
        assertThat(selector.selectForTick()).hasSize(2);
    }

    @Test
    void offPeakRaisesKToOffPeakBudget() {
        repo.save(domain("a.example.com", null, true));
        repo.save(domain("b.example.com", null, true));
        repo.save(domain("c.example.com", null, true));

        ApiDiscoverProperties props = props(1, 3, "UTC"); // peak K=1, off-peak K=3
        assertThat(new ScanSelector(repo, props, fixed(NOON)).selectForTick()).hasSize(1);     // peak
        assertThat(new ScanSelector(repo, props, fixed(OFF_PEAK)).selectForTick()).hasSize(3); // off-peak
    }

    @Test
    void excludesDomainsWithLastSeenOlderThanInactiveAfter() {
        // inactive-after=30d(props 기본). NOON 기준 cutoff=NOON−30d. 게이트 기준=lastSeenAt(discovery 관측, D59).
        repo.save(domain("recent.example.com", null, true, NOON.minus(Duration.ofDays(5))));  // 5일 전 관측=활성→포함
        repo.save(domain("stale.example.com", null, true, NOON.minus(Duration.ofDays(40))));  // 40일 전=무접속→제외
        repo.save(domain("never-seen.example.com", null, true, null));                        // 미관측=제외 안 함(기회 부여)

        ScanSelector selector = new ScanSelector(repo, props(10, 10, "UTC"), fixed(NOON));
        var hosts = selector.selectForTick().stream().map(DomainConfig::getHost).toList();

        assertThat(hosts).contains("recent.example.com", "never-seen.example.com");
        assertThat(hosts).doesNotContain("stale.example.com"); // 마지막 관측 30일 초과 → 자동스캔 제외
    }

    @Test
    void prioritizesDomainsWithNewTrafficOverEarlierDueIdleOnes() {
        // D64: FIFO 라면 idle(더 이른 due)이 먼저지만, busy(워터마크 이후 신규 트래픽)가 우선 선발.
        repo.save(domain("idle.example.com", NOON.minus(Duration.ofMinutes(30)), true, NOON.minus(Duration.ofHours(2))));
        wm("idle.example.com", NOON.minus(Duration.ofHours(1)));   // lastSeen(2h전) <= wm(1h전) = 신규 트래픽 없음
        repo.save(domain("busy.example.com", NOON.minus(Duration.ofMinutes(1)), true, NOON.minus(Duration.ofMinutes(5))));
        wm("busy.example.com", NOON.minus(Duration.ofHours(1)));   // lastSeen(5분전) > wm(1h전) = 신규 트래픽

        ScanSelector selector = new ScanSelector(repo, props(10, 10, "UTC"), fixed(NOON));
        var selected = selector.selectForTick().stream().map(DomainConfig::getHost).toList();

        assertThat(selected).containsExactly("busy.example.com", "idle.example.com"); // 활성 먼저(due 순서 아님)
    }

    @Test
    void reservesMinimumSlotsForIdleDomainsToPreventStarvation() {
        // D64: 활성이 K 를 다 채워도 idle(K/5 예약)이 최소 1개는 선발 — D61 점프 유지(드리프트 방지).
        for (int i = 0; i < 10; i++) {
            String h = "busy" + i + ".example.com";
            repo.save(domain(h, NOON.minus(Duration.ofMinutes(10)), true, NOON.minus(Duration.ofMinutes(5))));
            wm(h, NOON.minus(Duration.ofHours(1)));
        }
        repo.save(domain("idle.example.com", NOON.minus(Duration.ofMinutes(30)), true, NOON.minus(Duration.ofHours(2))));
        wm("idle.example.com", NOON.minus(Duration.ofHours(1)));

        ScanSelector selector = new ScanSelector(repo, props(5, 5, "UTC"), fixed(NOON)); // K=5 → 예약 1
        var selected = selector.selectForTick().stream().map(DomainConfig::getHost).toList();

        assertThat(selected).hasSize(5).contains("idle.example.com"); // 예약분으로 idle 포함
    }

    @Test
    void inactiveAfterZeroDisablesStaleExclusion() {
        repo.save(domain("stale.example.com", null, true, NOON.minus(Duration.ofDays(40))));

        ScanSelector selector = new ScanSelector(repo, props(10, 10, "UTC", Duration.ZERO), fixed(NOON));

        assertThat(selector.selectForTick().stream().map(DomainConfig::getHost).toList())
                .contains("stale.example.com"); // inactive-after=0 → 필터 비활성(현행 무회귀)
    }

    private static Clock fixed(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private void wm(String host, Instant lastEnd) {
        Watermark w = new Watermark();
        w.setHost(host);
        w.setLastEnd(lastEnd);
        wmRepo.save(w);
    }

    private static DomainConfig domain(String host, Instant nextScanDueAt, boolean enabled) {
        return domain(host, nextScanDueAt, enabled, null); // lastSeenAt 미설정(무접속 필터 무관=null→제외 안 함)
    }

    private static DomainConfig domain(String host, Instant nextScanDueAt, boolean enabled, Instant lastSeenAt) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(enabled);
        d.setNextScanDueAt(nextScanDueAt);
        d.setLastSeenAt(lastSeenAt); // ★게이트 기준 = discovery 관측시각 lastSeenAt(D59, D57 재설계)
        return d;
    }

    private static ApiDiscoverProperties props(int domainsPerTick, int offPeakDomainsPerTick, String offPeakZone) {
        return props(domainsPerTick, offPeakDomainsPerTick, offPeakZone, Duration.ofDays(30)); // 기본 inactive-after=P30D
    }

    private static ApiDiscoverProperties props(int domainsPerTick, int offPeakDomainsPerTick, String offPeakZone,
                                               Duration inactiveAfter) {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://loki.local:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(1)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 0, "^x$", java.util.List.of()),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), domainsPerTick, Duration.ZERO, 0, 0L, true,
                        Duration.ZERO, 0, true, Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6),
                        Duration.ofHours(24), offPeakDomainsPerTick, Duration.ofHours(24), offPeakZone,
                        Duration.ofDays(14), Duration.ofDays(1), inactiveAfter, 0));
    }
}
