// LokiQueryBuilder 단위 테스트 — 단건/배칭(D63) LogQL 생성·정규식 이스케이프
package com.pentasecurity.apidiscover.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LokiQueryBuilderTest {

    private final LokiQueryBuilder builder = new LokiQueryBuilder(props());

    @Test
    void buildsSingleDomainQueries() {
        assertThat(builder.build("EDGE1", "a.example.com"))
                .isEqualTo("{job=\"access_log\", hostname=\"EDGE1\"} |= `a.example.com`");
        assertThat(builder.build("a.example.com"))
                .isEqualTo("{job=\"access_log\"} |= `a.example.com`");
    }

    @Test
    void buildBatchJoinsEscapedDomainsWithRegexOr() {
        // D63: |~ OR 배칭 — '.' 이스케이프 필수(a.com 이 axcom 매치되는 과탐 방지)
        String q = builder.buildBatch("EDGE1", List.of("a.example.com", "b-2.example.com"));
        assertThat(q).isEqualTo(
                "{job=\"access_log\", hostname=\"EDGE1\"} |~ `(a\\.example\\.com|b-2\\.example\\.com)`");
    }

    @Test
    void escapeRegexEscapesAllRe2Metacharacters() {
        assertThat(LokiQueryBuilder.escapeRegex("a.b+c*d?e(f)g|h[i]j{k}l^m$n\\o"))
                .isEqualTo("a\\.b\\+c\\*d\\?e\\(f\\)g\\|h\\[i\\]j\\{k\\}l\\^m\\$n\\\\o");
        assertThat(LokiQueryBuilder.escapeRegex("plain-domain.com")).isEqualTo("plain-domain\\.com");
    }

    private static ApiDiscoverProperties props() {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://loki.local:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(1)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 0, "^x$", List.of(), List.of(), List.of(), List.of()),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), 100, Duration.ZERO, 0, 0L, true,
                        Duration.ZERO, 0, false, Duration.ofMinutes(30), Duration.ofHours(2),
                        Duration.ofHours(6), Duration.ofHours(24), 500, Duration.ofHours(24), "",
                        Duration.ofDays(14), Duration.ofDays(1), Duration.ZERO, 0, false, Duration.ZERO));
    }
}
