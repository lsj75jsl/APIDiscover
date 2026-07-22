// host 결합 Discovery 조회 API — 총 알려진 API 요약(/discovery)·상세(/discovery/detail) (doc/26 §6/§7)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.ApiLists;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.DiscoverySummaryView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.SummaryView;
import com.pentasecurity.apidiscover.batch.CombinedDiscoveryService;
import com.pentasecurity.apidiscover.model.Classification;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import com.pentasecurity.apidiscover.model.Finding;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/domains/{host}")
public class CombinedDiscoveryController {

    private final CombinedDiscoveryService service;

    public CombinedDiscoveryController(CombinedDiscoveryService service) {
        this.service = service;
    }

    /**
     * 총 알려진 API 요약 — 누적 검출∪스펙 결합 결과의 유형별 카운트+목록(summary+apis). per-scan /scan-result 와 동형(창 무관 누적).
     * 상세(findings·판단근거·effectiveClassification)는 {@code /discovery/detail}.
     */
    @GetMapping("/discovery")
    public DiscoverySummaryView discovery(@PathVariable String host) {
        return summarize(service.forHost(host));
    }

    /** 총 알려진 API 상세정보 — 결합 findings + 엔드포인트별 판단근거(rationale)·effectiveClassification(VERSION_GROUPED 시 versionGroups). */
    @GetMapping("/discovery/detail")
    public CombinedDiscovery discoveryDetail(@PathVariable String host) {
        return service.forHost(host);
    }

    /**
     * CombinedDiscovery(누적 findings) → 유형별 요약. finding 객체의 {@code classification()} 로 직접 분류(창 무관 누적 카탈로그).
     * {@code discovered}=전체 finding, 나머지=분류별. 각 원소 {@code ApiLists.label} 형식. summary 카운트=각 목록 길이.
     */
    private static DiscoverySummaryView summarize(CombinedDiscovery cd) {
        List<String> discovered = new ArrayList<>();
        Map<Classification, List<String>> byClass = new EnumMap<>(Classification.class);
        for (Finding f : cd.findings()) {
            String label = ApiLists.label(f.method(), f.host(), f.pathTemplate());
            discovered.add(label);
            byClass.computeIfAbsent(f.classification(), k -> new ArrayList<>()).add(label);
        }
        ApiLists apis = new ApiLists(discovered,
                byClass.getOrDefault(Classification.ACTIVE, List.of()),
                byClass.getOrDefault(Classification.SHADOW, List.of()),
                byClass.getOrDefault(Classification.ZOMBIE, List.of()),
                byClass.getOrDefault(Classification.UNUSED, List.of()));
        SummaryView summary = new SummaryView(discovered.size(),
                apis.active().size(), apis.shadow().size(), apis.zombie().size(), apis.unused().size());
        return new DiscoverySummaryView(cd.host(), summary, apis);
    }
}
