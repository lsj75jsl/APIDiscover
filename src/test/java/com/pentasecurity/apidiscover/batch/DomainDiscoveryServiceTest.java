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

    // --- LogQL 형태(coalesce·집계·필터) ---

    @Test
    void buildsServerSideAggregationLogQLWithCoalesce() {
        String ql = service().buildLogQL(Duration.ofMinutes(12));
        assertThat(ql).contains("sum by (domain, hostname) (count_over_time(");
        assertThat(ql).contains("{job=\"access_log\"}");
        assertThat(ql).contains("| label_format domain=");
        assertThat(ql).contains(".real_host");          // coalesce 분기
        assertThat(ql).contains("| domain!=\"\" | domain!=\"-\"");
        assertThat(ql).contains("[720s]");
    }

    // --- helpers ---

    private DomainDiscoveryService service() {
        return new DomainDiscoveryService(loki, repo, new DomainUpserter(repo), props(200));
    }

    private DomainDiscoveryService serviceWithCap(int cap) {
        return new DomainDiscoveryService(loki, repo, new DomainUpserter(repo), props(cap));
    }

    private static MetricSample sample(String domain, String hostname, double count) {
        return new MetricSample(Map.of("domain", domain, "hostname", hostname), count);
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
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://loki.local:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(1)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), maxDomains, FQDN));
    }
}
