// 도메인 1회 스캔의 최종 리포트 (doc/01 §4)
package com.pentasecurity.apidiscover.model;

import com.pentasecurity.apidiscover.ingest.LogWindow;
import java.time.Instant;
import java.util.List;

public record DiscoveryReport(
        String host,
        Instant generatedAt,
        LogWindow logWindow,
        long specVersion,
        Summary summary,
        List<Finding> findings,
        DroppedNonApi droppedNonApi,  // non_api 게이트 탈락 사유별 집계 (doc/12), 항상 non-null
        DroppedByLimit droppedByLimit, // 카디널리티 상한 초과 drop 집계 (doc/13), 항상 non-null
        DroppedNonExistent droppedNonExistent, // 404-only 비실재 drop 집계 (doc/19), 항상 non-null
        EndpointKindSignal endpointKindSignal, // endpoint_kind referer 보조 신호 커버리지 (doc/20), 항상 non-null
        TypeDistribution typeDistribution, // corpus $type 히스토그램 (doc/21), 항상 non-null
        PreflightSignal preflightSignal, // CORS preflight 결정신호(acrm) 가용성 (doc/23 §9), 항상 non-null
        SpecSource specSource // 스펙 출처·파싱 경고 (doc/25 §A), 항상 non-null(무스펙=EMPTY)
) {
    public record Summary(
            int discovered,
            int active,
            int shadow,
            int zombie,
            int unused,
            int lowConfidence // confidence<0.5 인 Shadow/Zombie 수 (doc/25 §A.3, at-a-glance)
    ) {}
}
