// Zombie severity 산정 — 가용 메트릭만, 외부 시계 미사용(순수·결정적) (doc/16 §2)
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.model.Severity;
import java.time.Duration;

/**
 * severity = 0.5·hitsScore + 0.3·successScore + 0.2·spanScore (doc/16 §2).
 * <ul>
 *   <li>hitsScore = clamp(log10(hits+1)/3) — 볼륨(마이그레이션 리스크)</li>
 *   <li>successScore = total&gt;0 ? 2xx/total : 0 — 진짜 성공 사용 vs 탐침/에러</li>
 *   <li>spanScore = clamp(log10((lastSeen−firstSeen)초+1)/4) — window 내 지속 사용(recency 대용)</li>
 * </ul>
 */
final class ZombieSeverity {

    private ZombieSeverity() {
    }

    static Severity of(Evidence e) {
        double hitsScore = clamp(Math.log10(e.hits + 1.0) / 3.0);
        double successScore = e.total > 0 ? (double) e.success2xx / e.total : 0.0;
        long spanSec = (e.firstSeen != null && e.lastSeen != null)
                ? Math.max(0L, Duration.between(e.firstSeen, e.lastSeen).getSeconds())
                : 0L;
        double spanScore = clamp(Math.log10(spanSec + 1.0) / 4.0);
        double score = 0.5 * hitsScore + 0.3 * successScore + 0.2 * spanScore;
        return new Severity(round3(score));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
