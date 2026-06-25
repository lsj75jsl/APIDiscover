// 엣지 hostname별 domain 조회 + 특정 (hostname,domain) 온디맨드 조회 API (요구사항)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.ScanStatusView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.SummaryView;
import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loki 조회 파라미터(엣지 hostname, domain)를 host(엣지)별로 목록 조회하고,
 * 특정 (hostname, domain) 1쌍을 외부에서 직접 조회·분석하도록 노출한다.
 */
@RestController
@RequestMapping("/api/v1/hostnames/{hostname}")
public class HostQueryController {

    private final DomainConfigRepository domainRepo;
    private final DiscoveryJobService jobService;

    public HostQueryController(DomainConfigRepository domainRepo, DiscoveryJobService jobService) {
        this.domainRepo = domainRepo;
        this.jobService = jobService;
    }

    /** 이 엣지 hostname 이 서빙하는 domain 목록 (host↔domain 역방향 조회). */
    @GetMapping("/domains")
    public List<String> domains(@PathVariable String hostname) {
        return domainRepo.findByHostname(hostname).stream().map(d -> d.getHost()).toList();
    }

    /**
     * 특정 (hostname, domain) 온디맨드 조회+분석. from/to(ISO-8601) 미지정 시 기본 윈도우.
     * 결과 요약을 반환한다.
     */
    @PostMapping("/domains/{domain}/query")
    public ScanStatusView query(@PathVariable String hostname,
                                @PathVariable String domain,
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        LogWindow window = (from != null && to != null)
                ? new LogWindow(Instant.parse(from), Instant.parse(to))
                : jobService.defaultWindow();

        ScanResult r = jobService.runOnDemand(hostname, domain, window);
        return new ScanStatusView(r.getHost(), r.getState(), r.getLastScanAt(), r.getVersion(),
                new SummaryView(r.getDiscovered(), r.getActive(), r.getShadow(), r.getZombie(), r.getUnused()), r.getTotalDropped());
    }
}
