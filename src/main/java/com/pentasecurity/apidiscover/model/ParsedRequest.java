// 로그 한 줄을 파싱한 결과 (doc/01 §3.1, doc/02, doc/40 §6 8.3 신규 신호)
package com.pentasecurity.apidiscover.model;

import java.time.Instant;
import java.util.List;

public record ParsedRequest(
        String method,
        String rawPath,                      // query string 제거된 경로
        List<QueryParamObs> queryParams,     // 쿼리 파라미터 '키 + 값 길이 버킷'만 (값 폐기, doc/13 §2.1)
        int status,
        String host,
        String clientIp,
        String userAgent,
        Instant ts,
        long respTimeMs,
        long bodyBytes,
        boolean https,
        String referer,            // 필드13 (endpoint_kind 보조, nullable)
        String type,               // 필드19 $type — document/library 등 (endpoint_kind 핵심, nullable)
        String requestId,          // 필드23 request_id (dedup 키, nullable)
        String acrm,               // Access-Control-Request-Method (CORS preflight 결정신호, nullable; doc/23 §9 M3)
        // --- 8.3 append 신규 신호 (doc/40 §6, 미수집=null → dormant) ---
        String responseContentType,  // $sent_http_content_type — endpoint_kind 소스(2xx 만 소비, nullable)
        String accept,               // $http_accept — acceptJson 신호(nullable)
        String xRequestedWith,       // $http_x_requested_with — xRequestedWith 신호(nullable)
        String origin,               // $http_origin — originHeader 신호(nullable)
        String authScheme            // $auth_scheme — authScheme 신호(nullable)
) {

    /** 하위호환 14-arg — acrm·8.3 신규 전부 null (dormant, 기존 호출부/테스트 무변경). */
    public ParsedRequest(String method, String rawPath, List<QueryParamObs> queryParams, int status,
                         String host, String clientIp, String userAgent, Instant ts, long respTimeMs,
                         long bodyBytes, boolean https, String referer, String type, String requestId) {
        this(method, rawPath, queryParams, status, host, clientIp, userAgent, ts, respTimeMs,
                bodyBytes, https, referer, type, requestId, null, null, null, null, null, null);
    }
}
