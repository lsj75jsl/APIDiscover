// (host, specVersion) 키 EndpointMatcher 캐시 — 매 스캔 재생성 제거 (doc/15, doc/03 §7.5)
package com.pentasecurity.apidiscover.match;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * host당 단일 슬롯 캐시. 키가 (host, specVersion) 라 새 버전이 슬롯을 덮어써 누수 없고, version 필드로 stale 서빙이 구조적으로 불가.
 * 스캔은 항상 active 버전만 조회 → 구버전 matcher 보관 불필요. EndpointMatcher 불변 → 슬롯 공유·동시 read 안전.
 *
 * <p>무효화 주체는 writer(SpecStore.upload), 빌드는 소비자(DiscoveryJobService)가 supplier 로 공급 →
 * 캐시는 스펙을 스스로 로드하지 않아 SpecStore↔캐시 순환 불가(doc/15 §2). HA cross-instance 무효화는 범위 밖.
 */
@Component
public class EndpointMatcherCache {

    private final ConcurrentHashMap<String, VersionedMatcher> cache = new ConcurrentHashMap<>();

    /** host 슬롯에 보관되는 (specVersion, matcher) 쌍. */
    public record VersionedMatcher(long specVersion, EndpointMatcher matcher) {}

    /**
     * 캐시 히트(host 슬롯의 specVersion 일치)면 보관된 matcher, 아니면 build 로 재빌드 후 슬롯 교체.
     * build throw 시 compute 매핑 불변(미저장) → 재시도(poisoning-free).
     */
    public EndpointMatcher get(String host, long specVersion, Supplier<EndpointMatcher> build) {
        // compute() per-host 락으로 동일 host 동시 빌드 직렬화 — 중복 매처 빌드 방지(의도). 타 host 는 병행.
        return cache.compute(host, (h, cur) ->
                (cur != null && cur.specVersion() == specVersion)
                        ? cur
                        : new VersionedMatcher(specVersion, build.get())).matcher();
    }

    /** 업로드(새 버전) 시 구버전 슬롯 해제 (doc/15 §2). 새 버전은 어차피 version-miss 로 재빌드. */
    public void invalidate(String host) {
        cache.remove(host);
    }

    /** 전체 무효화(대칭/테스트용). */
    public void invalidateAll() {
        cache.clear();
    }
}
