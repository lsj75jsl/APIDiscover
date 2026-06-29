// 엔드포인트 1건의 판단 근거 — findings 와 동일 순서·identity 병렬 (doc/34 §2). /discovery 전용
package com.pentasecurity.apidiscover.model;

/**
 * {@code CombinedDiscovery.rationale} 항목. (method,host,pathTemplate)=대응 {@code Finding} 의 identity,
 * {@code classification}=그 분류, {@code basis}=분류별 근거(polymorphic). findings[i] ↔ rationale[i] 병렬(동일 순서).
 */
public record EndpointRationale(
        String method,
        String host,
        String pathTemplate,
        Classification classification,
        ApiBasis basis) {
}
