// 로그 한 줄을 파싱한 결과 (doc/01 §3.1, doc/02)
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
        String acrm                // Access-Control-Request-Method (CORS preflight 결정신호, nullable; doc/23 §9 M3)
) {

    /** 하위호환 14-arg — acrm 기본 null (M3 dormant, 기존 호출부/파서 무변경). */
    public ParsedRequest(String method, String rawPath, List<QueryParamObs> queryParams, int status,
                         String host, String clientIp, String userAgent, Instant ts, long respTimeMs,
                         long bodyBytes, boolean https, String referer, String type, String requestId) {
        this(method, rawPath, queryParams, status, host, clientIp, userAgent, ts, respTimeMs,
                bodyBytes, https, referer, type, requestId, null);
    }
}
