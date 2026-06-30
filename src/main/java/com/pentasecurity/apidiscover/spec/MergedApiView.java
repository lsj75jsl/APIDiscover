// GET /apis?view=merged 응답 1건 — 도메인 전체 병합 표면(문서 구분 없이 method+path 병합) (doc/38 §4)
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.domain.ApiStatus;
import com.pentasecurity.apidiscover.model.SpecParam;
import java.util.List;

/**
 * 도메인의 (method, path_template) 단위 병합 뷰. 기여 문서 중 하나라도 ACTIVE 면 ACTIVE, deprecated OR,
 * version/params 는 최신(sourceSpecVersion 최대) 기준(doc/38 §4.2). {@code contributingSpecNames}=기여 문서 목록.
 */
public record MergedApiView(
        String method,
        String pathTemplate,
        ApiStatus status,
        boolean deprecated,
        String version,
        List<SpecParam> params,
        long sourceSpecVersion,
        List<String> contributingSpecNames) {
}
