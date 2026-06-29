// API 상태추적(ADDED/DELETED/UPDATED) 응답 DTO — GET /spec/changes (doc/36 §4). spec 패키지(api.dto↔spec 순환 회피)
package com.pentasecurity.apidiscover.spec;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 도메인 spec 변경 상태. {@code documents}=active specName 별 현 vs 직전 diff. {@code updatedScope}=UPDATED 검출 범위 한계 자기노출
 * ({@code "deprecated_version_only"}=M7a — param-level 변경은 미검출, M7b 후속, doc/36 §3).
 */
public record SpecChanges(String host, List<DocChanges> documents, String updatedScope) {

    /** ★M7a UPDATED 스코프 — deprecated/version 만(canonical 보유 속성). param-level 은 M7b. */
    public static final String SCOPE_DEPRECATED_VERSION_ONLY = "deprecated_version_only";

    /** specName 1개 문서의 버전 전이 diff. previousVersion=null=최초 업로드(changes 전부 ADDED). */
    public record DocChanges(
            String specName,
            String filename,
            long comparedVersion,
            Long previousVersion,
            Instant comparedUploadedAt,
            Instant previousUploadedAt,
            List<ApiChange> changes) {}

    /**
     * 엔드포인트 1건 변경. status=ADDED|DELETED|UPDATED(동일성=method+path_template).
     * {@code changed}=UPDATED 시 변경 속성명(deprecated|version), {@code changedDetail}=속성→from/to. ADDED/DELETED 는 null.
     */
    public record ApiChange(
            String method,
            String pathTemplate,
            String status,
            List<String> changed,
            Map<String, FromTo> changedDetail) {}

    /** 속성 변경 전/후 값. */
    public record FromTo(Object from, Object to) {}
}
