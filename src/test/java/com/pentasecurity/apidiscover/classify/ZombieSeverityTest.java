// ZombieSeverity / Severity.band 단위 테스트 — 결정적 산정·밴드 경계·엣지 (doc/16 §2, §5)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.Severity;
import com.pentasecurity.apidiscover.model.SeverityBand;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ZombieSeverityTest {

    @Test
    void bandThresholds() {
        assertThat(new Severity(0.66).band()).isEqualTo(SeverityBand.HIGH);
        assertThat(new Severity(0.659).band()).isEqualTo(SeverityBand.MEDIUM);
        assertThat(new Severity(0.33).band()).isEqualTo(SeverityBand.MEDIUM);
        assertThat(new Severity(0.329).band()).isEqualTo(SeverityBand.LOW);
    }

    @Test
    void highVolumeSustainedSuccessIsHigh() {
        Evidence e = new Evidence();
        e.hits = 1000;
        e.success2xx = 1000;
        e.total = 1000;
        e.firstSeen = Instant.EPOCH;
        e.lastSeen = Instant.EPOCH.plusSeconds(100_000); // 지속
        // hitsScore≈1, success=1, spanScore≈1 → score≈1.0
        assertThat(ZombieSeverity.of(e).band()).isEqualTo(SeverityBand.HIGH);
    }

    @Test
    void lowVolumeNoSuccessNoSpanIsLow() {
        Evidence e = new Evidence();
        e.hits = 1;
        e.success2xx = 0;
        e.total = 1; // 1건, 비-2xx
        e.firstSeen = Instant.EPOCH;
        e.lastSeen = Instant.EPOCH; // span 0
        // hitsScore≈0.10, success=0, span=0 → score≈0.05 → LOW
        assertThat(ZombieSeverity.of(e).band()).isEqualTo(SeverityBand.LOW);
    }

    @Test
    void totalZeroYieldsZeroSuccessScoreNoCrash() {
        Evidence e = new Evidence();
        e.hits = 1000;
        e.total = 0; // 분모 0 → successScore 0 (예외 없이)
        e.firstSeen = Instant.EPOCH;
        e.lastSeen = Instant.EPOCH;
        Severity s = ZombieSeverity.of(e);
        // 0.5·1.0(hits) + 0.3·0(success) + 0.2·0(span) = 0.5 → MEDIUM
        assertThat(s.score()).isEqualTo(0.5);
        assertThat(s.band()).isEqualTo(SeverityBand.MEDIUM);
    }

    @Test
    void firstEqualsLastSeenSpanContributesZero() {
        Evidence e = new Evidence();
        e.hits = 1;
        e.success2xx = 1;
        e.total = 1;
        e.firstSeen = Instant.EPOCH;
        e.lastSeen = Instant.EPOCH; // spanSec 0 → spanScore 0
        // 0.5·0.1 + 0.3·1.0 + 0.2·0 = 0.35
        assertThat(ZombieSeverity.of(e).score()).isEqualTo(0.35);
    }
}
