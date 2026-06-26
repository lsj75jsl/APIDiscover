// ScanSelector LRS 선택 테스트 — @DataJpaTest(H2): nulls-first·오름차순·K 상한 (doc/33 §1 B)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class ScanSelectorTest {

    @Autowired
    private DomainConfigRepository repo;

    @Test
    void selectsLeastRecentlyScannedNullsFirstUpToK() {
        // never-scanned(null) 가 가장 먼저, 그다음 오래된 순. disabled 제외.
        repo.save(domain("never.example.com", null, true));
        repo.save(domain("old.example.com", Instant.parse("2026-06-01T00:00:00Z"), true));
        repo.save(domain("recent.example.com", Instant.parse("2026-06-25T00:00:00Z"), true));
        repo.save(domain("disabled.example.com", null, false)); // enabled=false → 제외

        ScanSelector selector = new ScanSelector(repo, props(2)); // K=2
        var selected = selector.selectForTick().stream().map(DomainConfig::getHost).toList();

        assertThat(selected).containsExactly("never.example.com", "old.example.com"); // nulls-first, asc, K=2
        assertThat(selected).doesNotContain("disabled.example.com");
    }

    private static DomainConfig domain(String host, Instant lastScanAttemptAt, boolean enabled) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(enabled);
        d.setLastScanAttemptAt(lastScanAttemptAt);
        return d;
    }

    private static ApiDiscoverProperties props(int domainsPerTick) {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://loki.local:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(1)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 0, "^x$"),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), domainsPerTick, Duration.ZERO, 0, 0L, true, Duration.ZERO, 0));
    }
}
