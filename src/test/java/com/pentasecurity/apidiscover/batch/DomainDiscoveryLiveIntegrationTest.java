// 실 Loki 대상 도메인 디스커버리 게이트 테스트 — label_format coalesce 동작 1회 확인 (-Dloki.live=true, 소창 off-peak)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.ingest.LokiClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 실 Loki(운영) 의 `label_format` coalesce(host 비었/"-"→real_host)·pattern 포지션을 소창으로 1회 확인한다.
 * 기본 빌드에서 실행 금지(LokiLive 게이트, -Dloki.live=true). 운영 Loki 부하 — 2분 창·off-peak.
 */
class DomainDiscoveryLiveIntegrationTest {

    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 6, 22, 13, 2, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void coalesceAndAggregationAgainstRealLoki() {
        assumeTrue("true".equals(System.getProperty("loki.live")),
                "skipped: set -Dloki.live=true to run against real Loki");

        ApiDiscoverProperties props = props();
        LokiClient loki = new LokiClient(props, new ObjectMapper());
        // buildLogQL 만 쓰므로 repo/upserter 는 미사용(count/upsert 미호출 경로) — mock 으로 충분.
        DomainConfigRepository repo = mock(DomainConfigRepository.class);
        DomainDiscoveryService svc = new DomainDiscoveryService(loki, repo, new DomainUpserter(repo), props);

        String logql = svc.buildLogQL(Duration.ofMinutes(2)); // 소창
        List<LokiClient.MetricSample> vector = loki.queryInstant(logql, AT.toInstant());

        assertThat(vector).as("도메인 집계 벡터(라인 미수신)").isNotEmpty();
        // coalesce + 필터: 모든 domain 라벨이 비/"-" 가 아니어야(빈/dash 는 LogQL 에서 제외)
        assertThat(vector).allSatisfy(s -> {
            String domain = s.labels().get("domain");
            assertThat(domain).isNotBlank();
            assertThat(domain).isNotEqualTo("-");
        });
        System.out.println("=== domain discovery live: " + vector.size() + " (domain,hostname) rows ===");
        vector.stream().limit(20).forEach(s -> System.out.printf("  %-30s %-10s %.0f%n",
                s.labels().get("domain"), s.labels().get("hostname"), s.value()));
    }

    private static ApiDiscoverProperties props() {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://192.168.8.100:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(5), 2000, 2, Duration.ofMillis(200)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 200,
                        "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$"));
    }
}
