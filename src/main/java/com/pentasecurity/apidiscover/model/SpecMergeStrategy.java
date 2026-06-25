// 도메인별 멀티 스펙 병합 전략 (doc/26 §5, D35). 기본 MERGE
package com.pentasecurity.apidiscover.model;

/**
 * <ul>
 *   <li>{@code MERGE}(기본·현행): 같은 specName 만 교체(형제 문서 유지), 활성 spec = ∪ active docs flat 1집합.</li>
 *   <li>{@code SEPARATE}: 업로드가 host 의 타 문서 전부 비활성(새 문서=전체 교체).</li>
 *   <li>{@code VERSION_GROUPED}: 다 문서 active 공존, 매칭은 union(MERGE 와 동일 1매칭셋); 버전 그룹 뷰는 3단계.</li>
 * </ul>
 */
public enum SpecMergeStrategy {
    MERGE,
    SEPARATE,
    VERSION_GROUPED
}
