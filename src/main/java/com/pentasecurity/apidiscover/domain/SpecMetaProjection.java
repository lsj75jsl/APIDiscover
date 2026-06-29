// 스펙 메타 projection — rawDoc(oid LOB) 미선택 (doc/28). REST 메타 조회(트랜잭션 밖 auto-commit)에서 oid materialize 회피
package com.pentasecurity.apidiscover.domain;

import com.pentasecurity.apidiscover.spec.SpecFormat;
import java.time.Instant;

/**
 * {@code SpecRecord} 의 메타 컬럼만(rawDoc/canonicalJson/warningsJson 제외). REST 메타 조회(M2/M4/M6)가 엔티티 대신 이 projection 을
 * 로드 → {@code @Lob byte[] rawDoc}(PG oid) materialize 회피(auto-commit 시 "Large Objects may not be used in auto-commit mode" 500 차단).
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
