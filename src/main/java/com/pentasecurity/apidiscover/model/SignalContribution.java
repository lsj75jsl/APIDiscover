// API 점수 신호 1개의 기여 내역 — 가중치·발화여부·기여점수 (doc/34 §2, scoreExplain 산출)
package com.pentasecurity.apidiscover.model;

/**
 * {@code ApiScorer.scoreExplain} 가 평가한 신호 1개. {@code key}=§4.3 신호명(WEIGHT_KEYS),
 * {@code weight}=현재 effective 가중치, {@code fired}=발화 여부, {@code contribution}=fired 면 weight(staticAssetPenalty 는 음수) 아니면 0.
 */
public record SignalContribution(String key, double weight, boolean fired, double contribution) {
    // record — 필드는 헤더 괄호에 선언, 본문 비움이 정상(순수 데이터, dead 아님).
}
