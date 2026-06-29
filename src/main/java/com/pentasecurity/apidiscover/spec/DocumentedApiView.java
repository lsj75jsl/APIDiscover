// GET /apis 응답 1건 — 인벤토리 행(params 역직렬화 포함) (doc/37 §4). spec 패키지(api.dto↔spec 순환 회피)
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.domain.ApiChangeKind;
import com.pentasecurity.apidiscover.domain.ApiStatus;
import com.pentasecurity.apidiscover.model.SpecParam;
import java.time.Instant;
import java.util.List;

/** {@code firstDocumentedAt}/{@code lastDocumentedAt}=스펙 문서에 존재한 시각(트래픽 아님, doc/37 §1). */
public record DocumentedApiView(
        String specName,
        String method,
        String pathTemplate,
        ApiStatus status,
        ApiChangeKind lastChange,
        boolean deprecated,
        String version,
        List<SpecParam> params,
        long sourceSpecVersion,
        Instant firstDocumentedAt,
        Instant lastDocumentedAt,
        Instant changedAt) {
}
