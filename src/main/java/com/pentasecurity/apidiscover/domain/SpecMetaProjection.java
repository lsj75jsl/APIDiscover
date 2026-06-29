// 스펙 메타 projection — 대용량 text 컬럼 미선택 (doc/28). REST 메타 조회에서 대용량 컬럼 로드 회피(LOB 폭증 방지)
package com.pentasecurity.apidiscover.domain;

import com.pentasecurity.apidiscover.spec.SpecFormat;
import java.time.Instant;

/**
 * {@code SpecRecord} 의 메타 컬럼만(대용량 text canonicalJson/warningsJson 제외). REST 메타 조회(M2/M4/M6)가 엔티티 대신 이 projection 을
 * 로드 → 목록/메타 조회 시 대용량 컬럼을 끌어오지 않는다(LOB 폭증 방지).
 * JPQL 생성자식 {@code select new SpecMetaProjection(...)} 로 메타 컬럼만 SELECT.
 */
public record SpecMetaProjection(
        SpecFormat format,
        long specVersion,
        int endpointCount,
        Instant uploadedAt,
        String specName,
        String filename,
        boolean active) {
}
