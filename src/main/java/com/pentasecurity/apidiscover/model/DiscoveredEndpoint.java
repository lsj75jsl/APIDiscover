// 로그에서 관찰·집계된 엔드포인트 (집합 D의 원소) (doc/01 §3.2)
package com.pentasecurity.apidiscover.model;

import java.time.Instant;
import java.util.Map;

public record DiscoveredEndpoint(
        String signature,            // "{METHOD} {host} {path_template}" — ★식별 키 아님(T1 승격 전 template·파싱 host 로 발산 가능). upsert/recency 는 EndpointIdentity.key 사용
        // ponytail: signature 필드는 식별 소비처 0(테스트 발산 재현용만 잔존). 제거는 별도 cleanup(테스트 얽힘).
        String method,
        String host,
        String pathTemplate,
        TemplateSource templateSource,
        EndpointKind endpointKind,
        double kindConfidence,
        boolean hadQuery,        // 관측 중 query string 존재 (ApiScorer 신호)
        boolean nonBrowserUa,    // SDK/CLI user-agent 다수 (ApiScorer 신호)
        // --- 8.3 요청측 신호 (doc/40 §3·§6) — 모두 다수결(count*2>=hits, Acc.toEndpoint 계산)·양성 가산 ---
        boolean acceptJson,      // Accept: application/json 다수
        boolean xRequestedWith,  // X-Requested-With: XMLHttpRequest 다수
        boolean originHeader,    // Origin 헤더 존재 다수
        boolean authScheme,      // Authorization 스킴 존재 다수
        Metrics metrics,
        ParamCandidates params   // query/path 파라미터 후보 (doc/13 §2, 가산)
) {

    /** 하위호환 11-arg — 8.3 요청 신호 4종 기본 false (dormant, 기존 호출부/테스트 무변경). */
    public DiscoveredEndpoint(String signature, String method, String host, String pathTemplate,
                              TemplateSource templateSource, EndpointKind endpointKind, double kindConfidence,
                              boolean hadQuery, boolean nonBrowserUa, Metrics metrics, ParamCandidates params) {
        this(signature, method, host, pathTemplate, templateSource, endpointKind, kindConfidence,
                hadQuery, nonBrowserUa, false, false, false, false, metrics, params);
    }
    /** 시그니처별 누적 트래픽 메트릭. */
    public record Metrics(
            long hits,
            Instant firstSeen,
            Instant lastSeen,
            Map<String, Long> statusDist,   // "2xx","3xx","4xx","5xx" -> count
            long distinctClients,           // 근사(HLL)
            long p50RespMs,
            long p95RespMs,
            long acrmPresentCount           // CORS preflight 결정신호 acrm 관측 수 (doc/23 §9 M3), 기본 0
    ) {
        /** 하위호환 7-arg — acrmPresentCount 기본 0 (M3 dormant, 기존 호출부 무변경). */
        public Metrics(long hits, Instant firstSeen, Instant lastSeen, Map<String, Long> statusDist,
                       long distinctClients, long p50RespMs, long p95RespMs) {
            this(hits, firstSeen, lastSeen, statusDist, distinctClients, p50RespMs, p95RespMs, 0L);
        }
    }
}
