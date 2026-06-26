// ScanTier.effectiveInterval 단위 테스트 — active/inactive/dormant/default 밴드 경계·override·tiering-off ZERO (doc/33 §4.2, D48)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScanTierTest {

    private static final Instant NOW = Instant.parse("2026-06-26T12:00:00Z");
    private static final Duration ACTIVE = Duration.ofMinutes(30);
    private static final Duration DEFAULT = Duration.ofHours(2);
    private static final Duration INACTIVE = Duration.ofHours(6);
    private static final Duration ACTIVE_THRESHOLD = Duration.ofHours(24);
    private static final Duration DORMANT_AFTER = Duration.ofDays(14);
    private static final Duration DORMANT = Duration.ofDays(1);

    @Test
    void activeWhenRecentlySeen() {
        assertThat(ScanTier.effectiveInterval(seen(Duration.ofHours(1)), NOW, cfg(true))).isEqualTo(ACTIVE);
    }

    @Test
    void activeAtThresholdBoundaryInclusive() {
        // age == active-threshold → active(<=)
        assertThat(ScanTier.effectiveInterval(seen(ACTIVE_THRESHOLD), NOW, cfg(true))).isEqualTo(ACTIVE);
    }

    @Test
    void inactiveBetweenThresholdAndDormant() {
        assertThat(ScanTier.effectiveInterval(seen(Duration.ofDays(5)), NOW, cfg(true))).isEqualTo(INACTIVE);
    }

    @Test
    void inactiveAtDormantAfterBoundary() {
        // age == dormant-after → inactive(not >), dormant 은 초과부터
        assertThat(ScanTier.effectiveInterval(seen(DORMANT_AFTER), NOW, cfg(true))).isEqualTo(INACTIVE);
    }

    @Test
    void dormantWhenAgeExceedsDormantAfter() {
        assertThat(ScanTier.effectiveInterval(seen(DORMANT_AFTER.plusSeconds(1)), NOW, cfg(true))).isEqualTo(DORMANT);
        assertThat(ScanTier.effectiveInterval(seen(Duration.ofDays(20)), NOW, cfg(true))).isEqualTo(DORMANT);
    }

    @Test
    void defaultWhenNeverSeen() {
        DomainConfig d = new DomainConfig();
        d.setHost("x");
        d.setLastSeenAt(null);
        assertThat(ScanTier.effectiveInterval(d, NOW, cfg(true))).isEqualTo(DEFAULT);
    }

    @Test
    void overrideTakesPrecedenceOverTier() {
        DomainConfig d = seen(Duration.ofHours(1)); // 원래 active
        d.setIntervalOverride("PT45M");
        assertThat(ScanTier.effectiveInterval(d, NOW, cfg(true))).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    void invalidOverrideFallsBackToTier() {
        DomainConfig d = new DomainConfig();
        d.setHost("x");
        d.setLastSeenAt(null);
        d.setIntervalOverride("not-a-duration");
        assertThat(ScanTier.effectiveInterval(d, NOW, cfg(true))).isEqualTo(DEFAULT); // 폴백 = default tier
    }

    @Test
    void tieringDisabledReturnsZero() {
        // 무회귀: nextScanDueAt=now=즉시 due=LRS 동치(override·티어 무시)
        DomainConfig d = seen(Duration.ofHours(1));
        d.setIntervalOverride("PT45M");
        assertThat(ScanTier.effectiveInterval(d, NOW, cfg(false))).isEqualTo(Duration.ZERO);
    }

    private static DomainConfig seen(Duration age) {
        DomainConfig d = new DomainConfig();
        d.setHost("x");
        d.setLastSeenAt(NOW.minus(age));
        return d;
    }

    private static ApiDiscoverProperties.Scan cfg(boolean tiering) {
        return new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), 100, Duration.ofMinutes(30), 0, 0L, true,
                Duration.ZERO, 0, tiering, ACTIVE, DEFAULT, INACTIVE, ACTIVE_THRESHOLD, 500, Duration.ofHours(24),
                "", DORMANT_AFTER, DORMANT);
    }
}
