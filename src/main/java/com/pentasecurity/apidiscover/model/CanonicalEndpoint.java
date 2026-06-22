// 3종 포맷(OpenAPI/Postman/CSV)이 공통 변환되는 내부 엔드포인트 표현 (doc/03 §1)
package com.pentasecurity.apidiscover.model;

public record CanonicalEndpoint(
        String method,
        String pathTemplate,   // 예: /users/{id}
        String host,           // null 가능(host-agnostic)
        boolean deprecated,    // Zombie 판정 기준
        String version,        // null 가능
        String sourceRef       // 추적용 원본 참조
) {}
