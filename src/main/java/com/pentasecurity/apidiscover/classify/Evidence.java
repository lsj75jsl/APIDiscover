// 매칭된 관찰 d 의 누적 메트릭 — Zombie severity 입력 (doc/16 §2, §4)
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import java.time.Instant;

/**
 * spec 키 1개에 매칭된 관찰 endpoint 들의 누적. host-agnostic spec 은 여러 host 의 d 가 한 키에 합산된다(doc/16 §4).
 * 가변 누적기(package-private).
 */
final class Evidence {

    long hits;
    long success2xx;
    long total;
    Instant firstSeen;
    Instant lastSeen;

    void add(DiscoveredEndpoint.Metrics m) {
        if (m == null) {
            return;
        }
        hits += m.hits();
        if (m.statusDist() != null) {
            success2xx += m.statusDist().getOrDefault("2xx", 0L);
            total += m.statusDist().values().stream().mapToLong(Long::longValue).sum();
        }
        if (m.firstSeen() != null && (firstSeen == null || m.firstSeen().isBefore(firstSeen))) {
            firstSeen = m.firstSeen();
        }
        if (m.lastSeen() != null && (lastSeen == null || m.lastSeen().isAfter(lastSeen))) {
            lastSeen = m.lastSeen();
        }
    }
}
