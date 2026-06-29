// host 단위 결합 Discovery 뷰 — 누적 검출 SoT ∪ 활성 스펙 분류 결과 (doc/26 §6/§7)
package com.pentasecurity.apidiscover.model;

import java.util.List;

/**
 * 누적 discovered_endpoint ∪ active spec 을 Classifier(불변)로 분류한 host 단위 결합 목록.
 * per-scan /result(최근 윈도우)와 별개의 누적 카탈로그 뷰(doc/26 §6, 둘 다 제공).
 * {@code versionGroups} 는 모드가 VERSION_GROUPED 일 때만 채워지고, 그 외 모드는 빈 list(findings flat).
 *
 * <p>{@code effectiveClassification}·{@code rationale} 는 판단 근거 노출(doc/34) — <b>/discovery 전용 additive</b>.
 * {@code Finding}·report_json·ETag 와 무관(중앙 /result 무파괴). {@code rationale} 는 {@code findings} 와 동일 순서·identity 병렬.
 */
public record CombinedDiscovery(
        String host,
        long specVersion,                 // 합성 spec 버전(merged canonical 해시, 무스펙=0)
        SpecMergeStrategy mode,
        List<Finding> findings,           // 결합 목록(flat, 항상 존재)
        List<VersionGroup> versionGroups, // VERSION_GROUPED 시 version 라벨별 분리(그 외 빈 list)
        SpecSource specSource,
        EffectiveClassificationView effectiveClassification, // 도메인 현재 preset/threshold/weights(doc/34 §2)
        List<EndpointRationale> rationale                    // 엔드포인트별 판단 근거(findings 병렬, doc/34 §2)
) {
    /** 근거 미동봉(현행 6-arg) 하위호환 — effectiveClassification=null·rationale 빈 list. CLI export 등 findings 만 쓰는 경로. */
    public CombinedDiscovery(String host, long specVersion, SpecMergeStrategy mode, List<Finding> findings,
                             List<VersionGroup> versionGroups, SpecSource specSource) {
        this(host, specVersion, mode, findings, versionGroups, specSource, null, List.of());
    }

    /** host 내 version 라벨 그룹 (doc/26 §4). version="unversioned"=버전 미식별. */
    public record VersionGroup(String version, List<Finding> findings) {}
}
