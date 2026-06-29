// 도메인 현재 effective 분류 설정 뷰 — preset/threshold/weights (doc/34 §2). /discovery 전용
package com.pentasecurity.apidiscover.model;

import java.util.Map;

/**
 * {@code GET /discovery} 의 {@code effectiveClassification}. {@code profile}=현재 도메인 preset(HIGH|MIDDLE|LOW|CUSTOM),
 * {@code threshold}=effective 임계, {@code weightsSource}=preset|custom(CUSTOM 이면 custom), {@code weights}=§4.3 신호별 effective 가중치 맵.
 */
public record EffectiveClassificationView(
        ClassificationProfile profile,
        double threshold,
        String weightsSource,
        Map<String, Double> weights) {
}
