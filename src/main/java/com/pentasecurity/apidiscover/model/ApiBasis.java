// 엔드포인트 API 판단 근거 — 분류별 polymorphic basis (doc/34 §2). /discovery rationale 전용
package com.pentasecurity.apidiscover.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * 분류별로 근거가 다르다(doc/34 §0): Shadow=점수 게이트, Active/Zombie=스펙 매칭, Unused=스펙만(무트래픽), WebPage=$type.
 * JSON 은 {@code "type"} 판별자로 구분(§2). /discovery 응답 전용 — {@code Finding}·report_json 불변.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ApiBasis.ScoreBasis.class, name = "score"),
        @JsonSubTypes.Type(value = ApiBasis.SpecMatchBasis.class, name = "spec_match"),
        @JsonSubTypes.Type(value = ApiBasis.SpecOnlyBasis.class, name = "spec_only"),
        @JsonSubTypes.Type(value = ApiBasis.KindBasis.class, name = "endpoint_kind")
})
public sealed interface ApiBasis
        permits ApiBasis.ScoreBasis, ApiBasis.SpecMatchBasis, ApiBasis.SpecOnlyBasis, ApiBasis.KindBasis {

    /** Shadow — 점수 게이트 통과(ADMIT)가 근거. mode=pathless|explicit_hint(doc/09 §2.3). signals=§4.3 신호 내역. */
    record ScoreBasis(double apiScore, double threshold, String gate, String mode,
                      List<SignalContribution> signals) implements ApiBasis {}

    /** Active/Zombie — 스펙 매칭(점수 무관). deprecated=명시 deprecated, estimated=버전 추정 Zombie. */
    record SpecMatchBasis(String specRef, boolean deprecated, boolean estimated) implements ApiBasis {}

    /** Unused — 스펙에 있고 무트래픽(관측 부재). */
    record SpecOnlyBasis(String specRef) implements ApiBasis {}

    /**
     * WebPage — $type/referer 로 비-API 판정. ponytail: 현재 Classifier 는 WebPage finding 을 산출하지 않아(게이트는 drop 카운트만)
     * 생성처가 없으나, sealed 완전성·향후 WebPage 노출 대비로 둔다(doc/34 §2 4종).
     */
    record KindBasis(String endpointKind, double kindConfidence) implements ApiBasis {}
}
