// DomainDiscoveryService 단위 테스트 — 벡터 파싱·FQDN 거름·상한·무삭제 업서트·설정보존·부트스트랩 (doc/30)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.ingest.LokiClient;
import com.pentasecurity.apidiscover.ingest.LokiClient.MetricSample;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainDiscoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-25T10:00:00Z");
    private static final String FQDN = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$";

    private final LokiClient loki = mock(LokiClient.class);
    private final List<DomainConfig> db = new ArrayList<>();
    private final DomainConfigRepository repo = statefulRepo(db);

    // --- 벡터 파싱 → (domain→hostnames) 집계 + 신규 INSERT ---

    @Test
    void parsesVectorAndInsertsNewDomainsWithHostnameUnion() {
        when(loki.queryInstant(any(), any())).thenReturn(List.of(
                sample("api.example.com", "AOSE1", 100),
                sample("api.example.com", "OSE1", 50),   // 같은 도메인 다른 엣지 → hostnames 합집합
                sample("shop.example.com", "AOSE1", 30)));

        DomainDiscoveryService.DiscoveryResult r = service().discover(NOW);

        assertThat(r.inserted()).isEqualTo(2);
        assertThat(r.updated()).isZero();
        DomainConfig api = find("api.example.com");
        assertThat(api.getHostnames()).containsExactly("AOSE1", "OSE1"); // 정렬·중복제거(TreeSet)
        assertThat(api.getDiscoveredAt()).isEqualTo(NOW);
        assertThat(api.getLastSeenAt()).isEqualTo(NOW);
        assertThat(api.isEnabled()).isTrue();                         // 기본값
        assertThat(api.getSpecMergeStrategy()).isEqualTo(SpecMergeStrategy.MERGE); // 기본값
    }

    // --- D62: 제외 엣지 관측은 없는 것으로 취급 ---

    @Test
    void excludedEdgeSamplesAreDroppedEntirely() {
        when(loki.queryInstant(any(), any())).thenReturn(List.of(
                sample("only-excluded.example.com", "AAJ11", 100),  // 제외 엣지에서만 관측 → 미등록
                sample("mixed.example.com", "AAJ11", 50),           // 혼합: 제외 엣지 관측은 drop
                sample("mixed.example.com", "AHJ11", 30)));         //       비제외 엣지 관측만 반영

        DomainDiscoveryService.DiscoveryResult r =
                serviceWithExcluded(List.of("AAJ11", "AAJ12")).discover(NOW);

        assertThat(r.inserted()).isEqualTo(1); // mixed 만 등록
        assertThat(db.stream().map(DomainConfig::getHost)).doesNotContain("only-excluded.example.com");
        DomainConfig mixed = find("mixed.example.com");
        assertThat(mixed.getHostnames()).containsExactly("AHJ11"); // 제외 엣지 매핑 미등록
    }

    // --- FQDN 거름: 변조/비FQDN Host 자동등록 차단 ---

    @Test
    void rejectsNonFqdnAndEmptyDomains() {
        when(loki.queryInstant(any(), any())).thenReturn(List.of(
                sample("api.example.com", "E1", 10),
                sample("localhost", "E1", 99),       // 점 없음 → FQDN 위반
                sample("bad_host.com", "E1", 99),     // 언더스코어 → 위반
                sample("-", "E1", 99),                // dash
                sample("", "E1", 99)));               // 빈값

        DomainDiscoveryService.DiscoveryResult r = service().discover(NOW);

        assertThat(r.inserted()).isEqualTo(1);
        assertThat(r.rejected()).isEqualTo(4);
        assertThat(db).extracting(DomainConfig::getHost).containsExactly("api.example.com");
    }

    @Test
    void normalizesDomainToLowercase() {
        when(loki.queryInstant(any(), any())).thenReturn(List.of(sample("API.Example.COM", "E1", 5)));
        service().discover(NOW);
        assertThat(db).extracting(DomainConfig::getHost).containsExactly("api.example.com");
    }

    // --- max-domains 상한: 카운트 desc top-N ---

    @Test
    void capsToMaxDomainsByCountDescending() {
        when(loki.queryInstant(any(), any())).thenReturn(List.of(
                sample("a.example.com", "E1", 10),
                sample("b.example.com", "E1", 99),   // 최다
                sample("c.example.com", "E1", 50)));

        DomainDiscoveryService.DiscoveryResult r = serviceWithCap(2).discover(NOW);

        assertThat(r.dropped()).isEqualTo(1);
        assertThat(db).extracting(DomainConfig::getHost)
                .containsExactlyInAnyOrder("b.example.com", "c.example.com"); // 최저 a 가 drop
    }

    @Test
    void unlimitedWhenCapZeroRegistersAllDomains() {
        when(loki.queryInstant(any(), any())).thenReturn(List.of(
                sample("a.example.com", "E1", 10),
                sample("b.example.com", "E1", 99),
                sample("c.example.com", "E1", 50)));

        DomainDiscoveryService.DiscoveryResult r = serviceWithCap(0).discover(NOW); // 0=무제한

        assertThat(r.dropped()).isZero();
        assertThat(r.inserted()).isEqualTo(3);
        assertThat(db).extracting(DomainConfig::getHost)
                .containsExactlyInAnyOrder("a.example.com", "b.example.com", "c.example.com"); // 전수 등록
    }

    // --- ★무삭제 + 사용자 설정 보존 ---

    @Test
    void existingDomainMergesHostnamesAndPreservesUserSettings() {
        DomainConfig existing = new DomainConfig();
        existing.setHost("api.example.com");
        existing.setHostnames(new ArrayList<>(List.of("OLD1")));
        existing.setEnabled(false);                       // 사용자가 끔
        existing.setBasePathStrip("/v2");                 // 사용자 설정
        existing.setSpecMergeStrategy(SpecMergeStrategy.SEPARATE);
        existing.setIntervalOverride("PT30M");
        existing.setDiscoveredAt(Instant.parse("2026-06-01T00:00:00Z"));
        db.add(existing);

        when(loki.queryInstant(any(), any())).thenReturn(List.of(sample("api.example.com", "NEW1", 10)));

        DomainDiscoveryService.DiscoveryResult r = service().discover(NOW);

        assertThat(r.inserted()).isZero();
        assertThat(r.updated()).isEqualTo(1);
        DomainConfig after = find("api.example.com");
        assertThat(after.getHostnames()).containsExactly("NEW1", "OLD1");      // 합집합
        assertThat(after.getLastSeenAt()).isEqualTo(NOW);                       // 갱신
        // ★설정 보존(덮어쓰기 금지)
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getBasePathStrip()).isEqualTo("/v2");
        assertThat(after.getSpecMergeStrategy()).isEqualTo(SpecMergeStrategy.SEPARATE);
        assertThat(after.getIntervalOverride()).isEqualTo("PT30M");
        assertThat(after.getDiscoveredAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z")); // 최초 발견 불변
    }

    @Test
    void neverDeletesDomainsAbsentFromVector() {
        DomainConfig stale = new DomainConfig();
        stale.setHost("gone.example.com");
        stale.setHostnames(new ArrayList<>(List.of("E1")));
        db.add(stale);

        when(loki.queryInstant(any(), any())).thenReturn(List.of(sample("api.example.com", "E1", 10)));
        service().discover(NOW); // gone 은 벡터에 없음

        assertThat(db).extracting(DomainConfig::getHost)
                .containsExactlyInAnyOrder("gone.example.com", "api.example.com"); // 삭제 없음
    }

    // --- 부트스트랩(빈 DB) vs 롤링 윈도우 ---

    @Test
    void usesBootstrapWindowWhenDbEmptyThenRolling() {
        when(loki.queryInstant(any(), any())).thenReturn(List.of());
        // 빈 DB → 부트스트랩(1h=3600s)
        service().discover(NOW);
        // 도메인 1개 시드 후 → 롤링(12m=720s)
        DomainConfig seed = new DomainConfig();
        seed.setHost("seed.example.com");
        db.add(seed);
        service().discover(NOW);

        org.mockito.ArgumentCaptor<String> ql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(loki, org.mockito.Mockito.times(2)).queryInstant(ql.capture(), any());
        assertThat(ql.getAllValues().get(0)).contains("[3600s]"); // bootstrap-window PT1H
        assertThat(ql.getAllValues().get(1)).contains("[720s]");  // window PT12M
    }

    // --- 클라이언트 coalesce: host 빈/"-" → real_host (서버 label_format 제거, 성능 10배) ---

    @Test
    void coalescesToRealHostWhenHostBlankOrDash() {
        when(loki.queryInstant(any(), any())).thenReturn(List.of(
                labeled("", "fallback.example.com", "E1", 5),    // host 빈 → real_host
                labeled("-", "dash.example.com", "E1", 5),       // host "-" → real_host
                labeled("api.example.com", "-", "E1", 5)));      // host 우선

        service().discover(NOW);
        assertThat(db).extracting(DomainConfig::getHost)
                .containsExactlyInAnyOrder("fallback.example.com", "dash.example.com", "api.example.com");
    }

    @Test
    void mergesSameDomainAcrossHostRealHostCombos() {
        // 같은 도메인이 (host) 와 (real_host 폴백) 양쪽에서 다른 hostname 으로 → 도메인 키로 합산(hostnames 합집합)
        when(loki.queryInstant(any(), any())).thenReturn(List.of(
                labeled("api.example.com", "-", "E1", 10),
                labeled("", "api.example.com", "E2", 20)));

        service().discover(NOW);
        assertThat(db).hasSize(1);
        assertThat(find("api.example.com").getHostnames()).containsExactly("E1", "E2"); // 합집합
    }

    // --- LogQL 형태(서버 coalesce 제거·host/real_host/hostname group by) ---

    @Test
    void buildsServerSideAggregationLogQLWithoutLabelFormat() {
        String ql = service().buildLogQL(Duration.ofMinutes(12));
        assertThat(ql).contains("sum by (host, real_host, hostname) (count_over_time(");
        assertThat(ql).contains("{job=\"access_log\"}");
        assertThat(ql).contains("[720s]");
        // 성능 회귀 가드: 서버측 label_format coalesce·domain 필터 제거됨
        assertThat(ql).doesNotContain("label_format");
        assertThat(ql).doesNotContain("domain");
    }

    // --- helpers ---

    private DomainDiscoveryService service() {
        return new DomainDiscoveryService(loki, repo, new DomainUpserter(repo), props(200));
    }

    private DomainDiscoveryService serviceWithCap(int cap) {
        return new DomainDiscoveryService(loki, repo, new DomainUpserter(repo), props(cap));
    }

    /** D62: 제외 엣지 목록을 지정한 서비스. */
    private DomainDiscoveryService serviceWithExcluded(List<String> excludedHostnames) {
        return new DomainDiscoveryService(loki, repo, new DomainUpserter(repo), props(200, excludedHostnames));
    }

    /** host=도메인·real_host="-"(폴백 불필요) 편의 — 기존 테스트의 "도메인=host" 의미 유지. */
    private static MetricSample sample(String host, String hostname, double count) {
        return labeled(host, "-", hostname, count);
    }

    /** 벡터 1행: {host, real_host, hostname} 라벨 + 집계값(클라이언트 coalesce 대상). */
    private static MetricSample labeled(String host, String realHost, String hostname, double count) {
        return new MetricSample(Map.of("host", host, "real_host", realHost, "hostname", hostname), count);
    }

    private DomainConfig find(String host) {
        return db.stream().filter(d -> host.equals(d.getHost())).findFirst().orElseThrow();
    }

    /** in-memory DomainConfigRepository(findById/save/count). */
    private static DomainConfigRepository statefulRepo(List<DomainConfig> store) {
        DomainConfigRepository repo = mock(DomainConfigRepository.class);
        when(repo.findById(any())).thenAnswer(inv -> store.stream()
                .filter(d -> inv.getArgument(0).equals(d.getHost())).findFirst());
        when(repo.save(any())).thenAnswer(inv -> {
            DomainConfig d = inv.getArgument(0);
            store.removeIf(e -> e.getHost().equals(d.getHost()));
            store.add(d);
            return d;
        });
        when(repo.count()).thenAnswer(inv -> (long) store.size());
        return repo;
    }

    private static ApiDiscoverProperties props(int maxDomains) {
        return props(maxDomains, List.of());
    }

    private static ApiDiscoverProperties props(int maxDomains, List<String> excludedHostnames) {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://loki.local:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(1)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), maxDomains, FQDN, excludedHostnames),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), 100, Duration.ZERO, 0, 0L, true, Duration.ZERO, 0, false, Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(24), 500, Duration.ofHours(24), "", Duration.ofDays(14), Duration.ofDays(1), Duration.ZERO, 0, false, Duration.ZERO));
    }
}
