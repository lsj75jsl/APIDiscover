// 도메인별 effectiveInterval 계산 — C 활동 티어 + F dormant band + intervalOverride 통합 순수함수 (doc/33 §4.2, D48)
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 스캔 시점 도메인 effectiveInterval (doc/33 §4.2, D48). {@code nextScanDueAt = now + effectiveInterval} 로 영속해
 * C(active/inactive/default 티어)·F(dormant 최장 band)·{@code intervalOverride} 를 단일 due 값으로 collapse 한다.
 * 우선순위: intervalOverride(명시) → lastSeenAt age 기반 티어. {@code tiering-enabled=false} → ZERO(=즉시 due=LRS 동치, PR1 롤백 스위치).
 *
 * <p>전제: {@code active-threshold < dormant-after}(정상설정). 역전 misconfig 시 active 체크가 dormant 보다 먼저라
 * 중간 age 가 active 로 평가돼 과스캔(안전쪽 — 크래시 없음·드물게 더 자주 스캔할 뿐). 검증 프레임워크는 과함(P3-2).
 */
public final class ScanTier {

    private static final Logger log = LoggerFactory.getLogger(ScanTier.class);

    private ScanTier() {
    }

    public static Duration effectiveInterval(DomainConfig d, Instant now, ApiDiscoverProperties.Scan cfg) {
        if (!cfg.tieringEnabled()) {
            return Duration.ZERO; // 무회귀: nextScanDueAt=now=즉시 due=LRS 동치(PR1 롤백 스위치)
        }
        String override = d.getIntervalOverride();
        if (override != null) {
            try {
                return Duration.parse(override); // 명시 override 최우선(기존 P3 TODO 배선)
            } catch (DateTimeParseException e) {
                log.warn("invalid intervalOverride '{}' for host={} — falling back to tier", override, d.getHost());
            }
        }
        Instant lastSeen = d.getLastSeenAt();
        if (lastSeen == null) {
            return cfg.defaultInterval(); // 신호 부재=보수적 중간
        }
        Duration age = Duration.between(lastSeen, now);
        if (age.compareTo(cfg.activeThreshold()) <= 0) {
            return cfg.activeInterval();   // C active
        }
        if (age.compareTo(cfg.dormantAfter()) > 0) {
            return cfg.dormantInterval();  // F dormant(최장, 삭제 아님)
        }
        return cfg.inactiveInterval();     // C inactive
    }
}
