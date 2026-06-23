// 로그에서 관찰·집계된 엔드포인트 (집합 D의 원소) (doc/01 §3.2)
package com.pentasecurity.apidiscover.model;

import java.time.Instant;
import java.util.Map;

public record DiscoveredEndpoint(
        String signature,            // "{METHOD} {host} {path_template}"
        String method,
        String host,
        String pathTemplate,
        TemplateSource templateSource,
        EndpointKind endpointKind,
        double kindConfidence,
        boolean hadQuery,        // 관측 중 query string 존재 (ApiScorer 신호)
        boolean nonBrowserUa,    // SDK/CLI user-agent 다수 (ApiScorer 신호)
        Metrics metrics,
        ParamCandidates params   // query/path 파라미터 후보 (doc/13 §2, 가산)
) {
    /** 시그니처별 누적 트래픽 메트릭. */
    public record Metrics(
            long hits,
            Instant firstSeen,
            Instant lastSeen,
            Map<String, Long> statusDist,   // "2xx","3xx","4xx","5xx" -> count
            long distinctClients,           // 근사(HLL)
            long p50RespMs,
            long p95RespMs
    ) {}
}
