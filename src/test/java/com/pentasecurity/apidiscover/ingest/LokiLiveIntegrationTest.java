// 실 Loki 대상 end-to-end 통합 테스트 — -Dloki.live=true 일 때만 실행 (부하 고려 2분 창)
package com.pentasecurity.apidiscover.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.classify.ApiScorer;
import com.pentasecurity.apidiscover.classify.Classifier;
import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveryReport;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.normalize.EndpointKindClassifier;
import com.pentasecurity.apidiscover.normalize.InventoryBuilder;
import com.pentasecurity.apidiscover.normalize.PathNormalizer;
import com.pentasecurity.apidiscover.parse.LogLineParser;
import com.pentasecurity.apidiscover.report.ReportBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LokiLiveIntegrationTest {

    private static final String DOMAIN = "www.computer.co.kr";
    private static final String[] HOSTNAMES = {"AOSE1", "OSE1"};
    // 부하 최소화: 2분 창
    private static final OffsetDateTime FROM = OffsetDateTime.of(2026, 6, 22, 13, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime TO = FROM.plusMinutes(2);

    @Test
    void endToEndAgainstRealLoki() throws IOException {
        assumeTrue("true".equals(System.getProperty("loki.live")),
                "skipped: set -Dloki.live=true to run against real Loki");

        ApiDiscoverProperties props = props();
        LokiClient loki = new LokiClient(props, new ObjectMapper());
        LokiQueryBuilder qb = new LokiQueryBuilder(props);
        LogWindow window = new LogWindow(FROM.toInstant(), TO.toInstant());

        // 1) 수집 — AOSE1 우선, 비면 OSE1
        List<String> lines = List.of();
        String usedHost = null;
        for (String h : HOSTNAMES) {
            lines = loki.queryRange(qb.build(h, DOMAIN), window);
            usedHost = h;
            if (!lines.isEmpty()) {
                break;
            }
        }
        assertThat(lines).as("Loki 에서 로그를 가져와야 함").isNotEmpty();

        // 2) 파싱
        LogLineParser parser = new LogLineParser();
        List<ParsedRequest> requests = new ArrayList<>();
        for (String line : lines) {
            parser.parse(line).ifPresent(requests::add);
        }

        // 3) 인벤토리(스펙 없음 → 매처 빈) → 4) 분류 → 5) 리포트
        EndpointMatcher matcher = new EndpointMatcher(List.<CanonicalEndpoint>of());
        InventoryBuilder inventory = new InventoryBuilder(new PathNormalizer(), new EndpointKindClassifier());
        List<DiscoveredEndpoint> discovered = inventory.build(requests, matcher);
        List<Finding> findings = new Classifier(new ApiScorer()).classify(discovered, List.of(), matcher);
        DiscoveryReport report = new ReportBuilder().build(DOMAIN, 0L, window, discovered.size(), findings);

        // 검증
        assertThat(requests).as("파싱된 요청이 있어야 함").isNotEmpty();
        assertThat(discovered).as("발견된 엔드포인트가 있어야 함").isNotEmpty();

        writeSummary(usedHost, lines, requests, discovered, report);
    }

    private void writeSummary(String host, List<String> lines, List<ParsedRequest> requests,
                              List<DiscoveredEndpoint> discovered, DiscoveryReport report) throws IOException {
        Map<EndpointKind, Integer> kindDist = new LinkedHashMap<>();
        for (DiscoveredEndpoint d : discovered) {
            kindDist.merge(d.endpointKind(), 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Loki E2E 검증 결과 ===\n");
        sb.append("hostname=").append(host).append("  domain=").append(DOMAIN).append('\n');
        sb.append("window=").append(FROM).append(" ~ ").append(TO).append('\n');
        sb.append("수집 라인=").append(lines.size())
                .append("  파싱 성공=").append(requests.size()).append('\n');
        sb.append("발견 엔드포인트(시그니처)=").append(discovered.size()).append('\n');
        sb.append("endpoint_kind 분포=").append(kindDist).append('\n');
        DiscoveryReport.Summary s = report.summary();
        sb.append("분류 요약: discovered=").append(s.discovered())
                .append(" active=").append(s.active())
                .append(" shadow=").append(s.shadow())
                .append(" zombie=").append(s.zombie())
                .append(" unused=").append(s.unused()).append('\n');
        sb.append("\n--- 발견 엔드포인트 샘플(최대 15) ---\n");
        discovered.stream().limit(15).forEach(d ->
                sb.append(String.format("  %-7s %-9s hits=%-4d %s%n",
                        d.method(), d.endpointKind(), d.metrics().hits(), d.pathTemplate())));

        String out = sb.toString();
        System.out.println(out);
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "loki_e2e_result.txt");
        // 스크래치패드에도 기록
        Path scratch = Path.of("/tmp/claude-1000/-home-kaga-dev-APIDiscover/"
                + "266ca9d0-a9a3-4f6d-87f8-bc5644ca8cc3/scratchpad/loki_e2e_result.txt");
        Files.writeString(file, out);
        try {
            Files.writeString(scratch, out);
        } catch (IOException ignored) {
            // 스크래치 경로 없으면 무시
        }
    }

    private static ApiDiscoverProperties props() {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://192.168.8.100:3200", "access_log",
                        Duration.ofSeconds(30),  // query-timeout
                        Duration.ofMinutes(5),   // chunk-window (2분 창 → 단일 chunk)
                        2000,                    // page-limit
                        2,                       // max-concurrent-queries
                        Duration.ofMillis(200)), // min-query-interval
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"));
    }
}
