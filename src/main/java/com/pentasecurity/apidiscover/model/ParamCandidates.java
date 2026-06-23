// endpoint 의 파라미터 후보 (body 없음 → query/path) (doc/13 §2.3)
package com.pentasecurity.apidiscover.model;

import java.util.List;
import java.util.Set;

public record ParamCandidates(List<QueryParam> query, List<PathParam> path) {

    /** 빈 후보 (가산적·항상 non-null). */
    public static final ParamCandidates EMPTY = new ParamCandidates(List.of(), List.of());

    /**
     * query param 후보. sensitive=true 면 값 길이 버킷은 억제(REDACTED)되어 빈 집합 (doc/13 §3.2).
     * count=관측된 presence 수, lenBuckets=관측된 값 길이 버킷 집합(≤5, 카디널리티 안전).
     */
    public record QueryParam(String name, long count, Set<ValueLenBucket> lenBuckets, boolean sensitive) {
    }

    /** path param 후보 = 템플릿 변수 세그먼트(저신뢰). position=0-based 세그먼트 인덱스. */
    public record PathParam(int position, String token) {
    }
}
