// 스캔 상태/결과 API — 결과는 조건부 GET (doc/07 §3.2, §3.3)
package com.pentasecurity.apidiscover.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.ApiLists;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.ScanStatusView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.SummaryView;
import com.pentasecurity.apidiscover.batch.CombinedDiscoveryService;
import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
import com.pentasecurity.apidiscover.batch.DomainRegistrar;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.model.Classification;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import com.pentasecurity.apidiscover.model.EndpointIdentity;
import com.pentasecurity.apidiscover.model.EndpointRationale;
import com.pentasecurity.apidiscover.spec.SpecStore;
import com.pentasecurity.apidiscover.util.DomainNames;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final DomainConfigRepository domainRepo;
    private final ObjectMapper objectMapper;

    public ScanController(ScanResultRepository scanRepo, DiscoveryJobService jobService, SpecStore specStore,
                          CombinedDiscoveryService combinedDiscoveryService, DomainRegistrar registrar,
                          DomainConfigRepository domainRepo, ObjectMapper objectMapper) {
        this.scanRepo = scanRepo;
        this.jobService = jobService;
        this.specStore = specStore;
        this.combinedDiscoveryService = combinedDiscoveryService;
        this.registrar = registrar;
        this.domainRepo = domainRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * 가벼운 상태 메타. 중앙이 version/lastScanAt 만 비교해 결과 재조회 여부 결정. latestSpec=최근 active 스펙(filename·API수, doc/35 M4).
     * <p>{@code apis}=유형별 API 목록(사용자 요청) — per-scan report_json finding 을 serve-time 판단근거(forHost)로 분류.
     * summary 와 동일 per-scan 집합이라 개수 일관. report_json 없으면(미스캔) 전부 빈 목록·forHost 미호출(경량 유지).
     */
    @GetMapping("/scan-result")
    public ScanStatusView status(@PathVariable String host) {
        ScanResult r = find(host);
        SpecMetaView latestSpec = specStore.latestSpecMeta(host).map(SpecMetaView::of).orElse(null); // projection(대용량 text 미로드, doc/28)
        return new ScanStatusView(r.getHost(), r.getState(), r.getLastScanAt(), r.getVersion(),
                new SummaryView(r.getDiscovered(), r.getActive(), r.getShadow(), r.getZombie(), r.getUnused()),
                r.getTotalDropped(), latestSpec, buildApiLists(r, host));
    }

    /**
     * scan-status 유형별 API 목록(사용자 요청) — per-scan report_json finding 을 {@code forHost} 판단근거로 분류.
     * {@code discovered}=전체 finding, 나머지=classification 별. summary 카운트와 동일 집합(per-scan). 각 원소 {@code "GET [https://host/path]"}.
     * report_json 없으면 전부 빈 목록(forHost 미호출). classification 은 report_json 에 미저장(타입 소거)이라 /result 와 동일하게 forHost 매칭으로 산출.
     */
    private ApiLists buildApiLists(ScanResult r, String host) {
        if (r.getReportJson() == null) {
            return new ApiLists(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<String> discovered = new ArrayList<>();
        Map<Classification, List<String>> byClass = new EnumMap<>(Classification.class);
        try {
            Map<String, EndpointRationale> byKey = rationaleByKey(host);
            if (objectMapper.readTree(r.getReportJson()).get("findings") instanceof ArrayNode findings) {
                for (JsonNode fn : findings) {
                    if (!(fn instanceof ObjectNode f)) {
                        continue;
                    }
                    String method = asText(f, "method");
                    String h = asText(f, "host");
                    String path = asText(f, "pathTemplate");
                    String label = ApiLists.label(method, h, path); // scheme 미저장 → https 고정
                    discovered.add(label);
                    EndpointRationale er = byKey.get(EndpointIdentity.key(method, h, path));
                    if (er != null) {
                        byClass.computeIfAbsent(er.classification(), k -> new ArrayList<>()).add(label);
                    }
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to build api lists for host=" + host, e);
        }
        return new ApiLists(discovered,
                byClass.getOrDefault(Classification.ACTIVE, List.of()),
                byClass.getOrDefault(Classification.SHADOW, List.of()),
                byClass.getOrDefault(Classification.ZOMBIE, List.of()),
                byClass.getOrDefault(Classification.UNUSED, List.of()));
    }

    /** forHost 판단근거를 {@link EndpointIdentity#key}(method,host,pathTemplate) → rationale 로 인덱싱. /result·scan-status 공용. */
    private Map<String, EndpointRationale> rationaleByKey(String host) {
        Map<String, EndpointRationale> byKey = new HashMap<>();
        for (EndpointRationale er : combinedDiscoveryService.forHost(host).rationale()) {
            byKey.put(EndpointIdentity.key(er.method(), er.host(), er.pathTemplate()), er);
        }
        return byKey;
    }

    /**
     * 결과 — 조건부 GET. If-None-Match 가 현재 version 과 같으면 304(doc/07 §3.3).
     * <p>★200 응답엔 serve-time 판단근거를 <b>각 finding 에 인라인</b>(doc/35 M5, ⓒ) — finding 마다 {@code classification}·
     * {@code basis}(SHADOW=점수게이트 apiScore/threshold/signals, Active/Zombie=spec_match) 가산. 별도 top-level
     * {@code rationale[]} 는 두지 않는다(인라인 대체). report_json <b>기존 필드 불변</b>·ETag={@code r.version} 유지(basis 는 ETag 입력 아님).
     * ★caveat: report_json findings=스캔시점 vs basis=현재 재계산(현재 effective 기준) → 다를 수 있고, 매칭 없는 finding 은 미가산.
     * 304 캐시 body 의 basis 는 갱신 안 됨(ETag=report 만 추적). 의도된 트레이드오프(doc/34·35 M5).
     */
    @GetMapping("/scan-result/detail")
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
                .body(inlineBasis(r.getReportJson(), host));
    }

    /**
     * report_json 의 각 finding 에 serve-time 판단근거(classification·basis)를 인라인(doc/35 M5, ⓒ).
     * forHost rationale 를 {@link EndpointIdentity#key} 로 인덱싱 → 같은 (method,host,pathTemplate) finding 에만 가산.
     * 기존 finding 필드 미터치(가산만), 매칭 없으면 미가산. 별도 top-level {@code rationale[]} 는 두지 않는다.
     */
    private String inlineBasis(String reportJson, String host) {
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(reportJson);
            Map<String, EndpointRationale> byKey = rationaleByKey(host);
            if (node.get("findings") instanceof ArrayNode findings) {
                for (JsonNode fn : findings) {
                    if (!(fn instanceof ObjectNode f)) {
                        continue;
                    }
                    // reason 을 classification 뒤로 재배치(사용자 요청 순서) — 먼저 떼어내 뒤에서 재부착
                    JsonNode reason = f.remove("reason");
                    EndpointRationale er = byKey.get(EndpointIdentity.key(
                            asText(f, "method"), asText(f, "host"), asText(f, "pathTemplate")));
                    if (er != null) {
                        f.put("classification", er.classification().name());
                        if (reason != null) {
                            f.set("reason", reason); // classification 직후
                            reason = null;
                        }
                        f.set("basis", objectMapper.valueToTree(er.basis()));
                    }
                    if (reason != null) {
                        f.set("reason", reason); // 미매칭 finding: reason 을 끝으로
                    }
                }
            }
            return objectMapper.writeValueAsString(ArrayCountJson.wrap(node)); // 모든 배열 {count,items} (사용자 요청, String 경로는 어드바이스 우회)
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to inline basis into report for host=" + host, e);
        }
    }

    /** finding ObjectNode 의 문자열 필드 추출(부재/null → null) — identity 키 매칭 안전. */
    private static String asText(ObjectNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    /** 온디맨드 재검사 트리거(비동기 202) — 스케줄러 runScan 트리거. 즉시 결과가 필요하면 {@code /scan-now}(동기) 사용. */
    @PostMapping("/scan")
    public ResponseEntity<Void> triggerScan(@PathVariable String host) {
        jobService.runScan(host); // TODO: 비동기 큐잉으로 전환
        promoteActive(host);      // D82: 수동 스캔 = 주기 스캔 대상 승격(INACTIVE→ACTIVE)
        return ResponseEntity.accepted().build();
    }

    /** D82(doc/43 §4.4): 수동 스캔 host 를 ACTIVE 로 flip → 주기 스캔 재포함. 정규화 키로 매칭(등록 정규화 일관), 이미 ACTIVE 면 no-op. */
    private void promoteActive(String host) {
        String normalized = DomainNames.normalize(host);
        if (normalized != null) {
            domainRepo.markActive(normalized, java.time.Instant.now());
        }
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
        domainRepo.markActive(normalized, java.time.Instant.now()); // D82: 수동 스캔 = 주기 스캔 대상 승격(INACTIVE→ACTIVE)
        return combinedDiscoveryService.forHost(normalized);
    }

    private ScanResult find(String host) {
        return scanRepo.findById(host)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
