// 분류 설정 REST DTO 묶음 (doc/11 §1). MatcherConfig·ApiScorer.Weights record 재사용
package com.pentasecurity.apidiscover.api.dto;

import com.pentasecurity.apidiscover.classify.ApiScorer;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import com.pentasecurity.apidiscover.model.MatcherConfig;
import java.time.Instant;
import java.util.Map;

public final class ClassificationDtos {

    private ClassificationDtos() {
    }

    /** PUT body (전역·도메인 공용). PUT=전체 교체 — null=해당 항목 clear. */
    public record ClassificationUpsert(
            ClassificationProfile profile,        // 전역: 필수 / 도메인: nullable(상속)
            Double thresholdOverride,             // nullable
            Map<String, Double> customWeights,    // nullable, key=Weights 필드명
            MatcherConfig matcher                 // nullable (model record 재사용)
    ) {}

    /** GET 전역 — 저장값. 행 부재 시 default(MIDDLE/null). */
    public record GlobalClassificationView(
            ClassificationProfile profile,
            Double thresholdOverride,
            Map<String, Double> customWeights,
            MatcherConfig matcher,
            Instant updatedAt
    ) {}

    /** GET 도메인 — override(저장값) + effective(병합값). */
    public record DomainClassificationView(
            String host,
            OverrideView override,
            EffectiveView effective
    ) {}

    /** 도메인 override 저장값 (행 부재 시 모든 필드 null). */
    public record OverrideView(
            ClassificationProfile profile,
            Double thresholdOverride,
            Map<String, Double> customWeights,
            MatcherConfig matcher,
            Instant updatedAt
    ) {}

    /**
     * effective(병합) 노출 — 런타임 객체(scorer/hints)는 제외.
     * weights 에 threshold·repeatMinCount 포함, matcher.includeWebForms()=정규화된 effective 플래그.
     */
    public record EffectiveView(
            ClassificationProfile profile,
            ApiScorer.Weights weights,
            MatcherConfig matcher
    ) {}
}
