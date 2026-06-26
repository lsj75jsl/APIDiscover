// OffPeakWindow 단위 테스트 — in/out·경계(start 포함·end 배제)·자정 wrap·blank/파싱실패=peak·zone 적용 (doc/33 §5, D48)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OffPeakWindowTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final String WINDOW = "01:00-06:00";
    private static final String WRAP = "22:00-06:00"; // 자정 넘김

    @Test
    void inWindowIsOffPeak() {
        assertThat(OffPeakWindow.isOffPeak(at("03:00"), WINDOW, UTC)).isTrue();
    }

    @Test
    void outOfWindowIsPeak() {
        assertThat(OffPeakWindow.isOffPeak(at("12:00"), WINDOW, UTC)).isFalse();
    }

    @Test
    void startBoundaryInclusiveEndBoundaryExclusive() {
        assertThat(OffPeakWindow.isOffPeak(at("01:00"), WINDOW, UTC)).isTrue();  // start 포함
        assertThat(OffPeakWindow.isOffPeak(at("05:59"), WINDOW, UTC)).isTrue();
        assertThat(OffPeakWindow.isOffPeak(at("06:00"), WINDOW, UTC)).isFalse(); // end 배제
    }

    @Test
    void midnightWrapCoversBeforeAndAfterMidnight() {
        assertThat(OffPeakWindow.isOffPeak(at("22:00"), WRAP, UTC)).isTrue();  // start 포함
        assertThat(OffPeakWindow.isOffPeak(at("23:00"), WRAP, UTC)).isTrue();  // 자정 전
        assertThat(OffPeakWindow.isOffPeak(at("03:00"), WRAP, UTC)).isTrue();  // 자정 후
        assertThat(OffPeakWindow.isOffPeak(at("06:00"), WRAP, UTC)).isFalse(); // end 배제
        assertThat(OffPeakWindow.isOffPeak(at("12:00"), WRAP, UTC)).isFalse(); // 밖
    }

    @Test
    void nullOrBlankWindowIsPeak() {
        assertThat(OffPeakWindow.isOffPeak(at("03:00"), null, UTC)).isFalse();
        assertThat(OffPeakWindow.isOffPeak(at("03:00"), "  ", UTC)).isFalse();
    }

    @Test
    void unparseableWindowIsPeak() {
        assertThat(OffPeakWindow.isOffPeak(at("03:00"), "0100to0600", UTC)).isFalse(); // dash 없음
        assertThat(OffPeakWindow.isOffPeak(at("03:00"), "aa:bb-cc:dd", UTC)).isFalse(); // 파싱 실패
    }

    @Test
    void zoneIsApplied() {
        // 03:00Z 는 UTC 에선 off-peak(01-06) 안, America/New_York(EDT −4)에선 전날 23:00 → peak
        assertThat(OffPeakWindow.isOffPeak(at("03:00"), WINDOW, UTC)).isTrue();
        assertThat(OffPeakWindow.isOffPeak(at("03:00"), WINDOW, ZoneId.of("America/New_York"))).isFalse();
    }

    @Test
    void zoneHelperBlankIsSystemDefaultNamedResolves() {
        assertThat(OffPeakWindow.zone("")).isEqualTo(ZoneId.systemDefault());
        assertThat(OffPeakWindow.zone(null)).isEqualTo(ZoneId.systemDefault());
        assertThat(OffPeakWindow.zone("UTC").getId()).isEqualTo("UTC");
    }

    @Test
    void invalidZoneFallsBackToSystemDefault() {
        // 잘못된 zone id=시스템 기본 폴백(매 틱 DateTimeException 전파→스캔 중단 방지, P3-1)
        assertThat(OffPeakWindow.zone("Not/AZone")).isEqualTo(ZoneId.systemDefault());
    }

    private static Instant at(String hhmm) {
        return Instant.parse("2026-06-26T" + hhmm + ":00Z");
    }
}
