// 도메인 1회 스캔 파이프라인 오케스트레이션 (doc/01 파이프라인)
package com.pentasecurity.apidiscover.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.classify.ApiScorer;
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
import com.pentasecurity.apidiscover.model.EndpointIdentity;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.SpecSource;
import com.pentasecurity.apidiscover.model.VersionTag;
import com.pentasecurity.apidiscover.normalize.InventoryBuilder;
import com.pentasecurity.apidiscover.parse.LogLineParser;
import com.pentasecurity.apidiscover.report.EtagUtil;
import com.pentasecurity.apidiscover.report.ReportBuilder;
import com.pentasecurity.apidiscover.spec.ApiInventoryService;
import com.pentasecurity.apidiscover.spec.SpecStore;
import com.pentasecurity.apidiscover.util.DomainNames;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final ApiInventoryService apiInventoryService;
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

    /** D62·D69: 대상 제외 엣지(정확 일치 + 'X*' 접두) — 스캔 조회에서 이 엣지 매핑을 뺀다(디스커버리 필터와 동일 목록). */
    private final EdgeExclusions excludedEdges;
    /** D65: 엣지 그룹 Master 해석기 — edge-group-main-only 시 조회 엣지를 Master 로 치환. */
    private final EdgeGroupResolver edgeGroups;

    public DiscoveryJobService(LogLineParser parser,
                               InventoryBuilder inventoryBuilder,
                               SpecStore specStore,
                               ApiInventoryService apiInventoryService,
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
                               ApiDiscoverProperties props,
                               EdgeGroupResolver edgeGroups) {
        this.parser = parser;
        this.inventoryBuilder = inventoryBuilder;
        this.specStore = specStore;
        this.apiInventoryService = apiInventoryService;
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
        this.excludedEdges = new EdgeExclusions(props.discovery().excludedHostnames());
        this.edgeGroups = edgeGroups;
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
        // D62: 엣지가 있었는데 전부 제외 대상이면 스캔 자체를 skip(★hostname-less 폴백 금지 — 전 스트림 조회 방지).
        List<String> edges = effectiveEdges(cfg);
        if (edges != null && edges.isEmpty()) {
            log.info("skip scan: host={} all edges excluded (D62)", host);
            return;
        }
        Optional<LogWindow> next = nextWindow(host, maxWindow);
        if (next.isEmpty()) {
            log.info("skip scan: host={} no new window (watermark up to date)", host);
            return;
        }
        LogWindow window = next.get();
        if (skipIfNoNewTraffic(cfg, window)) {
            return;
        }
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

    /**
     * D60 delta-driven skip: discovery(10분마다 Loki 실시간 집계)의 마지막 관측(lastSeenAt)이 윈도우 시작 이전이면
     * = 구간에 신규 트래픽 없음 → Loki 조회 없이 워터마크만 전진(빈 윈도우 쿼리 낭비 제거). D59 와 동일 discovery 신호 신뢰.
     * ★D61: lastSeen < window.from 은 [window.from, now−lag] 전체 무트래픽을 보장 → maxWindow 상한 없이 now−lag 로
     * 즉시 전진(빈 도메인 1 touch caught-up). ★scan-now(온디맨드)는 미경유. lastSeenAt null(방어)=skip 안 함.
     * ★한계(정직): discovery 가 놓친 트래픽은 skip 될 수 있음(정상 가동 전제) — inventory/shadow 용도엔 허용.
     *
     * @return true = skip 처리됨(워터마크 전진 완료)
     */
    private boolean skipIfNoNewTraffic(DomainConfig cfg, LogWindow window) {
        if (cfg.getLastSeenAt() == null || !cfg.getLastSeenAt().isBefore(window.from())) {
            return false;
        }
        Instant caughtUp = Instant.now().minus(props.schedule().ingestLag()); // now−lag(윈도우 실제 상한, maxWindow 미적용)
        if (caughtUp.isBefore(window.to())) {
            caughtUp = window.to(); // 방어: 최소 window.to (clock skew)
        }
        advanceWatermark(cfg.getHost(), caughtUp);
        log.info("skip scan: host={} no new traffic per discovery (lastSeen={} < window.from={}) → watermark jumped to {}",
                cfg.getHost(), cfg.getLastSeenAt(), window.from(), caughtUp);
        return true;
    }

    /** 슬라이스 부분 수집 결과: 누적 라인 + 모든 hostname 완료된 마지막 슬라이스 끝(watermark 전진 한계). 테스트 가시(package-private). */
    record BoundedCollection(List<String> lines, Instant consumedUpTo) {}

    // ── D63 엣지-그룹 배칭 ────────────────────────────────────────────────────────

    /** 배칭 워터마크 버킷 폭. ponytail: 상수 10분 — maxWindow(30m)보다 충분히 작아 재읽기 상한·전진 보장. 필요 시 설정 승격. */
    private static final long BATCH_BUCKET_SECONDS = 600L;

    /** 배칭 실조회 작업 1건: 도메인 + 자기 윈도우 + 유효 엣지. */
    private record BatchJob(DomainConfig cfg, LogWindow window, List<String> edges, String normHost) {}

    /**
     * D63: 틱의 도메인들을 배칭 스캔. 도메인별 게이트(disabled/D62 전부제외/무윈도우/D60·D61 delta-skip)는 개별 처리하고,
     * 남은 <b>실조회</b> 도메인을 (워터마크 10분 버킷 × 엣지)로 묶어 {@code |~ `(d1|d2|…)`} 1쿼리로 조회한다
     * — Loki 라인필터는 비인덱스(청크 전체 스캔)라 같은 엣지 청크를 도메인별로 N번 재읽던 것이 1번이 된다.
     * <p>그룹 윈도우 = [버킷 min from, min(min from+maxWindow, now−lag)). 자기 from 이 그룹 from 보다 늦은 도메인은
     * 최대 버킷폭(10분)만큼 재읽기(멱등: firstSeen min/lastSeen max/request_id dedup). 워터마크는 그룹 to 로 전진(버킷폭<maxWindow
     * 이므로 항상 자기 from 이후 = 후퇴 없음, 방어 가드 포함). ★gap-free: 도메인의 <b>모든</b> 엣지 쿼리가 성공한 경우에만
     * analyze+전진(부분 실패 = 미전진, 다음 due 재시도 — collectBounded 와 동일 규칙). 예산 소진 시 잔여 그룹 이월.
     * <p>hostname-less 레거시 도메인(엣지 없음)은 배칭 불가 → 기존 per-domain 경로(runScan)로 위임.
     */
    public void runScanBatched(List<String> hosts, Duration maxWindow) {
        int batchSize = Math.max(1, props.scan().queryBatchSize());
        List<BatchJob> jobs = new ArrayList<>();
        for (String host : hosts) {
            DomainConfig cfg = domainRepo.findById(host).orElse(null);
            if (cfg == null || !cfg.isEnabled()) {
                log.info("skip scan: host={} not found or disabled", host);
                continue;
            }
            List<String> edges = effectiveEdges(cfg);
            if (edges != null && edges.isEmpty()) {
                log.info("skip scan: host={} all edges excluded (D62)", host);
                continue;
            }
            if (edges == null) {
                runScan(host, maxWindow); // 레거시(hostname-less): 배칭 불가 → 기존 경로
                continue;
            }
            Optional<LogWindow> next = nextWindow(host, maxWindow);
            if (next.isEmpty()) {
                log.info("skip scan: host={} no new window (watermark up to date)", host);
                continue;
            }
            LogWindow window = next.get();
            if (skipIfNoNewTraffic(cfg, window)) {
                continue;
            }
            String norm = DomainNames.normalize(host);
            if (norm == null) {
                continue; // 방어: 비정규화 host 는 라인 분배 불가
            }
            jobs.add(new BatchJob(cfg, window, edges, norm));
        }
        if (jobs.isEmpty()) {
            return;
        }

        // 버킷(윈도우 from 10분 절사) → 그 안에서 엣지별 sub-batch
        Map<Long, List<BatchJob>> byBucket = new HashMap<>();
        for (BatchJob j : jobs) {
            byBucket.computeIfAbsent(j.window().from().getEpochSecond() / BATCH_BUCKET_SECONDS,
                    k -> new ArrayList<>()).add(j);
        }
        Instant lagEnd = Instant.now().minus(props.schedule().ingestLag());
        int batchedQueries = 0;
        int scanned = 0;
        int deferred = 0;
        for (List<BatchJob> bucket : byBucket.values()) {
            Instant groupFrom = bucket.stream().map(j -> j.window().from()).min(Instant::compareTo).orElseThrow();
            Instant groupTo = groupFrom.plus(maxWindow);
            if (groupTo.isAfter(lagEnd)) {
                groupTo = lagEnd;
            }
            if (!groupTo.isAfter(groupFrom)) {
                continue; // 방어: 유효 구간 없음(다음 틱)
            }
            LogWindow groupWindow = new LogWindow(groupFrom, groupTo);

            // 엣지 → 그 엣지를 가진 job 들(멀티엣지 job 은 여러 엣지에 등장)
            Map<String, List<BatchJob>> byEdge = new LinkedHashMap<>();
            for (BatchJob j : bucket) {
                for (String e : j.edges()) {
                    byEdge.computeIfAbsent(e, k -> new ArrayList<>()).add(j);
                }
            }
            Map<String, List<String>> linesByHost = new HashMap<>();
            Map<String, Integer> okEdgesByHost = new HashMap<>();
            Set<String> failedHosts = new HashSet<>();
            for (Map.Entry<String, List<BatchJob>> e : byEdge.entrySet()) {
                List<BatchJob> onEdge = e.getValue();
                for (int i = 0; i < onEdge.size(); i += batchSize) {
                    List<BatchJob> chunk = onEdge.subList(i, Math.min(i + batchSize, onEdge.size()));
                    if (!budget.hasBudget()) {
                        chunk.forEach(j -> failedHosts.add(j.normHost())); // 예산 소진 → 이월(미전진)
                        deferred += chunk.size();
                        continue;
                    }
                    List<String> domains = chunk.stream().map(j -> j.cfg().getHost()).toList();
                    try {
                        List<String> lines = lokiClient.queryRange(
                                queryBuilder.buildBatch(e.getKey(), domains), groupWindow);
                        batchedQueries++;
                        splitByHost(lines, chunk, linesByHost);
                        chunk.forEach(j -> okEdgesByHost.merge(j.normHost(), 1, Integer::sum));
                    } catch (RuntimeException ex) {
                        chunk.forEach(j -> failedHosts.add(j.normHost())); // 부분 실패 = 그 도메인 미전진(gap-free)
                        log.warn("batched scan failed for edge={} domains={}", e.getKey(), domains.size(), ex);
                    }
                }
            }
            for (BatchJob j : bucket) {
                if (failedHosts.contains(j.normHost())
                        || okEdgesByHost.getOrDefault(j.normHost(), 0) < j.edges().size()) {
                    continue; // 전 엣지 성공 못함 → 미전진(다음 due 재시도)
                }
                try {
                    analyze(j.cfg().getHost(),
                            linesByHost.getOrDefault(j.normHost(), List.of()),
                            new LogWindow(j.window().from(), groupTo));
                    advanceWatermark(j.cfg().getHost(), groupTo);
                    scanned++;
                } catch (RuntimeException ex) {
                    log.warn("batched analyze failed for host={}", j.cfg().getHost(), ex); // 도메인 격리
                }
            }
        }
        log.info("batched scan tick: jobs={} queries={} scanned={} deferred={} (batchSize={})",
                jobs.size(), batchedQueries, scanned, deferred, batchSize);
    }

    /** 배치 응답 라인을 도메인별로 분배 — host 필드 파싱·정규화 후 배치 도메인과 정확 일치만(라인필터 과탐 차단). */
    private void splitByHost(List<String> lines, List<BatchJob> chunk, Map<String, List<String>> sink) {
        Set<String> wanted = new HashSet<>();
        for (BatchJob j : chunk) {
            wanted.add(j.normHost());
        }
        for (String line : lines) {
            parser.parse(line).ifPresent(r -> {
                String h = DomainNames.normalize(r.host());
                if (h != null && wanted.contains(h)) {
                    sink.computeIfAbsent(h, k -> new ArrayList<>()).add(line);
                }
            });
        }
    }

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
        // foreign-host 제외 (doc/05 §2.2): LokiQueryBuilder 의 |= domain 은 substring coarse 전치필터라 referer/URL/UA 에
        // 도메인 든 다른 Host 라인도 매칭됨 → 파싱 후 스캔도메인(정규화 동치)만 남겨 인벤토리·discovered·recency 오염 차단.
        // ★양변 정규화(자기완결, 불변식 미의존): 좌변 스캔 host 도 normalize → 비정규화 등록분(레거시)도 오필터 방지. null host 자동 제외.
        String scanDomain = DomainNames.normalize(host);
        requests = requests.stream()
                .filter(r -> scanDomain != null && scanDomain.equals(DomainNames.normalize(r.host())))
                .toList();

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
        // DELETED→Zombie 결합 입력(doc/37 §6) — host 의 DELETED 인벤토리 키 1쿼리. 비면 무영향(무회귀).
        Set<String> deletedKeys = apiInventoryService.deletedKeys(host);
        // findings + non_api dropped 메트릭 동시 산출 (doc/12 §1, doc/24·26·27, doc/37 §6)
        ClassificationResult classified = classifier.classifyWithMetrics(
                discovered, spec, matcher, eff.scorer(), eff.hints(), priorFirstSeen, stripPrefix, host, deletedKeys);
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
        // ★실 access log 최신 시각(time_iso8601) 갱신(D56) — 관측 로그의 최신 시각으로 last_access_log_at 전진(never decrease).
        // 무접속 자동스캔 제외(ScanSelector) 기준. 관측 로그 없으면(빈 스캔) 미갱신(dead 도메인은 값이 오래된 채 유지 → 제외됨).
        Instant maxLogTime = null;
        for (DiscoveredEndpoint d : discovered) {
            Instant ls = (d.metrics() != null) ? d.metrics().lastSeen() : null;
            if (ls != null && (maxLogTime == null || ls.isAfter(maxLogTime))) {
                maxLogTime = ls;
            }
        }
        if (maxLogTime != null) {
            domainRepo.touchLastAccessLogAt(host, maxLogTime);
        }
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
     * <p>★dedup 키 = DB unique(host,method,path_template) 제약 튜플({@code identityKey}). {@code prior} 적재 키(loadDiscovered
     * 의 {@code signatureOf(rec)})와 정확히 동일 → 기존 행 항상 매칭(UPDATE)·배치 내 동일 튜플 병합 → 신규 INSERT 충돌 불가.
     * {@code DiscoveredEndpoint.signature} 는 최종 template 과 발산할 수 있어(특히 "/" 정규화/방출 불일치, 실배포 발견) 키로 쓰지 않는다(PR #31 정정).
     * <p>visibility: 실 PG 회귀 테스트(PostgresIntegrationTest, 별 패키지)가 직접 호출 — 그 외엔 analyze 내부 전용.
     */
    public void upsertDiscovered(String host, Map<String, DiscoveredEndpointRecord> prior,
                                 List<DiscoveredEndpoint> discovered, EndpointMatcher matcher, LogWindow window) {
        Instant scanEnd = (window != null) ? window.to() : null;
        long count = prior.size();
        int dropped = 0;
        int oversize = 0;
        int saveFailed = 0;
        for (DiscoveredEndpoint d : discovered) {
            // ★D68 길이 하드가드: unique(host,method,path_template) btree 인덱스 행 한계(압축 후 ~2.7KB) 초과 경로는
            //   INSERT 자체가 불가(SQLSTATE 54000, 실관측=SQLi 페이로드 43KB) — persist 전 차단. 분류 게이트(DROP_OVERSIZE)와 동일 임계.
            if (d.pathTemplate() != null && d.pathTemplate().length() > ApiScorer.MAX_PATH_TEMPLATE_CHARS) {
                oversize++;
                continue;
            }
            // dedup 키 = 영속 identity 와 동일(host=파라미터, prior 적재 키도 동일) — d.host() 발산해도 기존 행/배치 중복 매칭.
            // ★host 는 d.host() 아닌 host 파라미터: 신규 rec 가 setHost(host)로 영속되므로 키도 host 여야 prior·DB 와 일치(reviewer 발견).
            String key = identityKey(d.method(), host, d.pathTemplate());
            DiscoveredEndpointRecord rec = prior.get(key);
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
                prior.put(key, rec); // 배치 내 동일 튜플 후속분이 새 INSERT 대신 이 rec UPDATE 로 병합(unique 위반 방지)
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
            try {
                discoveredRepo.save(rec);
            } catch (RuntimeException ex) {
                // ★D68 엔드포인트 격리(C): 한 행의 저장 실패가 같은 도메인 나머지 행 저장을 막지 않게 한다
                //   (배치 틱 경로는 save 별 개별 tx 라 격리 유효. 프록시 경유 외부 tx 활성 시엔 rollback-only 로 후속도 실패하나 로그는 남음).
                saveFailed++;
                String pt = d.pathTemplate();
                log.warn("discovered_endpoint save failed host={} method={} pathLen={} pathHead={} : {}",
                        host, d.method(), (pt != null) ? pt.length() : -1,
                        (pt != null) ? pt.substring(0, Math.min(120, pt.length())) : null, ex.getMessage());
            }
        }
        if (dropped > 0) {
            log.warn("discovered_endpoint cap {} reached for host={} — {} new identities dropped",
                    TEMPLATE_CAP, host, dropped);
        }
        if (oversize > 0) {
            log.warn("oversize path_template dropped for host={} — {} identities (>{} chars, D68)",
                    host, oversize, ApiScorer.MAX_PATH_TEMPLATE_CHARS);
        }
        if (saveFailed > 0) {
            log.warn("discovered_endpoint save failures isolated for host={} — {} rows skipped", host, saveFailed);
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

    /** DB unique(host,method,path_template) 제약과 동일한 dedup 키 — loadDiscovered 적재·upsert lookup·recency 공통(EndpointIdentity 단일 진실원). */
    private static String identityKey(String method, String host, String pathTemplate) {
        return EndpointIdentity.key(method, host, pathTemplate);
    }

    /** 검출 record → 제약 튜플 키 "{METHOD} {host} {template}". priorFirstSeen 키로도 쓰임(classifier 의 signature 발산은 별도 후속, doc/26 §2). */
    private static String signatureOf(DiscoveredEndpointRecord rec) {
        return identityKey(rec.getMethod(), rec.getHost(), rec.getPathTemplate());
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

    /** watermark 기반 증분 윈도우 (doc/05 §3). 신규 구간 없으면 empty. per-scan 상한=maxWindow(A, off-peak 시 상향 주입). */
    Optional<LogWindow> nextWindow(String host, Duration maxWindow) {
        Instant lastEnd = watermarkRepo.findById(host).map(w -> w.getLastEnd()).orElse(null);
        Duration sample = props.scan().sampleWindow();
        if (sample != null && !sample.isZero()) {
            return sampleWindowFor(Instant.now(), lastEnd, props.schedule().ingestLag(), sample);
        }
        return windowFor(Instant.now(), lastEnd,
                props.schedule().ingestLag(), props.schedule().initialBackfill(), maxWindow);
    }

    /**
     * D66 롤링 샘플링 윈도우(순수 함수): {@code [max(lastEnd, end−sample), end)}, end=now−lag.
     * 항상 "최신 sample 구간"만 조사 — 과거 백로그(워터마크~시작 사이)는 <b>의도적으로 건너뜀</b>(표본화).
     * 재방문이 sample 보다 빠르면 겹침 없이 [lastEnd, end) 로 축소. 워터마크 최신이면 empty.
     * ★신규 도메인도 최신 구간만(initial-backfill 미적용 — 샘플링 철학 일관, 과거 1008청크 백필 방지).
     * 스캔 후 워터마크는 end(=now−lag) 로 전진 → 신선도 = 재방문 주기(active PT30M = 시간당 2회 표본).
     */
    static Optional<LogWindow> sampleWindowFor(Instant now, Instant lastEnd, Duration ingestLag, Duration sample) {
        Instant end = now.minus(ingestLag);
        Instant start = end.minus(sample);
        if (lastEnd != null && lastEnd.isAfter(start)) {
            start = lastEnd;
        }
        if (!start.isBefore(end)) {
            return Optional.empty();
        }
        return Optional.of(new LogWindow(start, end));
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

    /**
     * D62: 제외 엣지를 뺀 유효 엣지 목록. null=원래 엣지 없음(레거시 hostname-less 폴백 유지),
     * empty=엣지가 있었는데 전부 제외(호출측 skip — 폴백 금지). 그 외=필터된 목록.
     */
    private List<String> effectiveEdges(DomainConfig cfg) {
        if (cfg.getHostnames() == null || cfg.getHostnames().isEmpty()) {
            return null;
        }
        var stream = cfg.getHostnames().stream().filter(h -> !excludedEdges.contains(h));
        if (props.scan().edgeGroupMainOnly()) {
            // D65: 엣지→그룹 Master 치환(중복 그룹은 distinct 로 1회 조회). replica 에만 매핑된 도메인도 Master 조회로 커버.
            stream = stream.map(edgeGroups::masterOf).distinct();
        }
        return stream.toList();
    }

    /** 도메인의 엣지 서버(hostname 라벨)별로 Loki 조회. 부하 보호는 LokiClient 내부(doc/05 §2.4). D62 제외 엣지 미조회. */
    private List<String> collect(DomainConfig cfg, LogWindow window) {
        List<String> lines = new ArrayList<>();
        List<String> edges = effectiveEdges(cfg);
        if (edges == null) {
            lines.addAll(lokiClient.queryRange(queryBuilder.build(cfg.getHost()), window));
        } else {
            for (String edge : edges) { // 전부 제외(empty)면 조회 0 = 빈 결과(온디맨드 포함 정책 일관)
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
        List<String> effective = effectiveEdges(cfg); // D62 제외 엣지 필터(empty 는 runScan 이 선차단)
        List<String> edges = (effective == null)
                ? java.util.Collections.singletonList(null)   // null = hostname 라벨 없는 도메인 쿼리(레거시 폴백)
                : effective;
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
