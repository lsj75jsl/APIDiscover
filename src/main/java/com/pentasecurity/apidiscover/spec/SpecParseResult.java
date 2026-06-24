// 스펙 파싱 결과 — Canonical 엔드포인트 + recoverable 경고 (doc/25 §A.1, doc/14 deferred seam)
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.List;

/**
 * {@link SpecParser#parse} 반환형. warnings = skip 된 행/item 등 recoverable 경고(fatal 은 예외).
 * 업로드 시 SpecRecord 에 영속되어 스캔 리포트 specSource.warnings 로 노출(재파싱 없음).
 */
public record SpecParseResult(List<CanonicalEndpoint> endpoints, List<String> warnings) {

    public SpecParseResult {
        endpoints = (endpoints == null) ? List.of() : List.copyOf(endpoints);
        warnings = (warnings == null) ? List.of() : List.copyOf(warnings);
    }
}
