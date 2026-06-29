// 스캔 상태/결과 API — 결과는 조건부 GET (doc/07 §3.2, §3.3)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.ScanStatusView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.SummaryView;
import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.spec.SpecStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/domains/{host}")
public class ScanController {

    private final ScanResultRepository scanRepo;
    private final DiscoveryJobService jobService;
    private final SpecStore specStore;

    public ScanController(ScanResultRepository scanRepo, DiscoveryJobService jobService, SpecStore specStore) {
        this.scanRepo = scanRepo;
        this.jobService = jobService;
        this.specStore = specStore;
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

    /** 결과 — 조건부 GET. If-None-Match 가 현재 version 과 같으면 304(doc/07 §3.3). */
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
                .body(r.getReportJson());
    }

    /** 온디맨드 재검사 트리거. */
    @PostMapping("/scan")
    public ResponseEntity<Void> triggerScan(@PathVariable String host) {
        jobService.runScan(host); // TODO: 비동기 큐잉으로 전환
        return ResponseEntity.accepted().build();
    }

    private ScanResult find(String host) {
        return scanRepo.findById(host)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
