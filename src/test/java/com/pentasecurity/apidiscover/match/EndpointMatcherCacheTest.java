// EndpointMatcherCache 단위 테스트 — hit/version miss/invalidate/poisoning-free/v0 (doc/15 §5)
package com.pentasecurity.apidiscover.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class EndpointMatcherCacheTest {

    private final EndpointMatcherCache cache = new EndpointMatcherCache();

    private static EndpointMatcher build() {
        return new EndpointMatcher(List.<CanonicalEndpoint>of());
    }

    @Test
    void hitReturnsSameInstanceAndBuildsOnce() {
        AtomicInteger builds = new AtomicInteger();
        Supplier<EndpointMatcher> sup = () -> {
            builds.incrementAndGet();
            return build();
        };
        EndpointMatcher m1 = cache.get("h", 1L, sup);
        EndpointMatcher m2 = cache.get("h", 1L, sup);

        assertThat(m2).isSameAs(m1);          // 동일 인스턴스
        assertThat(builds.get()).isEqualTo(1); // 히트 → supplier 1회
    }

    @Test
    void versionMissRebuildsAndDoesNotServeStale() {
        EndpointMatcher v1 = cache.get("h", 1L, EndpointMatcherCacheTest::build);
        EndpointMatcher v2 = cache.get("h", 2L, EndpointMatcherCacheTest::build);
        assertThat(v2).isNotSameAs(v1); // 버전 변경 → 재빌드(슬롯 교체)

        // 슬롯이 v2 인 상태에서 v1 재요청 → version mismatch → stale(v2) 서빙 안 함, 재빌드
        EndpointMatcher v1again = cache.get("h", 1L, EndpointMatcherCacheTest::build);
        assertThat(v1again).isNotSameAs(v2);
    }

    @Test
    void invalidateForcesRebuild() {
        EndpointMatcher m1 = cache.get("h", 1L, EndpointMatcherCacheTest::build);
        cache.invalidate("h");
        EndpointMatcher m2 = cache.get("h", 1L, EndpointMatcherCacheTest::build);
        assertThat(m2).isNotSameAs(m1); // 무효화 후 재빌드
    }

    @Test
    void buildFailureIsNotCached() {
        assertThatThrownBy(() -> cache.get("h", 1L, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        // poisoning-free: 미저장 → 재시도 시 정상 빌드
        EndpointMatcher m = cache.get("h", 1L, EndpointMatcherCacheTest::build);
        assertThat(m).isNotNull();
    }

    @Test
    void specVersionZeroCachedUniformly() {
        AtomicInteger builds = new AtomicInteger();
        Supplier<EndpointMatcher> sup = () -> {
            builds.incrementAndGet();
            return build();
        };
        EndpointMatcher m1 = cache.get("h", 0L, sup); // 스펙 없음도 균일 캐시
        EndpointMatcher m2 = cache.get("h", 0L, sup);

        assertThat(m2).isSameAs(m1);
        assertThat(builds.get()).isEqualTo(1);
    }
}
