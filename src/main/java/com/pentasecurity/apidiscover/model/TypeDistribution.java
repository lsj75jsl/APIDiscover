// corpus $type 분포 히스토그램(top-N + other) — taxonomy/드리프트 self-reporting (doc/21 §3 Tier1)
package com.pentasecurity.apidiscover.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * 한 스캔 corpus 에서 관측된 $type 값별 빈도(상위 N) + 나머지 합산(other). 운영자가 매 스캔 자기 도메인의
 * $type vocabulary·document 트랩을 수동 Loki 재조회 없이 확인하기 위한 진단 노출(형제 record 패턴).
 * $type 은 콘텐츠 분류 라벨이라 민감정보 아님(마스킹 불요).
 */
public record TypeDistribution(List<Entry> top, long other) {

    public record Entry(String type, long count) {}

    /** 빈 분포 ($type 관측 없음, 항상 non-null). */
    public static final TypeDistribution NONE = new TypeDistribution(List.of(), 0);

    /**
     * ETag 입력용 — 노출된 distinct $type 키(정렬, count 제외). 신규 값 출현=taxonomy 드리프트→version bump,
     * 요청량에 따른 count 변동=무bump 로 304 효율 보존(doc/07 §8, doc/21 §3 Tier1). 'other' 버킷은 실제 키가 아니라 제외.
     */
    @JsonIgnore
    public List<String> distinctKeys() {
        return top.stream().map(Entry::type).sorted().toList();
    }
}
