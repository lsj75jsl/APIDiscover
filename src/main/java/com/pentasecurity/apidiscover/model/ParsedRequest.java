// 로그 한 줄을 파싱한 결과 (doc/01 §3.1, doc/02)
package com.pentasecurity.apidiscover.model;

import java.time.Instant;
import java.util.List;

public record ParsedRequest(
        String method,
        String rawPath,            // query string 제거된 경로
        List<String> queryKeys,    // 쿼리 파라미터 '키'만 (값 제외)
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
        String requestId           // 필드23 request_id (dedup 키, nullable)
) {}
