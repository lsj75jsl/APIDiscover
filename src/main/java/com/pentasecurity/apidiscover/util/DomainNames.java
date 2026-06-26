// 도메인 정규화 단일 진실원 — discovery 등록·스캔 foreign-host 필터 공통(trim+lowercase, 빈/"-"→null) (doc/05 §2.2, doc/30)
package com.pentasecurity.apidiscover.util;

import java.util.Locale;

public final class DomainNames {

    private DomainNames() {
    }

    /**
     * raw host → trim + lowercase(ROOT). null·빈문자·"-" → null.
     * ★discovery 등록(DomainDiscoveryService)과 스캔 foreign-host 필터(DiscoveryJobService.analyze)가 반드시 동일 규칙을
     * 써야 한다 — 다르면 정상 도메인도 필터돼 결과 0(무회귀 깨짐).
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return (t.isEmpty() || "-".equals(t)) ? null : t;
    }
}
