// 대상 제외 엣지 매처(D62·D69) — 정확 일치 + 'X*' 접두 와일드카드("P*"=P 로 시작하는 전 엣지)
package com.pentasecurity.apidiscover.batch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code discovery.excluded-hostnames} 항목 매처. 항목이 {@code *} 로 끝나면 접두 일치(D69, 예: "P*"),
 * 아니면 정확 일치(D62 AAJ 목록). 신규 엣지가 나중에 등록돼도 접두 규칙이 자동 커버(목록 노후화 방지).
 * discovery 등록·출석(lastSeen)·스캔 조회 제외 판정 공용 — D62 의 소프트 제외 의미 그대로.
 */
final class EdgeExclusions {

    private final Set<String> exact = new HashSet<>();
    private final List<String> prefixes = new ArrayList<>();

    EdgeExclusions(List<String> entries) {
        for (String e : (entries != null) ? entries : List.<String>of()) {
            if (e == null || e.isBlank()) {
                continue;
            }
            if (e.endsWith("*")) {
                prefixes.add(e.substring(0, e.length() - 1));
            } else {
                exact.add(e);
            }
        }
    }

    boolean contains(String hostname) {
        if (hostname == null) {
            return false;
        }
        if (exact.contains(hostname)) {
            return true;
        }
        for (String p : prefixes) {
            if (hostname.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
