// 도메인 1회 스캔 파이프라인 오케스트레이션 (doc/01 파이프라인)
package com.pentasecurity.apidiscover.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.classify.ClassificationResult;
import com.pentasecurity.apidiscover.classify.Classifier;
import com.pentasecurity.apidiscover.classify.EffectiveClassification;
import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRecord;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRepository;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.domain.Watermark;
import com.pentasecurity.apidiscover.domain.WatermarkRepository;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.ingest.LokiBudget;
import com.pentasecurity.apidiscover.ingest.LokiClient;
import com.pentasecurity.apidiscover.ingest.LokiQueryBuilder;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.match.EndpointMatcherCache;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveryReport;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.SpecSource;
import com.pentasecurity.apidiscover.model.VersionTag;
import com.pentasecurity.apidiscover.normalize.InventoryBuilder;
import com.pentasecurity.apidiscover.parse.LogLineParser;
import com.pentasecurity.apidiscover.report.EtagUtil;
import com.pentasecurity.apidiscover.report.ReportBuilder;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscoveryJobService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryJobService.class);

    private final LogLineParser parser;
    private final InventoryBuilder inventoryBuilder;
    private final SpecStore specStore;
    private final EndpointMatcherCache matcherCache;
    private final Classifier classifier;
    private final EffectiveClassificationResolver classificationResolver;
    private final ReportBuilder reportBuilder;
    private final ScanResultRepository scanRepo;
    private final DomainConfigRepository domainRepo;
    private final WatermarkRepository watermarkRepo;
    private final DiscoveredEndpointRepository discoveredRepo;
    private final LokiClient lokiClient;
    private final LokiQueryBuilder queryBuilder;
    private final LokiBudget budget;
    private final ObjectMapper objectMapper;
    private final ApiDiscoverProperties props;

    public DiscoveryJobService(LogLineParser parser,
                               InventoryBuilder inventoryBuilder,
                               SpecStore specStore,
                               EndpointMatcherCache matcherCache,
                               Classifier classifier,
                               EffectiveClassificationResolver classificationResolver,
                               ReportBuilder reportBuilder,
                               ScanResultRepository scanRepo,
                               DomainConfigRepository domainRepo,
                               WatermarkRepository watermarkRepo,
                               DiscoveredEndpointRepository discoveredRepo,
                               LokiClient lokiClient,
                               LokiQueryBuilder queryBuilder,
                               LokiBudget budget,
                               ObjectMapper objectMapper,
                               ApiDiscoverProperties props) {
        this.parser = parser;
        this.inventoryBuilder = inventoryBuilder;
        this.specStore = specStore;
        this.matcherCache = matcherCache;
        this.classifier = classifier;
        this.classificationResolver = classificationResolver;
        this.reportBuilder = reportBuilder;
        this.scanRepo = scanRepo;
        this.domainRepo = domainRepo;
        this.watermarkRepo = watermarkRepo;
        this.discoveredRepo = discoveredRepo;
        this.lokiClient = lokiClient;
        this.queryBuilder = queryBuilder;
        this.budget = budget;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /** 한 도메인 스캔 — 윈도우 상한 = {@code scan.max-window}(기본). off-peak 등 상한 스위치는 오버로드(아래). */
    public void runScan(String host) {
        runScan(host, props.scan().maxWindow());
    }

    /**
     * 한 도메인 스캔 실행: Loki 수집(S0) → 분석. 윈도우 = watermark 증분([lastEnd, now−lag), 미스캔=backfill 시작점),
     * per-scan 상한 {@code maxWindow} 로 슬라이스(A, doc/33 §2). off-peak(D)면 호출측이 off-peak-max-window 주입(코어 불변).
     * skip-if-current·advance-on-success.
     */
    public void runScan(String host, Duration maxWindow) {
        DomainConfig cfg = domainRepo.findById(host).orElse(null);
        if (cfg == null || !cfg.isEnabled()) {
            log.info("skip scan: host={} not found or disabled", host);
            return;
        }
        Optional<LogWindow> next = nextWindow(host, maxWindow);
        if (next.isEmpty()) {
            log.info("skip scan: host={} no new window (watermark up to date)", host);
            return;
        }
        LogWindow window = next.get();
        // PR1.1(① doc/33 §14): 슬라이스 외부·hostname 내부 순회 + per-scan 캡/예산 → 부분 수집.
        BoundedCollection bc = collectBounded(cfg, window);
        if (!bc.consumedUpTo().isAfter(window.from())) {
            // 한 슬라이스도 완료 못함(첫 슬라이스 전 예산/캡 소진) → 전진 없음, 다음 틱 resume
            log.info("defer scan: host={} budget/cap exhausted before any slice", host);
            return;
        }
        LogWindow consumed = new LogWindow(window.from(), bc.consumedUpTo());
        analyze(host, bc.lines(), consumed);
        // ★부분 watermark 전진 = 마지막 완료 슬라이스 끝(모든 hostname 완료분만 → gap-free). 다음 틱이 이어서 resume.
        advanceWatermark(host, bc.consumedUpTo());
        log.info("scan complete: host={} window={} consumedUpTo={}{}", host, window, bc.consumedUpTo(),
                bc.consumedUpTo().isBefore(window.to()) ? " (partial — resume next tick)" : "");
    }

    /** 슬라이스 부분 수집 결과: 누적 라인 + 모든 hostname 완료된 마지막 슬라이스 끝(watermark 전진 한계). 테스트 가시(package-private). */
    record BoundedCollection(List<String> lines, Instant consumedUpTo) {}

    /**
     * 특정 (엣지 hostname, domain) 1쌍에 대한 온디맨드 조회+분석 (외부 API 직접 호출용).
     */
    public ScanResult runOnDemand(String edgeHostname, String domain, LogWindow window) {
        String logql = queryBuilder.build(edgeHostname, domain);
        List<String> lines = lokiClient.queryRange(logql, window);
        return analyze(domain, lines, window);
    }

    /**
     * 운영자 온디맨드 스캔 (doc/33 §7, CLI). edge 지정 시 그 엣지만, 미지정 시 도메인 hostnames 전체 collect → analyze.
     * ★watermark 미전진(임시 스냅샷만 — 전진 시 스케줄러가 [lastEnd, now−win) skip=데이터 갭). 누적(discovered_endpoint)·
     * 최신 결과(ScanResult)만 갱신, 증분 진행은 스케줄러가 계속 소유.
     */
    public ScanResult scanOnDemand(String domain, LogWindow window, String edge) {
        if (edge != null && !edge.isBlank()) {
            return runOnDemand(edge, domain, window); // 단일 엣지
        }
        DomainConfig cfg = domainRepo.findById(domain)
                .orElseThrow(() -> new IllegalArgumentException("domain not found: " + domain));
        return analyze(domain, collect(cfg, window), window);
    }

    /**
     * 온디맨드 윈도우: [to−win, to), to=now−lag, win=min(requested 또는 max-window, max-window) (doc/33 §7).
     * now() 는 CLI 경계 1회 — 임시 스냅샷이라 watermark 무관.
     */
    public LogWindow onDemandWindow(Duration requested) {
        Duration max = props.scan().maxWindow();
        Duration win = (requested != null) ? requested : max;
        if (max != null && !max.isZero() && win.compareTo(max) > 0) {
            win = max; // 상한 적용
        }
        Instant to = Instant.now().minus(props.schedule().ingestLag());
        return new LogWindow(to.minus(win), to);
    }

    /**
     * 분석 파이프라인(Loki 와 분리, 테스트 가능):
     * 파싱(A) → 인벤토리(B) → Spec 로드 → 매칭(D) → 분류(E) → 리포트(F) → ScanResult 영속(+ETag).
     */
    @Transactional
    public ScanResult analyze(String host, List<String> rawLines, LogWindow window) {
        // (A) 파싱 — 손상/비표준 라인은 폐기
        List<ParsedRequest> parsed = new ArrayList<>(rawLines.size());
        for (String line : rawLines) {
            parser.parse(line).ifPresent(parsed::add);
        }
        // request_id 기반 dedup (윈도우 겹침/재시도 흡수, doc/05 §3.3)
        List<ParsedRequest> requests = dedupByRequestId(parsed);

        // Spec 로드 — 없으면 빈 스펙(매처는 아무것도 매칭 못함 → 관찰분 전부 Shadow)
        Optional<SpecRecord> active = specStore.activeMeta(host);
        List<CanonicalEndpoint> spec = active.isPresent()
                ? specStore.loadActiveCanonical(host) : List.of();
        // 합성 spec 버전 = merged canonical 콘텐츠 해시(doc/26 §8) — per-record specVersion 대신.
        // 동일 콘텐츠=동일 버전 → matcherCache 안정·ETag 결정적. 무스펙=0.
        long specVersion = spec.isEmpty() ? 0L : SpecStore.syntheticVersion(spec, objectMapper);
        // 스펙 출처/경고/문서 메타 → 리포트 specSource (doc/25 §A.2, doc/26 §4 멀티문서 union+documents).
        // activeRecords 미가용(테스트 mock 등) 시 activeMeta 단건으로 폴백.
        SpecSource specSource = active
                .map(meta -> {
                    List<SpecRecord> recs = specStore.activeRecords(host);
                    return SpecStore.specSourceFrom(specVersion, recs.isEmpty() ? List.of(meta) : recs, objectMapper);
                })
                .orElse(SpecSource.EMPTY);
        // (host, specVersion) 캐시 — 동일 버전 재스캔 시 matcher 재생성 제거. 없으면 supplier 로 빌드 (doc/15 §3)
        EndpointMatcher matcher = matcherCache.get(host, specVersion, () -> new EndpointMatcher(spec));

        // (B) 인벤토리(+T1 승격/상한·T2 param·T3 sensitive) → (E) 분류 → (F) 리포트
        InventoryBuilder.InventoryResult inventory = inventoryBuilder.buildWithLimits(requests, matcher);
        List<DiscoveredEndpoint> discovered = inventory.endpoints();
        // effective 분류 설정(전역+도메인 병합) 해석 → scorer/hints 주입 (doc/10 §6). 설정 부재 시 무회귀.
        EffectiveClassification eff = classificationResolver.resolve(host);
        // cross-scan recency: 검출 SoT(discovered_endpoint) 로드 → Zombie severity entrenchment 입력
        // (doc/24 §7, doc/26 §8 — EndpointHistory 흡수). 콜드스타트=빈 map=현행 무회귀. signature 키.
        Map<String, DiscoveredEndpointRecord> priorDiscovered = loadDiscovered(host, window);
        Map<String, Instant> priorFirstSeen = new HashMap<>();
        priorDiscovered.forEach((sig, rec) -> {
            if (rec.getFirstSeen() != null) {
                priorFirstSeen.put(sig, rec.getFirstSeen());
            }
        });
        // base-path-strip prefix (doc/27 §3). null=off=현행. 매처가 as-is 우선·미매칭 시 prefix 재부착 재시도.
        String stripPrefix = domainRepo.findById(host).map(c -> c.getBasePathStrip()).orElse(null);
        // findings + non_api dropped 메트릭 동시 산출 (doc/12 §1, doc/24·26·27)
        ClassificationResult classified = classifier.classifyWithMetrics(
                discovered, spec, matcher, eff.scorer(), eff.hints(), priorFirstSeen, stripPrefix);
        List<Finding> findings = classified.findings();
        // OPTIONS 는 CORS 신호로만 쓰고 보고에서 제외되므로 인벤토리 카운트에서도 뺀다 (과대집계 방지)
        long reportedCount = discovered.stream()
                .filter(d -> !"OPTIONS".equalsIgnoreCase(d.method()))
                .count();
        DiscoveryReport report = reportBuilder.build(
                host, specVersion, window, (int) reportedCount, findings,
                classified.dropped(), inventory.droppedByLimit(), inventory.droppedNonExistent(),
                inventory.endpointKindSignal(), inventory.typeDistribution(), classified.preflightSignal(),
                specSource);

        ScanResult result = persist(host, report);
        // 검출 SoT 누적 upsert(firstSeen min/lastSeen max/스냅샷) + cap·retention prune (doc/26 §2).
        upsertDiscovered(host, priorDiscovered, discovered, matcher, window);
        return result;
    }

    // 검출 SoT 누적 가드 (doc/26 §2). cap=host template 상한(doc/13 동일 5000), retention=stale prune 기준.
    // ponytail: 상수(현 규모 충분). 도메인별 override 필요 시 @ConfigurationProperties seam(후속).
    private static final long TEMPLATE_CAP = 5000L;
    private static final Duration RETENTION = Duration.ofDays(180);

    /** 검출 SoT 로드(host) — recency 입력 + upsert 기준. retention prune 후 signature→record 맵 (doc/26 §2). */
    private Map<String, DiscoveredEndpointRecord> loadDiscovered(String host, LogWindow window) {
        if (window != null && window.to() != null) {
            // stale(lastSeen < scanEnd − retention) prune — 데이터 ts 기준(now 미사용), 스캐너 noise 누적 방지
            discoveredRepo.deleteByHostAndLastSeenBefore(host, window.to().minus(RETENTION));
        }
        Map<String, DiscoveredEndpointRecord> out = new HashMap<>();
        for (DiscoveredEndpointRecord rec : discoveredRepo.findByHost(host)) {
            out.put(signatureOf(rec), rec);
        }
        return out;
    }

    /**
     * 스캔 discovered → discovered_endpoint 누적 upsert (firstSeen min/lastSeen max/최신 윈도우 스냅샷, doc/26 §2).
     * prior = loadDiscovered 맵 재사용(단건 재조회 회피). cap 초과 신규 identity 는 drop(기존 갱신은 항상).
     */
    private void upsertDiscovered(String host, Map<String, DiscoveredEndpointRecord> prior,
                                  List<DiscoveredEndpoint> discovered, EndpointMatcher matcher, LogWindow window) {
        Instant scanEnd = (window != null) ? window.to() : null;
        long count = prior.size();
        int dropped = 0;
        for (DiscoveredEndpoint d : discovered) {
            DiscoveredEndpointRecord rec = prior.get(d.signature());
            if (rec == null) {
                if (count >= TEMPLATE_CAP) {
                    dropped++; // cap: 신규 identity 만 제한 — 스캐너 noise 무한 성장 방지
                    continue;
                }
                rec = new DiscoveredEndpointRecord();
                rec.setHost(host);
                rec.setMethod(d.method());
                rec.setPathTemplate(d.pathTemplate());
                count++;
            }
            DiscoveredEndpoint.Metrics m = d.metrics();
            rec.setFirstSeen(earliest(rec.getFirstSeen(), (m != null) ? m.firstSeen() : null));
            rec.setLastSeen(latest(rec.getLastSeen(), (m != null) ? m.lastSeen() : null));
            rec.setLastScanAt(scanEnd);
            rec.setHits((m != null) ? m.hits() : 0L);
            rec.setStatusDistJson(toJson((m != null && m.statusDist() != null) ? m.statusDist() : Map.of()));
            rec.setHadQuery(d.hadQuery());
            rec.setNonBrowserUa(d.nonBrowserUa());
            rec.setParamsJson(toJson(d.params()));
            rec.setTemplateSource((d.templateSource() != null) ? d.templateSource().name() : null);
            rec.setEndpointKind((d.endpointKind() != null) ? d.endpointKind().name() : null);
            rec.setKindConfidence(d.kindConfidence());
            rec.setVersion(deriveVersion(d, matcher));
            discoveredRepo.save(rec);
        }
        if (dropped > 0) {
            log.warn("discovered_endpoint cap {} reached for host={} — {} new identities dropped",
                    TEMPLATE_CAP, host, dropped);
        }
    }

    /** 검출 version 도출: path ^v\d+$ 세그먼트(doc/16) → 매칭 spec.version → null (doc/26 §4). */
    private static String deriveVersion(DiscoveredEndpoint d, EndpointMatcher matcher) {
        String pathV = VersionTag.ofPath(d.pathTemplate());
        if (pathV != null) {
            return pathV;
        }
        return matcher.match(d.method(), d.host(), d.pathTemplate())
                .map(CanonicalEndpoint::version).orElse(null);
    }

    /** 검출 record → DiscoveredEndpoint.signature 동일 포맷 키 "{METHOD} {host} {template}" (priorFirstSeen 키). */
    private static String signatureOf(DiscoveredEndpointRecord rec) {
        return rec.getMethod() + " " + rec.getHost() + " " + rec.getPathTemplate();
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return (b == null || a.isBefore(b)) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return (b == null || a.isAfter(b)) ? a : b;
    }

    private ScanResult persist(String host, DiscoveryReport report) {
        String reportJson = toJson(report);
        // version(ETag)은 generatedAt/window 를 제외한 '내용'으로 산정 → 동일 결과는 동일 버전 (doc/07 §8).
        // dropped 메트릭·endpoint_kind 신호 모두 결과 콘텐츠 → 포함(분포 변화 반영, doc/12 §4, doc/13 §4.2, doc/19 §4, doc/20 §5)
        // $type 분포는 distinct 키집합(정렬, count 제외)만 → 신규 값=드리프트 bump, count 변동=무bump (doc/21 §3 Tier1)
        // preflightSignal 은 status 만 → DORMANT↔ACTIVE 전환=실제 분류 변화 bump, acrm count churn 없음 (doc/23 §9.5)
        // Zombie severity 는 band 로 투영 → entrenchment/hits 발 미세 creep 무bump, band 전이 시만 bump (doc/24 §5)
        // 합성 content 버전(report.specVersion)이 spec 콘텐츠 변화를 반영. specSource 는 content-stable 투영만
        // (format/warnings/문서명·포맷) — per-record monotonic specVersion 제외 → 동일 콘텐츠 재업로드 무bump (doc/26 §8, P3-2)
        String version = EtagUtil.of(toJson(List.of(
                report.specVersion(), report.summary(), findingsEtagView(report.findings()),
                report.droppedNonApi(), report.droppedByLimit(), report.droppedNonExistent(),
                report.endpointKindSignal(), report.typeDistribution().distinctKeys(),
                report.preflightSignal().status(), specSourceEtagView(report.specSource()))));

        ScanResult r = scanRepo.findById(host).orElseGet(ScanResult::new);
        r.setHost(host);
        r.setState("idle");
        r.setLastScanAt(report.generatedAt());
        r.setVersion(version);
        r.setSpecVersion(report.specVersion());
        r.setWindowFrom(report.logWindow() != null ? report.logWindow().from() : null);
        r.setWindowTo(report.logWindow() != null ? report.logWindow().to() : null);
        r.setReportJson(reportJson);

        DiscoveryReport.Summary s = report.summary();
        r.setDiscovered(s.discovered());
        r.setActive(s.active());
        r.setShadow(s.shadow());
        r.setZombie(s.zombie());
        r.setUnused(s.unused());
        // scan-status at-a-glance 비정규화 합계(사유별 상세는 /result 만, doc/25 §C). ETag 무영향.
        r.setTotalDropped(report.droppedNonApi().total() + report.droppedByLimit().total()
                + report.droppedNonExistent().notFound());
        return scanRepo.save(r);
    }

    /**
     * ETag 입력용 findings 투영 — Zombie severity 의 raw score 를 band(HIGH/MED/LOW)로 버킷화(doc/24 §5).
     * 지속 zombie 의 lastSeen 전진·hits 증가발 severity 미세 creep 이 매 스캔 ETag 를 bump 하는 것을 막고, band 전이 시만 bump.
     * 그 외 finding 은 그대로(Shadow confidence churn 은 별개·범위 밖).
     */
    private static List<Object> findingsEtagView(List<Finding> findings) {
        List<Object> out = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            // host=null(host-agnostic spec) 가능 → null 허용 Arrays.asList (List.of 는 null 거부)
            // params 는 정렬 명칭집합으로 축약 → count/lenBuckets 발 churn 제거(Shadow·Active·Zombie 균일, doc/25 §B.3)
            switch (f) {
                case Finding.Zombie z -> out.add(java.util.Arrays.asList("ZOMBIE", z.host(), z.method(),
                        z.pathTemplate(), z.confidence(), z.estimated(), z.severity().band(), z.reason(),
                        paramsKey(z.params())));
                case Finding.Shadow s -> out.add(java.util.Arrays.asList("SHADOW", s.host(), s.method(),
                        s.pathTemplate(), s.confidence(), s.reason(), paramsKey(s.params())));
                case Finding.Active a -> out.add(java.util.Arrays.asList("ACTIVE", a.host(), a.method(),
                        a.pathTemplate(), a.specRef(), paramsKey(a.params())));
                default -> out.add(f); // Unused, WebPage — params 없음
            }
        }
        return out;
    }

    /**
     * specSource ETag 투영 — content-stable 만(format/warnings/문서 name+format). documents 의 per-record
     * monotonic specVersion 은 제외 → 동일 콘텐츠 재업로드 무bump(합성 content 버전은 report.specVersion 이 별도 반영, P3-2).
     */
    private static Object specSourceEtagView(com.pentasecurity.apidiscover.model.SpecSource s) {
        List<Object> docs = s.documents().stream()
                .map(d -> (Object) java.util.Arrays.asList(d.specName(), d.format())) // specVersion(monotonic) 제외
                .toList();
        return java.util.Arrays.asList(s.format(), s.warnings(), docs);
    }

    /** params → [정렬 query 이름, 정렬 path 토큰] (count/buckets 제외 — 명칭집합만, doc/25 §B.3). */
    private static Object paramsKey(com.pentasecurity.apidiscover.model.ParamCandidates p) {
        List<String> q = p.query().stream()
                .map(com.pentasecurity.apidiscover.model.ParamCandidates.QueryParam::name).sorted().toList();
        List<String> path = p.path().stream()
                .map(com.pentasecurity.apidiscover.model.ParamCandidates.PathParam::token).sorted().toList();
        return java.util.Arrays.asList(q, path);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize report", e);
        }
    }

    /** 온디맨드/기본 윈도우: [now - interval - lag, now - lag). */
    public LogWindow defaultWindow() {
        Instant to = Instant.now().minus(props.schedule().ingestLag());
        Instant from = to.minus(props.schedule().defaultInterval());
        return new LogWindow(from, to);
    }

    /** watermark 기반 증분 윈도우 (doc/05 §3). 신규 구간 없으면 empty. per-scan 상한=maxWindow(A, off-peak 시 상향 주입). */
    Optional<LogWindow> nextWindow(String host, Duration maxWindow) {
        Instant lastEnd = watermarkRepo.findById(host).map(w -> w.getLastEnd()).orElse(null);
        return windowFor(Instant.now(), lastEnd,
                props.schedule().ingestLag(), props.schedule().initialBackfill(), maxWindow);
    }

    /**
     * 순수 함수(테스트용): now·lastEnd 로 윈도우 산정. lastEnd 없으면 backfill 시작점.
     * (end−start) &gt; maxWindow 면 end=start+maxWindow 로 절단(A, doc/33 §2) → 백필을 max-window 씩 여러 틱 점진.
     * maxWindow null/zero = 무제한(현행 무회귀).
     */
    static Optional<LogWindow> windowFor(Instant now, Instant lastEnd,
                                         Duration ingestLag, Duration backfill, Duration maxWindow) {
        Instant end = now.minus(ingestLag);
        Instant start = (lastEnd != null) ? lastEnd : end.minus(backfill);
        if (!start.isBefore(end)) {
            return Optional.empty(); // 신규 구간 없음
        }
        if (maxWindow != null && !maxWindow.isZero()) {
            Instant capped = start.plus(maxWindow);
            if (capped.isBefore(end)) {
                end = capped; // 백필 슬라이스 — watermark 가 max-window 씩 전진
            }
        }
        return Optional.of(new LogWindow(start, end));
    }

    private void advanceWatermark(String host, Instant end) {
        Watermark w = watermarkRepo.findById(host).orElseGet(Watermark::new);
        w.setHost(host);
        w.setLastEnd(end);
        watermarkRepo.save(w);
    }

    /** request_id 가 있을 때만 중복 제거. id 없으면(20필드 로그 등) 정상 보존. */
    static List<ParsedRequest> dedupByRequestId(List<ParsedRequest> requests) {
        Set<String> seen = new HashSet<>();
        List<ParsedRequest> out = new ArrayList<>(requests.size());
        for (ParsedRequest r : requests) {
            String id = r.requestId();
            if (id == null || seen.add(id)) {
                out.add(r);
            }
        }
        return out;
    }

    /** 도메인의 엣지 서버(hostname 라벨)별로 Loki 조회. 부하 보호는 LokiClient 내부(doc/05 §2.4). */
    private List<String> collect(DomainConfig cfg, LogWindow window) {
        List<String> lines = new ArrayList<>();
        if (cfg.getHostnames() == null || cfg.getHostnames().isEmpty()) {
            lines.addAll(lokiClient.queryRange(queryBuilder.build(cfg.getHost()), window));
        } else {
            for (String edge : cfg.getHostnames()) {
                lines.addAll(lokiClient.queryRange(queryBuilder.build(edge, cfg.getHost()), window));
            }
        }
        return lines;
    }

    /**
     * PR1.1(① doc/33 §14) — 슬라이스 외부·hostname 내부 순회. 윈도우를 slice-window(미지정=chunk-window)로 쪼개,
     * <b>각 슬라이스의 모든 hostname 을 완료한 뒤에만</b> consumedUpTo 를 그 슬라이스 끝으로 전진(멀티-hostname gap-free).
     * 슬라이스 경계에서 per-scan 캡(max-queries-per-scan)·전역 예산(budget.hasBudget) 체크 → 초과 시 마지막 완료 슬라이스까지만(부분 전진).
     * 무회귀: cap=0 && 예산 무제한 && slice≥window → 전 슬라이스 수집 = 기존 collect 동치(consumedUpTo=window.to()).
     */
    BoundedCollection collectBounded(DomainConfig cfg, LogWindow window) {
        List<String> edges = (cfg.getHostnames() == null || cfg.getHostnames().isEmpty())
                ? java.util.Collections.singletonList(null)   // null = hostname 라벨 없는 도메인 쿼리
                : cfg.getHostnames();
        Duration slice = sliceWindow();
        int cap = props.scan().maxQueriesPerScan();
        List<String> lines = new ArrayList<>();
        Instant consumedUpTo = window.from();
        Instant sliceStart = window.from();
        int queriesUsed = 0;
        while (sliceStart.isBefore(window.to())) {
            // 슬라이스 경계 하드캡(①)·전역 예산(④ 흡수) — 초과 시 여기까지(consumedUpTo)만 처리하고 종료(resume)
            if (!budget.hasBudget() || (cap > 0 && queriesUsed >= cap)) {
                break;
            }
            Instant sliceEnd = sliceStart.plus(slice);
            if (sliceEnd.isAfter(window.to())) {
                sliceEnd = window.to();
            }
            LogWindow sliceWin = new LogWindow(sliceStart, sliceEnd);
            for (String edge : edges) {
                String logql = (edge != null)
                        ? queryBuilder.build(edge, cfg.getHost()) : queryBuilder.build(cfg.getHost());
                lines.addAll(lokiClient.queryRange(logql, sliceWin));
                queriesUsed++;
            }
            consumedUpTo = sliceEnd; // 슬라이스의 모든 hostname 완료 → watermark 후보 전진(gap-free)
            sliceStart = sliceEnd;
        }
        return new BoundedCollection(lines, consumedUpTo);
    }

    /** ① 슬라이스 단위(scan.slice-window, 미지정/0 → loki.chunk-window 재사용). */
    private Duration sliceWindow() {
        Duration s = props.scan().sliceWindow();
        return (s != null && !s.isZero()) ? s : props.loki().chunkWindow();
    }
}
