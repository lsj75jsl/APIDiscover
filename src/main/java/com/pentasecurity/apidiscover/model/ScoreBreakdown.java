// API 점수 산출 내역 — 신호별 기여 + 총점(clamp[0,1]·3자리) (doc/34 §3, ApiScorer.scoreExplain 반환)
package com.pentasecurity.apidiscover.model;

import java.util.List;

/**
 * {@code total} = signals 의 contribution 합을 clamp[0,1]·3자리 반올림한 값(=기존 {@code score()} 와 동일).
 * {@code signals} = 평가된 신호 전부(mode 에 따라 path-shape vs pathHint, doc/34 §2).
 */
public record ScoreBreakdown(double total, List<SignalContribution> signals) {
}
