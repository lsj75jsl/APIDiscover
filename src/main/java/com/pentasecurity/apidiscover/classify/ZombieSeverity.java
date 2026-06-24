// Zombie severity 산정 — 가용 메트릭만, 외부 시계 미사용(순수·결정적) (doc/16 §2, doc/24 entrenchment 보강)
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.model.Severity;
import java.time.Duration;
import java.time.Instant;

/**
 * base = 0.5·hitsScore + 0.3·successScore + 0.2·spanScore (doc/16 §2).
 * <ul>
 *   <li>hitsScore = clamp(log10(hits+1)/3) — 볼륨(마이그레이션 리스크)</li>
 *   <li>successScore = total&gt;0 ? 2xx/total : 0 — 진짜 성공 사용 vs 탐침/에러</li>
 *   <li>spanScore = clamp(log10((lastSeen−firstSeen)초+1)/4) — window 내 지속 사용(recency 대용)</li>
 * </ul>
 * severity = clamp(base + entrenchmentBonus) — 누적 lifespan(lastSeen − 이력상 firstSeen, 데이터 ts·now 미사용) 가산(doc/24 §2).
 */
final class ZombieSeverity {

    // entrenchment 1차값(캐비엇, seam=@ConfigurationProperties, doc/24 §2). W=최대보너스, GRACE 미만 0, SAT 이상 W.
    private static final double ENTRENCHMENT_WEIGHT = 0.2;
    private static final double GRACE_DAYS = 7.0;
    private static final double SATURATION_DAYS = 90.0;

    private ZombieSeverity() {
    }

    /** 하위호환 — 이력 없는 경로(콜드스타트): historicalFirstSeen=현재 firstSeen → lifespan=window span ≪ GRACE → 보너스 0 = 현행(doc/24 §4). */
    static Severity of(Evidence e) {
        return of(e, e.firstSeen);
    }

    /** base(doc/16) + entrenchmentBonus(historicalFirstSeen → lastSeen 누적 lifespan). */
    static Severity of(Evidence e, Instant historicalFirstSeen) {
        double score = baseScore(e) + entrenchmentBonus(historicalFirstSeen, e.lastSeen);
        return new Severity(round3(clamp(score)));
    }

    private static double baseScore(Evidence e) {
        double hitsScore = clamp(Math.log10(e.hits + 1.0) / 3.0);
        double successScore = e.total > 0 ? (double) e.success2xx / e.total : 0.0;
        long spanSec = (e.firstSeen != null && e.lastSeen != null)
                ? Math.max(0L, Duration.between(e.firstSeen, e.lastSeen).getSeconds())
                : 0L;
        double spanScore = clamp(Math.log10(spanSec + 1.0) / 4.0);
        return 0.5 * hitsScore + 0.3 * successScore + 0.2 * spanScore;
    }

    /** 누적 lifespan(일) → 보너스. GRACE 미만 0, SAT 이상 W, 사이는 log 보간 (doc/24 §2). 데이터 ts 차(now 미사용). */
    private static double entrenchmentBonus(Instant historicalFirstSeen, Instant lastSeen) {
        if (historicalFirstSeen == null || lastSeen == null) {
            return 0.0;
        }
        double lifespanDays =
                Math.max(0L, Duration.between(historicalFirstSeen, lastSeen).getSeconds()) / 86_400.0;
        double num = Math.log10(lifespanDays + 1.0) - Math.log10(GRACE_DAYS + 1.0);
        double den = Math.log10(SATURATION_DAYS + 1.0) - Math.log10(GRACE_DAYS + 1.0);
        return ENTRENCHMENT_WEIGHT * clamp(num / den);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
