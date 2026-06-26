// DiscoveryJobService.analyze() end-to-end 파이프라인 테스트 (Loki 제외)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pentasecurity.apidiscover.classify.ApiScorer;
import com.pentasecurity.apidiscover.classify.Classifier;
import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.config.NormalizationProperties;
import com.pentasecurity.apidiscover.config.ParseProperties;
import com.pentasecurity.apidiscover.config.SensitiveKeyProperties;
import com.pentasecurity.apidiscover.domain.ClassificationConfig;
import com.pentasecurity.apidiscover.domain.ClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRecord;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRepository;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.domain.WatermarkRepository;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.ingest.LokiBudget;
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
import com.pentasecurity.apidiscover.normalize.RefererSignalExtractor;
import com.pentasecurity.apidiscover.normalize.SensitiveKeyMatcher;
import com.pentasecurity.apidiscover.parse.LogLineParser;
import com.pentasecurity.apidiscover.report.ReportBuilder;
import com.pentasecurity.apidiscover.spec.SpecFormat;
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
    // 기본 빈 mock → findById empty → basePathStrip=null=off(무회귀). base-path-strip 테스트만 stub.
    private final DomainConfigRepository domainRepo = mock(DomainConfigRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // 기본 빈 mock → resolver 가 무회귀 default 반환. e2e 테스트는 globalClsRepo 를 직접 stub.
    private final ClassificationConfigRepository globalClsRepo = mock(ClassificationConfigRepository.class);
    private final DomainClassificationConfigRepository domainClsRepo =
            mock(DomainClassificationConfigRepository.class);
    private final EffectiveClassificationResolver resolver =
            new EffectiveClassificationResolver(globalClsRepo, domainClsRepo, objectMapper);

    // cross-scan 검출 SoT — stateful mock(저장분을 findByHost 로 반환) → entrenchment/누적 재스캔 테스트 (doc/24·26)
    private final java.util.List<DiscoveredEndpointRecord> discStore = new java.util.ArrayList<>();
    private final DiscoveredEndpointRepository discoveredRepo = statefulDiscovered(discStore);

    private static DiscoveredEndpointRepository statefulDiscovered(java.util.List<DiscoveredEndpointRecord> store) {
        DiscoveredEndpointRepository repo = mock(DiscoveredEndpointRepository.class);
        when(repo.findByHost(any())).thenAnswer(inv -> store.stream()
                .filter(r -> r.getHost().equals(inv.getArgument(0))).toList());
        when(repo.save(any())).thenAnswer(inv -> {
            DiscoveredEndpointRecord r = inv.getArgument(0);
            store.removeIf(e -> e.getHost().equals(r.getHost()) && e.getMethod().equals(r.getMethod())
                    && e.getPathTemplate().equals(r.getPathTemplate())); // upsert(host,method,template)
            store.add(r);
            return r;
        });
        // retention prune — stale(lastSeen < cutoff) 삭제(불변식2). cutoff 는 service 가 window.to()−180d 로 산정.
        doAnswer(inv -> {
            String host = inv.getArgument(0);
            Instant cutoff = inv.getArgument(1);
            store.removeIf(e -> e.getHost().equals(host) && e.getLastSeen() != null && e.getLastSeen().isBefore(cutoff));
            return null;
        }).when(repo).deleteByHostAndLastSeenBefore(any(), any());
        return repo;
    }

    private final DiscoveryJobService service = new DiscoveryJobService(
            new LogLineParser(NORM, ParseProperties.defaults()),
            new InventoryBuilder(new PathNormalizer(), new EndpointKindClassifier(),
                    new CardinalityNormalizer(NORM),
                    new ParamCandidateExtractor(new SensitiveKeyMatcher(SensitiveKeyProperties.defaults()), NORM),
                    new RefererSignalExtractor(new PathNormalizer())),
            specStore,
            new EndpointMatcherCache(),
            new Classifier(new ApiScorer()),
            resolver,
            new ReportBuilder(),
            scanRepo,
            domainRepo,
            mock(WatermarkRepository.class),
            discoveredRepo,
            mock(LokiClient.class),
            mock(LokiQueryBuilder.class),
            mock(LokiBudget.class),
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

        assertThat(result.getDiscovered()).isEqualTo(1);   // /users/{id} 1건
        assertThat(result.getShadow()).isEqualTo(1);
        assertThat(result.getActive()).isZero();
        assertThat(result.getVersion()).isNotBlank();
        assertThat(result.getReportJson()).contains("\"shadow\"");
    }

    @Test
    void analyzeReflectsExcludeConfigFromResolver() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));
        // 전역 설정: /users exclude → /users/{id} 가 DROP_EXCLUDED → Shadow 0 (설정이 결과에 반영, e2e)
        ClassificationConfig global = new ClassificationConfig();
        global.setId(1L);
        global.setProfile(ClassificationProfile.MIDDLE);
        global.setMatcherJson("{\"excludePathPrefixes\":[\"/users\"]}");
        when(globalClsRepo.findById(1L)).thenReturn(Optional.of(global));

        ScanResult result = service.analyze(HOST, List.of(
                line("GET", "/users/1?x=1", 200),
                line("GET", "/users/2?x=2", 200),
                line("GET", "/users/3?x=3", 200)), window);

        assertThat(result.getDiscovered()).isEqualTo(1); // 인벤토리엔 존재
        assertThat(result.getShadow()).isZero();          // exclude 설정 반영 → 미보고(무회귀 대비 차이)
    }

    @Test
    void specMakesMatchedTrafficActive() {
        SpecRecord active = new SpecRecord();
        active.setSpecVersion(5L);
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(active));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/users/{id}", null, false, null, "ref")));
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        ScanResult result = service.analyze(HOST, List.of(
                line("GET", "/users/1", 200),
                line("GET", "/users/2", 200)), window);

        assertThat(result.getActive()).isEqualTo(1);
        assertThat(result.getShadow()).isZero();
        // specVersion = merged canonical 콘텐츠 합성 해시(doc/26 §8) — per-record 5L 아님. 스펙 존재→non-zero.
        assertThat(result.getSpecVersion()).isNotZero();
    }

    @Test
    void sameSpecContentReuploadDoesNotBumpEtagDespitePerRecordVersion() {
        // P3-2: 동일 콘텐츠 재업로드(per-record specVersion 1→2) → 합성 content 버전 동일 → ETag 무bump.
        // SpecSource.documents[].specVersion(monotonic)은 리포트 body 진단용, ETag 투영서 제외.
        var canonical = List.of(new CanonicalEndpoint("GET", "/v2/users/{id}", null, false, null, "ref"));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(canonical);
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));
        List<String> traffic = List.of(line("GET", "/v2/users/1", 200), line("GET", "/v2/users/2", 200));

        SpecRecord v1 = new SpecRecord();
        v1.setSpecVersion(1L);
        v1.setFormat(SpecFormat.OPENAPI);
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(v1));
        ScanResult a = service.analyze(HOST, traffic, window);

        SpecRecord v2 = new SpecRecord(); // 재업로드: 동일 콘텐츠, per-record 버전만 2
        v2.setSpecVersion(2L);
        v2.setFormat(SpecFormat.OPENAPI);
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(v2));
        ScanResult b = service.analyze(HOST, traffic, window);

        assertThat(a.getActive()).isEqualTo(1);
        assertThat(a.getVersion()).isEqualTo(b.getVersion()); // per-record 1→2 임에도 content-stable → 무bump
    }

    @Test
    void basePathStripReattachesStrippedTrafficCorrectingFalseShadow() {
        // doc/27: 스펙 basePath 결합(/v2/users/{id}), 프록시 /v2 strip → 관측 /users/N. basePathStrip 으로 교정.
        SpecRecord rec = new SpecRecord();
        rec.setSpecVersion(1L);
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(rec));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/v2/users/{id}", null, false, null, "ref")));
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));
        List<String> stripped = List.of(
                line("GET", "/users/1?x=1", 200), line("GET", "/users/2?x=2", 200), line("GET", "/users/3?x=3", 200));

        // (대조) basePathStrip 미설정(null) → /users/{id} 미매칭 Shadow + /v2/users/{id} Unused (현행 무회귀)
        ScanResult off = service.analyze(HOST, stripped, window);
        assertThat(off.getActive()).isZero();
        assertThat(off.getShadow()).isEqualTo(1); // false Shadow
        assertThat(off.getUnused()).isEqualTo(1); // false Unused

        // basePathStrip=/v2 → at-match 재부착(/v2/users/{id}) → Active, false Shadow/Unused 해소
        DomainConfig cfg = new DomainConfig();
        cfg.setHost(HOST);
        cfg.setBasePathStrip("/v2");
        when(domainRepo.findById(HOST)).thenReturn(Optional.of(cfg));
        ScanResult on = service.analyze(HOST, stripped, window);
        assertThat(on.getActive()).isEqualTo(1);
        assertThat(on.getShadow()).isZero();
        assertThat(on.getUnused()).isZero();

        // findings 변화 → ETag bump(정당); 동일 데이터 재스캔 → 동일 version(결정적·시간非의존)
        assertThat(on.getVersion()).isNotEqualTo(off.getVersion());
        ScanResult on2 = service.analyze(HOST, stripped, window);
        assertThat(on2.getVersion()).isEqualTo(on.getVersion());
    }

    @Test
    void versionIsStableAcrossIdenticalContent() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        List<String> lines = List.of(line("GET", "/users/1", 200));
        String v1 = service.analyze(HOST, lines, window).getVersion();
        // 다른 윈도우(시간대)라도 findings 내용이 같으면 version 동일 (doc/07 §8)
        LogWindow later = new LogWindow(Instant.EPOCH.plusSeconds(7200), Instant.EPOCH.plusSeconds(10800));
        String v2 = service.analyze(HOST, lines, later).getVersion();

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

        assertThat(result.getShadow()).isZero();
        assertThat(result.getReportJson())
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
        g.setId(1L);
        g.setProfile(ClassificationProfile.MIDDLE);
        g.setMatcherJson("{\"excludePathPrefixes\":[\"/page\"]}");
        when(globalClsRepo.findById(1L)).thenReturn(Optional.of(g));
        resolver.invalidateAll();
        ScanResult b = service.analyze(HOST, lines, window);

        assertThat(a.getShadow()).isZero();
        assertThat(b.getShadow()).isZero();
        assertThat(a.getDiscovered()).isEqualTo(b.getDiscovered()); // summary 동일
        assertThat(a.getVersion()).isNotEqualTo(b.getVersion());     // dropped 분포 변화가 ETag 에 반영 (doc/12 §4)
    }

    @Test
    void reportJsonAndEtagReflectNonExistentDrop() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // 스캔 A: /probe/1·/probe/2 전부 404(INFERRED /probe/{id}) → 비실재 hard-drop. droppedNonExistent=1, reportJson 노출
        ScanResult a = service.analyze(HOST, List.of(
                line("GET", "/probe/1", 404), line("GET", "/probe/2", 404)), window);
        assertThat(a.getReportJson()).contains("\"droppedNonExistent\"").contains("\"notFound\":1");

        // 스캔 B: /probe/2 가 200(실재) → 100%-404 아님 → drop 0. notFound=0 + ETag 다름(실재성 분포 변화 반영, doc/19 §4)
        ScanResult b = service.analyze(HOST, List.of(
                line("GET", "/probe/1", 404), line("GET", "/probe/2", 200)), window);
        assertThat(b.getReportJson()).contains("\"notFound\":0");
        assertThat(a.getVersion()).isNotEqualTo(b.getVersion());
    }

    @Test
    void reportJsonExposesEndpointKindSignal() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // line() 헬퍼는 referer="-"(null)·정적 미경유 → endpoint_kind referer 신호 DORMANT, reportJson 에 노출 (doc/20 §5)
        ScanResult r = service.analyze(HOST, List.of(line("GET", "/x", 200)), window);
        assertThat(r.getReportJson()).contains("\"endpointKindSignal\"").contains("\"status\":\"DORMANT\"");
    }

    @Test
    void preflightSignalDormantByDefaultAndEtagBumpsToActive() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // 동일 트래픽(OPTIONS preflight + GET), 파서만 다름 — findings 동일, preflightSignal status 만 차이
        List<String> lines = List.of(
                lineAcrm("OPTIONS", "/api/x", 204, "GET"), lineAcrm("GET", "/api/x", 200, "-"));

        // 기본 파서(idx=-1) → acrm 무시 → DORMANT (무회귀)
        ScanResult dormant = service.analyze(HOST, lines, window);
        assertThat(dormant.getReportJson()).contains("\"preflightSignal\"").contains("\"status\":\"DORMANT\"");

        // acrm 파서(idx=20) → acrm 읽음 → ACTIVE
        var acrmService = new DiscoveryJobService(new LogLineParser(NORM, new ParseProperties(20)),
                new InventoryBuilder(new PathNormalizer(), new EndpointKindClassifier(),
                        new CardinalityNormalizer(NORM),
                        new ParamCandidateExtractor(new SensitiveKeyMatcher(SensitiveKeyProperties.defaults()), NORM),
                        new RefererSignalExtractor(new PathNormalizer())),
                specStore, new EndpointMatcherCache(), new Classifier(new ApiScorer()), resolver,
                new ReportBuilder(), scanRepo, mock(DomainConfigRepository.class), mock(WatermarkRepository.class),
                mock(DiscoveredEndpointRepository.class), mock(LokiClient.class), mock(LokiQueryBuilder.class),
                mock(LokiBudget.class), objectMapper, props());
        ScanResult active = acrmService.analyze(HOST, lines, window);
        assertThat(active.getReportJson()).contains("\"status\":\"ACTIVE\"");

        // status 전환(DORMANT→ACTIVE)이 ETag 에 반영 (doc/23 §9.5)
        assertThat(dormant.getVersion()).isNotEqualTo(active.getVersion());
    }

    @Test
    void crossScanReScanSameDataYieldsSameEtagAndAccumulatesDiscovered() {
        SpecRecord rec = new SpecRecord();
        rec.setSpecVersion(1L);
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(rec));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/v2/old", null, true, null, "ref"))); // deprecated → Zombie
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        List<String> lines = List.of(line("GET", "/v2/old", 200), line("GET", "/v2/old", 200));
        ScanResult s1 = service.analyze(HOST, lines, window);
        assertThat(s1.getZombie()).isEqualTo(1);
        // 검출 SoT 누적(doc/26 §2) — 검출 endpoint 기록(spec 매칭 무관) + version=v2(path ^v\d+$ 도출, §4)
        assertThat(discStore).filteredOn(r -> r.getHost().equals(HOST) && r.getPathTemplate().equals("/v2/old"))
                .singleElement()
                .satisfies(r -> assertThat(r.getVersion()).isEqualTo("v2"));

        // 재스캔(동일 데이터+누적 firstSeen): now 무의존·lifespan 동일 → 동일 version (doc/24 §5, doc/26 §8)
        ScanResult s2 = service.analyze(HOST, lines, window);
        assertThat(s2.getVersion()).isEqualTo(s1.getVersion());
    }

    @Test
    void retentionPrunesStaleDiscoveredByWindowBoundaryNotNowAndIsIdempotent() {
        // doc/26 §2 불변식: stale(lastSeen < window.to()−180d) 삭제 / active(> cutoff) 보존. cutoff 는 데이터 윈도우 기준(now() 비의존).
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // window.to()=2020-01-01 → cutoff = to−180d ≈ 2019-07-05. now()=2026 라면 둘 다 prune 됐을 것
        // → /recent(2019-12, > cutoff) 보존이 cutoff=window.to()−180d(=데이터 경계, now() 비의존) 임을 증명.
        Instant to = Instant.parse("2020-01-01T00:00:00Z");
        LogWindow w = new LogWindow(to.minusSeconds(3600), to);
        discStore.add(seedDiscovered("GET", "/stale", Instant.parse("2019-01-01T00:00:00Z")));  // < cutoff → prune
        discStore.add(seedDiscovered("GET", "/recent", Instant.parse("2019-12-01T00:00:00Z"))); // > cutoff → 보존

        service.analyze(HOST, List.of(), w); // 무트래픽 — prune 만 검증(upsert 무영향)
        assertThat(discStore).extracting(r -> r.getPathTemplate()).containsExactly("/recent"); // stale 삭제·recent 보존

        // 동일 윈도우 재스캔: recent 여전히 > cutoff → 보존, 중복 없음(idempotent)
        service.analyze(HOST, List.of(), w);
        assertThat(discStore).extracting(r -> r.getPathTemplate()).containsExactly("/recent");
    }

    @Test
    void reportExposesSpecSourceWarningsAndLowConfidenceFlag() {
        // §A: 업로드 시 영속된 warnings → specSource 로 로드(재파싱 없음), low_confidence 플래그 노출
        SpecRecord rec = new SpecRecord();
        rec.setSpecVersion(2L);
        rec.setFormat(SpecFormat.OPENAPI);
        rec.setWarningsJson("[\"row 3 skipped: missing method\"]");
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(rec));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/v2/old", null, true, null, "ref"))); // deprecated → Zombie
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        ScanResult r = service.analyze(HOST, List.of(
                line("GET", "/v2/old", 200), line("GET", "/v2/old", 200)), window);
        assertThat(r.getReportJson()).contains("\"specSource\"").contains("missing method").contains("\"low_confidence\"");
    }

    @Test
    void activeParamsQueryCountChangeDoesNotBumpEtag() {
        // §B.3: query param count 만 다른 두 스캔 → ETag 이름집합 투영 동일 → 무bump
        SpecRecord rec = new SpecRecord();
        rec.setSpecVersion(1L);
        rec.setFormat(SpecFormat.OPENAPI);
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(rec));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/v2/users/{id}", null, false, null, "ref"))); // Active
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        ScanResult a = service.analyze(HOST, List.of(
                line("GET", "/v2/users/1?expand=full", 200), line("GET", "/v2/users/2?expand=full", 200)), window);
        ScanResult b = service.analyze(HOST, List.of(
                line("GET", "/v2/users/1?expand=full", 200), line("GET", "/v2/users/2?expand=full", 200),
                line("GET", "/v2/users/3?expand=full", 200), line("GET", "/v2/users/4?expand=full", 200)), window);
        assertThat(a.getActive()).isEqualTo(1);
        assertThat(a.getVersion()).isEqualTo(b.getVersion()); // expand count 2 vs 4, 이름집합 {expand} 동일 → 무bump
    }

    @Test
    void scanStatusTotalDroppedSumsThreeDroppedKinds() {
        // §C: totalDropped = droppedNonApi.total + byLimit.total + nonExistent.notFound. /result 상세 불변
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // /page x2 → DROP_LOW_SCORE(non_api 1), /probe/1·/probe/2 404-only → nonExistent 1
        ScanResult r = service.analyze(HOST, List.of(
                line("GET", "/page", 200), line("GET", "/page", 200),
                line("GET", "/probe/1", 404), line("GET", "/probe/2", 404)), window);

        assertThat(r.getTotalDropped()).isEqualTo(2); // 1(lowScore) + 0 + 1(nonExistent)
        assertThat(r.getReportJson()).contains("\"droppedNonApi\"").contains("\"droppedNonExistent\""); // 상세 불변
    }

    @Test
    void zombieSeverityCreepWithinBandDoesNotBumpEtag() {
        SpecRecord rec = new SpecRecord();
        rec.setSpecVersion(1L);
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(rec));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/v2/old", null, true, null, "ref")));
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // hits 2 vs 3 → Zombie raw severity 미세 creep(≈0.38→0.40)이나 같은 band(MEDIUM) → ETag(band 투영) 동일 (doc/24 §5)
        ScanResult a = service.analyze(HOST, List.of(
                line("GET", "/v2/old", 200), line("GET", "/v2/old", 200)), window);
        ScanResult b = service.analyze(HOST, List.of(
                line("GET", "/v2/old", 200), line("GET", "/v2/old", 200), line("GET", "/v2/old", 200)), window);
        assertThat(a.getZombie()).isEqualTo(1);
        assertThat(a.getVersion()).isEqualTo(b.getVersion());
    }

    @Test
    void reportJsonExposesTypeDistribution() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // $type=document 2건 → corpus 히스토그램 reportJson 노출 (doc/21 Tier1)
        ScanResult r = service.analyze(HOST, List.of(
                lineT("GET", "/p1", 200, "document"), lineT("GET", "/p2", 200, "document")), window);
        assertThat(r.getReportJson()).contains("\"typeDistribution\"").contains("\"document\"");
    }

    @Test
    void etagBumpsOnNewTypeKeyButNotOnCountChange() {
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());
        when(scanRepo.findById(HOST)).thenReturn(Optional.empty());
        when(scanRepo.save(any(ScanResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // A: /x document×3 (dominant document → WEB_PAGE, 저득점 drop). 히스토그램 키집합={document}
        ScanResult a = service.analyze(HOST, List.of(
                lineT("GET", "/x", 200, "document"), lineT("GET", "/x", 200, "document"),
                lineT("GET", "/x", 200, "document")), window);
        // B: document×5 — count 만 변동, 키집합 동일={document}, findings/summary 불변 → ETag 무bump (doc/21 §3)
        ScanResult b = service.analyze(HOST, List.of(
                lineT("GET", "/x", 200, "document"), lineT("GET", "/x", 200, "document"),
                lineT("GET", "/x", 200, "document"), lineT("GET", "/x", 200, "document"),
                lineT("GET", "/x", 200, "document")), window);
        // C: document×3 + xhr×1 (dominant 여전히 document) — 신규 $type 키 출현 → 키집합={document,xhr} → ETag bump
        ScanResult c = service.analyze(HOST, List.of(
                lineT("GET", "/x", 200, "document"), lineT("GET", "/x", 200, "document"),
                lineT("GET", "/x", 200, "document"), lineT("GET", "/x", 200, "xhr")), window);

        assertThat(a.getVersion()).isEqualTo(b.getVersion());    // count 변동 → 무bump
        assertThat(a.getVersion()).isNotEqualTo(c.getVersion());  // 신규 $type 키 → bump
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

        assertThat(result.getShadow()).isEqualTo(1);
        assertThat(result.getReportJson())
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
                new ParamCandidateExtractor(new SensitiveKeyMatcher(SensitiveKeyProperties.defaults()), capProps),
                new RefererSignalExtractor(new PathNormalizer()));
        var cappedService = new DiscoveryJobService(new LogLineParser(NORM, ParseProperties.defaults()), cappedInventory, specStore,
                new EndpointMatcherCache(), new Classifier(new ApiScorer()), resolver, new ReportBuilder(), scanRepo,
                mock(DomainConfigRepository.class), mock(WatermarkRepository.class),
                mock(DiscoveredEndpointRepository.class), mock(LokiClient.class),
                mock(LokiQueryBuilder.class), mock(LokiBudget.class), objectMapper, props());

        ScanResult result = cappedService.analyze(HOST, List.of(
                line("GET", "/a/x", 200), line("GET", "/a/x", 200), line("GET", "/a/x", 200),
                line("GET", "/b/x", 200), line("GET", "/b/x", 200),
                line("GET", "/c/x", 200)), window);

        // droppedByLimit 가 reportJson(=ETag 입력)에 임베드 → 상한 이벤트가 결과 콘텐츠에 반영 (doc/13 §4.2)
        assertThat(result.getReportJson()).contains("\"droppedByLimit\"").contains("\"templates\":1");
        assertThat(result.getVersion()).isNotBlank();
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
        v1.setSpecVersion(1L);
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(v1));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/users/{id}", null, false, null, "ref")));
        ScanResult r1 = service.analyze(HOST, traffic, window);
        assertThat(r1.getActive()).isEqualTo(1);
        assertThat(r1.getShadow()).isZero();

        // v2(버전 변경): spec 이 /orders/{id} 로 교체 → 동일 host·매처 캐시는 (host,specVersion) 키라 재빌드
        SpecRecord v2 = new SpecRecord();
        v2.setSpecVersion(2L);
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(v2));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/orders/{id}", null, false, null, "ref")));
        ScanResult r2 = service.analyze(HOST, traffic, window);
        // 새 매처(v2) 반영: /users 는 더 이상 매칭 안 됨 → Shadow. stale v1 매처였다면 Active 였을 것
        assertThat(r2.getActive()).isZero();
        assertThat(r2.getShadow()).isEqualTo(1);
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

        // watermark 없음 → [now-lag-backfill, now-lag] (maxWindow=ZERO=무제한)
        LogWindow first = DiscoveryJobService.windowFor(now, null, lag, backfill, Duration.ZERO).orElseThrow();
        assertThat(first.to()).isEqualTo(now.minus(lag));
        assertThat(first.from()).isEqualTo(now.minus(lag).minus(backfill));

        // watermark 있음 → [watermark, now-lag]
        Instant last = Instant.parse("2026-06-22T09:00:00Z");
        LogWindow next = DiscoveryJobService.windowFor(now, last, lag, backfill, Duration.ZERO).orElseThrow();
        assertThat(next.from()).isEqualTo(last);
        assertThat(next.to()).isEqualTo(now.minus(lag));

        // watermark 가 end 이후 → 신규 구간 없음
        assertThat(DiscoveryJobService.windowFor(now, now, lag, backfill, Duration.ZERO)).isEmpty();
    }

    @Test
    void windowForCapsBackfillAtMaxWindow() {
        Instant now = Instant.parse("2026-06-22T10:00:00Z");
        Duration lag = Duration.ofMinutes(10);
        Duration backfill = Duration.ofDays(7);
        Duration maxWindow = Duration.ofHours(6);

        // 미스캔(7일 백필) + max-window 6h → end = start + 6h (7일 일괄 pull 차단, A doc/33 §2)
        Instant end = now.minus(lag);
        Instant start = end.minus(backfill);
        LogWindow w = DiscoveryJobService.windowFor(now, null, lag, backfill, maxWindow).orElseThrow();
        assertThat(w.from()).isEqualTo(start);
        assertThat(w.to()).isEqualTo(start.plus(maxWindow));        // 절단됨
        assertThat(w.to()).isBefore(end);

        // 정상상태(델타 < max-window) → 상한 미발동(전체 윈도우 유지)
        Instant recent = end.minus(Duration.ofMinutes(30));
        LogWindow small = DiscoveryJobService.windowFor(now, recent, lag, backfill, maxWindow).orElseThrow();
        assertThat(small.from()).isEqualTo(recent);
        assertThat(small.to()).isEqualTo(end);                      // 미절단
    }

    // --- helpers ---

    /** discStore 사전 적재용 검출 record(prune 경계 테스트). firstSeen=lastSeen 동일. */
    private static DiscoveredEndpointRecord seedDiscovered(String method, String template, Instant lastSeen) {
        DiscoveredEndpointRecord r = new DiscoveredEndpointRecord();
        r.setHost(HOST);
        r.setMethod(method);
        r.setPathTemplate(template);
        r.setFirstSeen(lastSeen);
        r.setLastSeen(lastSeen);
        return r;
    }

    private static ParsedRequest pr(String requestId) {
        return new ParsedRequest("GET", "/x", List.of(), 200, HOST, "ip", "ua",
                Instant.EPOCH, 1, 1, true, null, null, requestId);
    }

    /** ^|^ 구분 20필드 로그 한 줄 생성. */
    private static String line(String method, String uri, int status) {
        return lineT(method, uri, status, "api");
    }

    /** line() 변형 — $type(필드19) 지정. $type taxonomy/히스토그램 테스트용 (doc/21). */
    private static String lineT(String method, String uri, int status, String type) {
        return String.join("^|^", List.of(
                "203.0.113.5", "10.0.0.2", "51514", "2026-06-22T09:00:00+09:00", "MISS",
                method + " " + uri + " HTTP/1.1", "OK", "0.010", uri, String.valueOf(status),
                "100", "99", "on", "-", "ua", HOST, HOST, "10.0.0.10", "443", type));
    }

    /** line() + acrm 필드(idx20). acrm 파서(ParseProperties(20))와 함께 M3 테스트용 (doc/23 §9). */
    private static String lineAcrm(String method, String uri, int status, String acrm) {
        return lineT(method, uri, status, "api") + "^|^" + acrm;
    }

    private static ApiDiscoverProperties props() {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://192.168.8.100:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(200)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 200,
                        "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$"),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), 100, Duration.ZERO, 0, 0L, true, Duration.ZERO, 0, false, Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(24), 500, Duration.ofHours(24), "", Duration.ofDays(14), Duration.ofDays(1)));
    }
}
