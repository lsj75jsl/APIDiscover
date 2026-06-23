// Zombie 조치 시급성 점수 + 파생 밴드 (doc/16 §2, §3). confidence(진짜 Zombie 인가)와 직교
package com.pentasecurity.apidiscover.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * severity score(0..1) + 파생 band. band 는 단일 진실원(score 임계) — JSON 에 "band" 로 출현.
 * 임계: ≥0.66 HIGH / ≥0.33 MEDIUM / else LOW (doc/16 §2, 1차값).
 */
public record Severity(double score) {

    @JsonProperty("band")
    public SeverityBand band() {
        if (score >= 0.66) {
            return SeverityBand.HIGH;
        }
        if (score >= 0.33) {
            return SeverityBand.MEDIUM;
        }
        return SeverityBand.LOW;
    }
}
