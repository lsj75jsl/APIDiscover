// 2종 포맷(OpenAPI/Postman)이 공통 변환되는 내부 엔드포인트 표현 (doc/03 §1, doc/37 §2 params 가산)
package com.pentasecurity.apidiscover.model;

import java.util.List;

public record CanonicalEndpoint(
        String method,
        String pathTemplate,   // 예: /users/{id}
        String host,           // null 가능(host-agnostic)
        boolean deprecated,    // Zombie 판정 기준
        String version,        // null 가능
        String sourceRef,      // 추적용 원본 참조
        List<SpecParam> params // 스펙 파라미터(doc/37 §2). 매칭/dedupe 키 아님(method+host+template 불변)
) {
    /** params null → 빈 리스트(구 canonicalJson 역직렬화·6-arg 호출 하위호환, doc/37 §2). */
    public CanonicalEndpoint {
        params = (params == null) ? List.of() : List.copyOf(params);
    }

    /** 하위호환 — params 미추출 호출부(canonicalize·일부 파서·테스트) 기본 빈 params. */
    public CanonicalEndpoint(String method, String pathTemplate, String host,
                             boolean deprecated, String version, String sourceRef) {
        this(method, pathTemplate, host, deprecated, version, sourceRef, List.of());
    }
}
