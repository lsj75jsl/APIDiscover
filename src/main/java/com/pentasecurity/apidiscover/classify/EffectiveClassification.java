// host 에 대한 effective 분류 설정 (doc/10 §4). 병합 결과 + 즉시 사용 가능한 scorer/hints
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import com.pentasecurity.apidiscover.model.EffectiveClassificationView;
import com.pentasecurity.apidiscover.model.MatcherConfig;

/**
 * 전역+도메인 병합 결과. {@code scorer}+{@code hints} 는 파이프라인이 직접 사용하고,
 * {@code profile/weights/matcher} 는 후속 {@code GET .../classification}(effective 노출) 소비자용(계산 비용 0이라 동봉).
 * 불변 → 호스트 간 공유 안전.
 */
public record EffectiveClassification(
        ClassificationProfile profile,
        ApiScorer.Weights weights,
        MatcherConfig matcher,
        ApiScorer scorer,
        ApiHintMatcher hints) {

    /**
     * /discovery·/domains 응답용 effective 뷰 (doc/34 §2, doc/35 M2). weightsSource=CUSTOM→custom·그 외 preset.
     * ★단일 진실원 — CombinedDiscoveryService(/discovery)·DomainController(M2)가 공유(중복 회피).
     */
    public EffectiveClassificationView toView() {
        String weightsSource = (profile == ClassificationProfile.CUSTOM) ? "custom" : "preset";
        return new EffectiveClassificationView(profile, scorer.threshold(), weightsSource,
                ApiScorer.weightsAsMap(weights));
    }
}
