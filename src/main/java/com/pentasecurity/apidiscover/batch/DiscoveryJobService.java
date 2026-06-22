// 도메인 1회 스캔 파이프라인 오케스트레이션 (doc/01 파이프라인)
package com.pentasecurity.apidiscover.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.classify.Classifier;
import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.domain.Watermark;
import com.pentasecurity.apidiscover.domain.WatermarkRepository;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.ingest.LokiClient;
import com.pentasecurity.apidiscover.ingest.LokiQueryBuilder;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveryReport;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.normalize.InventoryBuilder;
import com.pentasecurity.apidiscover.parse.LogLineParser;
import com.pentasecurity.apidiscover.report.EtagUtil;
import com.pentasecurity.apidiscover.report.ReportBuilder;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private final Classifier classifier;
    private final ReportBuilder reportBuilder;
    private final ScanResultRepository scanRepo;
    private final DomainConfigRepository domainRepo;
    private final WatermarkRepository watermarkRepo;
    private final LokiClient lokiClient;
    private final LokiQueryBuilder queryBuilder;
    private final ObjectMapper objectMapper;
    private final ApiDiscoverProperties props;

    public DiscoveryJobService(LogLineParser parser,
                               InventoryBuilder inventoryBuilder,
                               SpecStore specStore,
                               Classifier classifier,
                               ReportBuilder reportBuilder,
                               ScanResultRepository scanRepo,
                               DomainConfigRepository domainRepo,
                               WatermarkRepository watermarkRepo,
                               LokiClient lokiClient,
                               LokiQueryBuilder queryBuilder,
                               ObjectMapper objectMapper,
                               ApiDiscoverProperties props) {
        this.parser = parser;
        this.inventoryBuilder = inventoryBuilder;
        this.specStore = specStore;
        this.classifier = classifier;
        this.reportBuilder = reportBuilder;
        this.scanRepo = scanRepo;
        this.domainRepo = domainRepo;
        this.watermarkRepo = watermarkRepo;
        this.lokiClient = lokiClient;
        this.queryBuilder = queryBuilder;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * 한 도메인 스캔 실행: Loki 수집(S0) → 분석. 윈도우 산정은 watermark 기반(TODO).
     */
    public void runScan(String host) {
        DomainConfig cfg = domainRepo.findById(host).orElse(null);
        if (cfg == null || !cfg.enabled) {
            log.info("skip scan: host={} not found or disabled", host);
            return;
        }
        Optional<LogWindow> next = nextWindow(host);
        if (next.isEmpty()) {
            log.info("skip scan: host={} no new window (watermark up to date)", host);
            return;
        }
        LogWindow window = next.get();
        List<String> lines = collect(cfg, window);
        analyze(host, lines, window);
        advanceWatermark(host, window.to()); // 성공 후에만 전진 (at-least-once)
        log.info("scan complete: host={} window={}", host, window);
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
        long specVersion = active.map(r -> r.specVersion).orElse(0L);
        EndpointMatcher matcher = new EndpointMatcher(spec);

        // (B) 인벤토리 → (E) 분류 → (F) 리포트
        List<DiscoveredEndpoint> discovered = inventoryBuilder.build(requests, matcher);
        List<Finding> findings = classifier.classify(discovered, spec, matcher);
        // OPTIONS 는 CORS 신호로만 쓰고 보고에서 제외되므로 인벤토리 카운트에서도 뺀다 (과대집계 방지)
        long reportedCount = discovered.stream()
                .filter(d -> !"OPTIONS".equalsIgnoreCase(d.method()))
                .count();
        DiscoveryReport report =
                reportBuilder.build(host, specVersion, window, (int) reportedCount, findings);

        return persist(host, report);
    }

    private ScanResult persist(String host, DiscoveryReport report) {
        String reportJson = toJson(report);
        // version(ETag)은 generatedAt/window 를 제외한 '내용'으로 산정 → 동일 결과는 동일 버전 (doc/07 §8)
        String version = EtagUtil.of(toJson(
                List.of(report.specVersion(), report.summary(), report.findings())));

        ScanResult r = scanRepo.findById(host).orElseGet(ScanResult::new);
        r.host = host;
        r.state = "idle";
        r.lastScanAt = report.generatedAt();
        r.version = version;
        r.specVersion = report.specVersion();
        r.windowFrom = report.logWindow() != null ? report.logWindow().from() : null;
        r.windowTo = report.logWindow() != null ? report.logWindow().to() : null;
        r.reportJson = reportJson;

        DiscoveryReport.Summary s = report.summary();
        r.discovered = s.discovered();
        r.active = s.active();
        r.shadow = s.shadow();
        r.zombie = s.zombie();
        r.unused = s.unused();
        return scanRepo.save(r);
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

    /** watermark 기반 증분 윈도우 (doc/05 §3). 신규 구간 없으면 empty. */
    Optional<LogWindow> nextWindow(String host) {
        Instant lastEnd = watermarkRepo.findById(host).map(w -> w.lastEnd).orElse(null);
        return windowFor(Instant.now(), lastEnd,
                props.schedule().ingestLag(), props.schedule().initialBackfill());
    }

    /** 순수 함수(테스트용): now·lastEnd 로 윈도우 산정. lastEnd 없으면 backfill. */
    static Optional<LogWindow> windowFor(Instant now, Instant lastEnd,
                                         Duration ingestLag, Duration backfill) {
        Instant end = now.minus(ingestLag);
        Instant start = (lastEnd != null) ? lastEnd : end.minus(backfill);
        if (!start.isBefore(end)) {
            return Optional.empty(); // 신규 구간 없음
        }
        return Optional.of(new LogWindow(start, end));
    }

    private void advanceWatermark(String host, Instant end) {
        Watermark w = watermarkRepo.findById(host).orElseGet(Watermark::new);
        w.host = host;
        w.lastEnd = end;
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
        if (cfg.hostnames == null || cfg.hostnames.isEmpty()) {
            lines.addAll(lokiClient.queryRange(queryBuilder.build(cfg.host), window));
        } else {
            for (String edge : cfg.hostnames) {
                lines.addAll(lokiClient.queryRange(queryBuilder.build(edge, cfg.host), window));
            }
        }
        return lines;
    }
}
