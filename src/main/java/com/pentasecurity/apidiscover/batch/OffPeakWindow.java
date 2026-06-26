// off-peak 시간대 판정 순수함수 — "HH:mm-HH:mm" 윈도우 파싱·자정 wrap (doc/33 §5, D48)
package com.pentasecurity.apidiscover.batch;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * off-peak 백필 시간대 판정 (doc/33 §5, D48). off-peak 는 due 술어를 바꾸지 않고 K·윈도우만 스위치하므로
 * 판정만 순수함수로 분리한다(테스트 용이). 미설정/파싱 실패=항상 peak(false, 안전쪽·무회귀).
 */
public final class OffPeakWindow {

    private static final Logger log = LoggerFactory.getLogger(OffPeakWindow.class);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private OffPeakWindow() {
    }

    /**
     * now(zone 의 로컬시각) 가 off-peak 윈도우("01:00-06:00") 안인가.
     * null/blank/파싱실패=peak(false). 자정 wrap(start&gt;end, 예 "22:00-06:00")=t&gt;=start || t&lt;end.
     */
    public static boolean isOffPeak(Instant now, String window, ZoneId zone) {
        if (window == null || window.isBlank()) {
            return false; // 미설정=항상 peak(무회귀)
        }
        int dash = window.indexOf('-');
        if (dash < 0) {
            log.warn("invalid off-peak-window '{}' (expected HH:mm-HH:mm) — treating as peak", window);
            return false;
        }
        try {
            LocalTime start = LocalTime.parse(window.substring(0, dash).trim(), HHMM);
            LocalTime end = LocalTime.parse(window.substring(dash + 1).trim(), HHMM);
            LocalTime t = LocalTime.ofInstant(now, zone);
            if (start.compareTo(end) <= 0) {
                return !t.isBefore(start) && t.isBefore(end);   // start<=t<end
            }
            return !t.isBefore(start) || t.isBefore(end);       // 자정 wrap
        } catch (DateTimeParseException e) {
            log.warn("invalid off-peak-window '{}' — treating as peak", window, e);
            return false;
        }
    }

    /** off-peak-zone 빈값=시스템 기본. 잘못된 zone id=시스템 기본 폴백+warn(isOffPeak·ScanTier 폴백과 일관, 매 틱 전파 방지). */
    public static ZoneId zone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            log.warn("invalid off-peak-zone '{}' — using system default", zoneId, e);
            return ZoneId.systemDefault();
        }
    }
}
