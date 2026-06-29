// spec 버전 canonical projection — rawDoc(oid) 미선택, canonicalJson(text) 포함 (doc/36 M7.2). API 상태추적 diff 입력
package com.pentasecurity.apidiscover.domain;

import java.time.Instant;

/**
 * {@code SpecRecord} 의 diff 입력 컬럼만(canonicalJson 포함·rawDoc oid 미선택). compute-on-read diff(현 active vs 직전 inactive)가
 * 비-트랜잭션(auto-commit)에서도 oid materialize 없이 안전하게 두 버전 canonical 을 읽도록(doc/28·D51 교훈). JPQL 생성자식으로 SELECT.
 */
public record SpecCanonicalProjection(
        String specName,
        long specVersion,
        String canonicalJson,
        Instant uploadedAt,
        String filename,
        boolean active) {
}
