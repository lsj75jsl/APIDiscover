// ScanSelector due 선택 테스트 — @DataJpaTest(H2): due 도래 술어·nullsFirst asc·K 상한·off-peak K 스위치 (doc/33 §4, D48)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
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

    private static Clock fixed(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private static DomainConfig domain(String host, Instant nextScanDueAt, boolean enabled) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(enabled);
        d.setNextScanDueAt(nextScanDueAt);
        return d;
    }

    private static ApiDiscoverProperties props(int domainsPerTick, int offPeakDomainsPerTick, String offPeakZone) {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://loki.local:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(1)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 0, "^x$"),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), domainsPerTick, Duration.ZERO, 0, 0L, true,
                        Duration.ZERO, 0, true, Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6),
                        Duration.ofHours(24), offPeakDomainsPerTick, Duration.ofHours(24), offPeakZone,
                        Duration.ofDays(14), Duration.ofDays(1)));
    }
}
