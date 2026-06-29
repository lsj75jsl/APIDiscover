// 스캔 상태/결과 API — 결과는 조건부 GET (doc/07 §3.2, §3.3)
package com.pentasecurity.apidiscover.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.ScanStatusView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.SummaryView;
import com.pentasecurity.apidiscover.batch.CombinedDiscoveryService;
import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
import com.pentasecurity.apidiscover.batch.DomainRegistrar;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import com.pentasecurity.apidiscover.spec.SpecStore;
import com.pentasecurity.apidiscover.util.DomainNames;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/domains/{host}")
public class ScanController {

    private final ScanResultRepository scanRepo;
    private final DiscoveryJobService jobService;
    private final SpecStore specStore;
    private final CombinedDiscoveryService combinedDiscoveryService;
    private final DomainRegistrar registrar;
    private final ObjectMapper objectMapper;

    public ScanController(ScanResultRepository scanRepo, DiscoveryJobService jobService, SpecStore specStore,
                          CombinedDiscoveryService combinedDiscoveryService, DomainRegistrar registrar,
                          ObjectMapper objectMapper) {
        this.scanRepo = scanRepo;
        this.jobService = jobService;
        this.specStore = specStore;
        this.combinedDiscoveryService = combinedDiscoveryService;
        this.registrar = registrar;
        this.objectMapper = objectMapper;
    }

    /** 가벼운 상태 메타. 중앙이 version/lastScanAt 만 비교해 결과 재조회 여부 결정. latestSpec=최근 active 스펙(filename·API수, doc/35 M4). */
    @GetMapping("/scan-status")
    public ScanStatusView status(@PathVariable String host) {
        ScanResult r = find(host);
        SpecMetaView latestSpec = specStore.activeMeta(host).map(SpecMetaView::of).orElse(null);
        return new ScanStatusView(r.getHost(), r.getState(), r.getLastScanAt(), r.getVersion(),
                new SummaryView(r.getDiscovered(), r.getActive(), r.getShadow(), r.getZombie(), r.getUnused()),
                r.getTotalDropped(), latestSpec);
    }

    /**
     * 결과 — 조건부 GET. If-None-Match 가 현재 version 과 같으면 304(doc/07 §3.3).
     * <p>★200 응답엔 serve-time 판단근거 {@code rationale} 가산(doc/35 M5) — report_json 파싱→주입→재직렬화.
     * report_json <b>기존 필드 불변</b>(중앙 additive-safe)·ETag={@code r.version} 유지(rationale 는 ETag 입력 아님).
     * ★caveat: report_json findings=스캔시점 vs rationale=현재 재계산(현재 effective 기준) → 다를 수 있고, 304 캐시 body 의
     * rationale 는 갱신 안 됨(ETag=report 만 추적). 의도된 트레이드오프(doc/34·35 M5).
     */
    @GetMapping("/result")
    public ResponseEntity<String> result(
            @PathVariable String host,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ScanResult r = find(host);
        if (r.getReportJson() == null || r.getVersion() == null) {
            return ResponseEntity.noContent().build(); // 아직 완료된 스캔 없음
        }
        String etag = "\"" + r.getVersion() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .contentType(MediaType.APPLICATION_JSON)
                .body(withRationale(r.getReportJson(), host));
    }

    /** report_json 에 serve-time rationale 가산(/discovery 와 동일 메커니즘, doc/35 M5). 기존 필드 미터치(가산만). */
    private String withRationale(String reportJson, String host) {
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(reportJson);
            node.set("rationale", objectMapper.valueToTree(combinedDiscoveryService.forHost(host).rationale()));
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to inject rationale into report for host=" + host, e);
        }
    }

    /** 온디맨드 재검사 트리거(비동기 202) — 스케줄러 runScan 트리거. 즉시 결과가 필요하면 {@code /scan-now}(동기) 사용. */
    @PostMapping("/scan")
    public ResponseEntity<Void> triggerScan(@PathVariable String host) {
        jobService.runScan(host); // TODO: 비동기 큐잉으로 전환
        return ResponseEntity.accepted().build();
    }

    /**
     * 즉시 동기 스캔 (doc/35 A1) — 미등록 도메인이면 자동 등록 후 스캔하고 결합 결과(findings+rationale+effectiveClassification)를 반환.
     * 정규화(null→400) → {@link DomainRegistrar#registerIfAbsent}(enabled=true·discoveredAt=null, CLI {@code -scan} 자동등록과 일관)
     * → {@code onDemandWindow}(상한=scan.max-window) → {@code scanOnDemand}(★watermark 미전진, doc/33 §7) → {@code forHost}(/discovery 일관).
     * <p>★Loki 동기 호출 — 부하보호(slice·throttle·동시·백오프·LokiBudget)는 scanOnDemand 내부 준수, window 상한으로 폭주 차단.
     * busy 도메인은 응답 지연 가능 → {@code window} 를 작게, 비동기 트리거는 {@code POST /scan}(202). Loki 수집/분석 실패=502.
     */
    @PostMapping("/scan-now")
    public CombinedDiscovery scanNow(@PathVariable String host,
                                     @RequestParam(required = false) Duration window) {
        String normalized = DomainNames.normalize(host);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "host is required");
        }
        registrar.registerIfAbsent(normalized); // 미등록 자동등록(실패=uncaught→500)
        try {
            LogWindow w = jobService.onDemandWindow(window); // 기본/상한=scan.max-window
            jobService.scanOnDemand(normalized, w, null);    // watermark 미전진(온디맨드 스냅샷)
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "on-demand scan failed: " + e.getMessage(), e);
        }
        return combinedDiscoveryService.forHost(normalized);
    }

    private ScanResult find(String host) {
        return scanRepo.findById(host)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
