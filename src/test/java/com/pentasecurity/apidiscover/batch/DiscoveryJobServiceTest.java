// DiscoveryJobService.analyze() end-to-end 파이프라인 테스트 (Loki 제외)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pentasecurity.apidiscover.classify.ApiScorer;
import com.pentasecurity.apidiscover.classify.Classifier;
import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.config.NormalizationProperties;
import com.pentasecurity.apidiscover.config.SensitiveKeyProperties;
import com.pentasecurity.apidiscover.domain.ClassificationConfig;
import com.pentasecurity.apidiscover.domain.ClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.domain.WatermarkRepository;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.ingest.LokiClient;
import com.pentasecurity.apidiscover.match.EndpointMatcherCache;
import com.pentasecurity.apidiscover.ingest.LokiQueryBuilder;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.normalize.CardinalityNormalizer;
import com.pentasecurity.apidiscover.normalize.EndpointKindClassifier;
import com.pentasecurity.apidiscover.normalize.InventoryBuilder;
import com.pentasecurity.apidiscover.normalize.ParamCandidateExtractor;
import com.pentasecurity.apidiscover.normalize.PathNormalizer;
import com.pentasecurity.apidiscover.normalize.SensitiveKeyMatcher;
import com.pentasecurity.apidiscover.parse.LogLineParser;
import com.pentasecurity.apidiscover.report.ReportBuilder;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DiscoveryJobServiceTest {

    private static final String HOST = "api.example.com";
    private static final NormalizationProperties NORM = NormalizationProperties.defaults();

    private final SpecStore specStore = mock(SpecStore.class);
    private final ScanResultRepository scanRepo = mock(ScanResultRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // 기본 빈 mock → resolver 가 무회귀 default 반환. e2e 테스트는 globalClsRepo 를 직접 stub.
    private final ClassificationConfigRepository globalClsRepo = mock(ClassificationConfigRepository.class);
    private final DomainClassificationConfigRepository domainClsRepo =
            mock(DomainClassificationConfigRepository.class);
    private final EffectiveClassificationResolver resolver =
            new EffectiveClassificationResolver(globalClsRepo, domainClsRepo, objectMapper);

    private final DiscoveryJobService service = new DiscoveryJobService(
            new LogLineParser(NORM),
            new InventoryBuilder(new PathNormalizer(), new EndpointKindClassifier(),
                    new CardinalityNormalizer(NORM),
                    new ParamCandidateExtractor(new SensitiveKeyMatcher(SensitiveKeyProperties.defaults()), NORM)),
            specStore,
            new EndpointMatcherCache(),
            new Classifier(new ApiScorer()),
            resolver,
            new ReportBuilder(),
            scanRepo,
            mock(DomainConfigRepository.class),
            mock(WatermarkRepository.class),
            mock(LokiClient.class),
            mock(LokiQueryBuilder.class),
            objectMapper,
            props());

    private final LogWindow window = new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));

    @Test
    void noSpecMakesEverythingShadow() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // HOST=api.example.com(host_api) + query + repeat → 게이트 통과 → Shadow
        ScanResult result = service.analyze(HOST, List.of(
                line("GET", "/users/1?x=1", 200),
                line("GET", "/users/2?x=2", 200),
                line("GET", "/users/3?x=3", 200)), window);

        assertThat(result.discovered).isEqualTo(1);   // /users/{id} 1건
        assertThat(result.shadow).isEqualTo(1);
        assertThat(result.active).isZero();
        assertThat(result.version).isNotBlank();
        assertThat(result.reportJson).contains("\"shadow\"");
    }

    @Test
    void analyzeReflectsExcludeConfigFromResolver() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));
        // 전역 설정: /users exclude → /users/{id} 가 DROP_EXCLUDED → Shadow 0 (설정이 결과에 반영, e2e)
        ClassificationConfig global = new ClassificationConfig();
        global.id = 1L;
        global.profile = ClassificationProfile.MIDDLE;
        global.matcherJson = "{\"excludePathPrefixes\":[\"/users\"]}";
        when(globalClsRepo.findById(1L)).thenReturn(Optional.of(global));

        ScanResult result = service.analyze(HOST, List.of(
                line("GET", "/users/1?x=1", 200),
                line("GET", "/users/2?x=2", 200),
                line("GET", "/users/3?x=3", 200)), window);

        assertThat(result.discovered).isEqualTo(1); // 인벤토리엔 존재
        assertThat(result.shadow).isZero();          // exclude 설정 반영 → 미보고(무회귀 대비 차이)
    }

    @Test
    void specMakesMatchedTrafficActive() {
        SpecRecord active = new SpecRecord();
        active.specVersion = 5L;
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(active));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/users/{id}", null, false, null, "ref")));
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        ScanResult result = service.analyze(HOST, List.of(
                line("GET", "/users/1", 200),
                line("GET", "/users/2", 200)), window);

        assertThat(result.active).isEqualTo(1);
        assertThat(result.shadow).isZero();
        assertThat(result.specVersion).isEqualTo(5L);
    }

    @Test
    void versionIsStableAcrossIdenticalContent() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        List<String> lines = List.of(line("GET", "/users/1", 200));
        String v1 = service.analyze(HOST, lines, window).version;
        // 다른 윈도우(시간대)라도 findings 내용이 같으면 version 동일 (doc/07 §8)
        LogWindow later = new LogWindow(Instant.EPOCH.plusSeconds(7200), Instant.EPOCH.plusSeconds(10800));
        String v2 = service.analyze(HOST, lines, later).version;

        assertThat(v1).isEqualTo(v2);
    }

    @Test
    void reportJsonEmbedsDroppedNonApi() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // /page x2($type=api→API_CANDIDATE): host_api(0.40)+responseTypeApi(0.25)=0.65 < 0.70, repeat 미달 → DROP_LOW_SCORE
        ScanResult result = service.analyze(HOST, List.of(
                line("GET", "/page", 200), line("GET", "/page", 200)), window);

        assertThat(result.shadow).isZero();
        assertThat(result.reportJson)
                .contains("\"droppedNonApi\"")
                .contains("\"lowScore\":1")
                .contains("\"total\":1"); // 파생 total JSON 출현
    }

    @Test
    void etagReflectsDroppedDistributionChange() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // /page x2($type=api→API_CANDIDATE): host_api 0.40+responseTypeApi 0.25=0.65 < 0.70, repeat 미달 → 저득점
        List<String> lines = List.of(
                line("GET", "/page", 200), line("GET", "/page", 200));

        // 스캔 A: 설정 없음 → /page DROP_LOW_SCORE, dropped=(0,0,1)
        ScanResult a = service.analyze(HOST, lines, window);

        // 스캔 B: /page exclude 설정 → DROP_EXCLUDED, dropped=(1,0,0). findings 동일(둘 다 Shadow 0)·dropped 분포만 변경
        ClassificationConfig g = new ClassificationConfig();
        g.id = 1L;
        g.profile = ClassificationProfile.MIDDLE;
        g.matcherJson = "{\"excludePathPrefixes\":[\"/page\"]}";
        when(globalClsRepo.findById(1L)).thenReturn(Optional.of(g));
        resolver.invalidateAll();
        ScanResult b = service.analyze(HOST, lines, window);

        assertThat(a.shadow).isZero();
        assertThat(b.shadow).isZero();
        assertThat(a.discovered).isEqualTo(b.discovered); // summary 동일
        assertThat(a.version).isNotEqualTo(b.version);     // dropped 분포 변화가 ETag 에 반영 (doc/12 §4)
    }

    @Test
    void shadowParamCandidatesAppearInReportJson() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // POST /api/orders/{id}?expand=full → ADMIT(Shadow). params(query expand + path {id})가 reportJson 까지 노출
        ScanResult result = service.analyze(HOST, List.of(
                line("POST", "/api/orders/1?expand=full", 200),
                line("POST", "/api/orders/2?expand=full", 200),
                line("POST", "/api/orders/3?expand=full", 200)), window);

        assertThat(result.shadow).isEqualTo(1);
        assertThat(result.reportJson)
                .contains("\"params\"")
                .contains("\"expand\"")           // query param 후보
                .contains("\"token\":\"{id}\"");  // path param 후보 (PathParam 직렬화)
    }

    @Test
    void templateCapSurfacesInReportJsonForEtag() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // 상한 2 인 InventoryBuilder 로 별도 서비스 구성 → 3 distinct template → 1 drop
        var capProps = new NormalizationProperties(2, 50, 0.3, 20, 0.7, new int[]{8, 32, 128});
        var cappedInventory = new InventoryBuilder(new PathNormalizer(), new EndpointKindClassifier(),
                new CardinalityNormalizer(capProps),
                new ParamCandidateExtractor(new SensitiveKeyMatcher(SensitiveKeyProperties.defaults()), capProps));
        var cappedService = new DiscoveryJobService(new LogLineParser(NORM), cappedInventory, specStore,
                new EndpointMatcherCache(), new Classifier(new ApiScorer()), resolver, new ReportBuilder(), scanRepo,
                mock(DomainConfigRepository.class), mock(WatermarkRepository.class), mock(LokiClient.class),
                mock(LokiQueryBuilder.class), objectMapper, props());

        ScanResult result = cappedService.analyze(HOST, List.of(
                line("GET", "/a/x", 200), line("GET", "/a/x", 200), line("GET", "/a/x", 200),
                line("GET", "/b/x", 200), line("GET", "/b/x", 200),
                line("GET", "/c/x", 200)), window);

        // droppedByLimit 가 reportJson(=ETag 입력)에 임베드 → 상한 이벤트가 결과 콘텐츠에 반영 (doc/13 §4.2)
        assertThat(result.reportJson).contains("\"droppedByLimit\"").contains("\"templates\":1");
        assertThat(result.version).isNotBlank();
    }

    @Test
    void rescanAfterSpecVersionBumpUsesFreshMatcherNotStale() {
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));
        List<String> traffic = List.of(
                line("GET", "/users/1?x=1", 200),
                line("GET", "/users/2?x=2", 200),
                line("GET", "/users/3?x=3", 200));

        // v1: spec 에 /users/{id} → 트래픽 매칭 → Active
        SpecRecord v1 = new SpecRecord();
        v1.specVersion = 1L;
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(v1));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/users/{id}", null, false, null, "ref")));
        ScanResult r1 = service.analyze(HOST, traffic, window);
        assertThat(r1.active).isEqualTo(1);
        assertThat(r1.shadow).isZero();

        // v2(버전 변경): spec 이 /orders/{id} 로 교체 → 동일 host·매처 캐시는 (host,specVersion) 키라 재빌드
        SpecRecord v2 = new SpecRecord();
        v2.specVersion = 2L;
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(v2));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/orders/{id}", null, false, null, "ref")));
        ScanResult r2 = service.analyze(HOST, traffic, window);
        // 새 매처(v2) 반영: /users 는 더 이상 매칭 안 됨 → Shadow. stale v1 매처였다면 Active 였을 것
        assertThat(r2.active).isZero();
        assertThat(r2.shadow).isEqualTo(1);
    }

    @Test
    void dedupRemovesDuplicateRequestIds() {
        List<ParsedRequest> in = List.of(
                pr("rid-1"), pr("rid-1"),   // 중복 → 1건
                pr("rid-2"),                // 1건
                pr(null), pr(null));        // id 없음 → 둘 다 보존

        List<ParsedRequest> out = DiscoveryJobService.dedupByRequestId(in);

        assertThat(out).hasSize(4); // rid-1(1) + rid-2(1) + null(2)
    }

    @Test
    void windowForComputesIncrementalRange() {
        Instant now = Instant.parse("2026-06-22T10:00:00Z");
        Duration lag = Duration.ofMinutes(10);
        Duration backfill = Duration.ofDays(7);

        // watermark 없음 → [now-lag-backfill, now-lag]
        LogWindow first = DiscoveryJobService.windowFor(now, null, lag, backfill).orElseThrow();
        assertThat(first.to()).isEqualTo(now.minus(lag));
        assertThat(first.from()).isEqualTo(now.minus(lag).minus(backfill));

        // watermark 있음 → [watermark, now-lag]
        Instant last = Instant.parse("2026-06-22T09:00:00Z");
        LogWindow next = DiscoveryJobService.windowFor(now, last, lag, backfill).orElseThrow();
        assertThat(next.from()).isEqualTo(last);
        assertThat(next.to()).isEqualTo(now.minus(lag));

        // watermark 가 end 이후 → 신규 구간 없음
        assertThat(DiscoveryJobService.windowFor(now, now, lag, backfill)).isEmpty();
    }

    // --- helpers ---

    private static ParsedRequest pr(String requestId) {
        return new ParsedRequest("GET", "/x", List.of(), 200, HOST, "ip", "ua",
                Instant.EPOCH, 1, 1, true, null, null, requestId);
    }

    /** ^|^ 구분 20필드 로그 한 줄 생성. */
    private static String line(String method, String uri, int status) {
        return String.join("^|^", List.of(
                "203.0.113.5", "10.0.0.2", "51514", "2026-06-22T09:00:00+09:00", "MISS",
                method + " " + uri + " HTTP/1.1", "OK", "0.010", uri, String.valueOf(status),
                "100", "99", "on", "-", "ua", HOST, HOST, "10.0.0.10", "443", "api"));
    }

    private static ApiDiscoverProperties props() {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://192.168.8.100:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(200)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"));
    }
}
