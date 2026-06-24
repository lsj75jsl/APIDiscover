// spec 매칭 endpoint 의 누적 관측 구간 — cross-scan 이력 직렬화 단위 (doc/24 §3)
package com.pentasecurity.apidiscover.model;

import java.time.Instant;

/** 이력상 최초 관측(firstSeen)·최신 관측(lastSeen). EndpointHistory historyJson 의 Map 값(Jackson 왕복). */
public record EndpointObservation(Instant firstSeen, Instant lastSeen) {
}
