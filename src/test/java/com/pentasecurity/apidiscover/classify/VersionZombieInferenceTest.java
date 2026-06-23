// VersionZombieInference 단위 테스트 — 버전 페어링·추정 규칙 (doc/16 §1, §5)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VersionZombieInferenceTest {

    private static CanonicalEndpoint ce(String template) {
        return new CanonicalEndpoint("GET", template, "api.example.com", false, null, "ref:" + template);
    }

    private static Set<String> templatesOf(Set<CanonicalEndpoint> set) {
        return set.stream().map(CanonicalEndpoint::pathTemplate).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void olderVersionEstimatedWhenNewerActive() {
        var est = VersionZombieInference.estimate(List.of(ce("/v1/orders/{id}"), ce("/v2/orders/{id}")));
        assertThat(templatesOf(est)).containsExactly("/v1/orders/{id}"); // v2=Vmax → v1 추정
    }

    @Test
    void singleVersionNotEstimated() {
        assertThat(VersionZombieInference.estimate(List.of(ce("/v2/orders/{id}")))).isEmpty();
    }

    @Test
    void nonVersionEndpointsNotEstimated() {
        assertThat(VersionZombieInference.estimate(List.of(ce("/orders/{id}"), ce("/users/{id}")))).isEmpty();
    }

    @Test
    void multipleOlderVersionsAllEstimated() {
        var est = VersionZombieInference.estimate(List.of(
                ce("/v1/orders/{id}"), ce("/v2/orders/{id}"), ce("/v3/orders/{id}")));
        assertThat(templatesOf(est)).containsExactlyInAnyOrder("/v1/orders/{id}", "/v2/orders/{id}"); // v3=Vmax
    }

    @Test
    void versionPositionIsRecognized() {
        // /v1/orders 와 /orders/v1 은 버전 위치가 달라 페어 안 됨. /orders/v1·/orders/v2 만 페어 → /orders/v1 추정
        var est = VersionZombieInference.estimate(List.of(
                ce("/v1/orders/{id}"),      // 단독 그룹(/{V}/orders/{id}) → 미추정
                ce("/orders/v1/{id}"),
                ce("/orders/v2/{id}")));
        assertThat(templatesOf(est)).containsExactly("/orders/v1/{id}");
    }

    @Test
    void versionMatchIsCaseInsensitive() {
        var est = VersionZombieInference.estimate(List.of(ce("/V1/orders/{id}"), ce("/v2/orders/{id}")));
        assertThat(templatesOf(est)).containsExactly("/V1/orders/{id}");
    }

    @Test
    void oversizedVersionNumberFallsBackToNonVersion() {
        // P3-1: 초대형 버전 숫자(int 오버플로) → 비버전 취급(페어링 제외), 예외 없이 미추정
        var est = VersionZombieInference.estimate(List.of(
                ce("/v99999999999999999999/orders/{id}"), ce("/v2/orders/{id}")));
        assertThat(est).isEmpty();
    }

    @Test
    void distinctResourcesKeptSeparate() {
        // /v1/orders·/v2/orders 페어(추정 v1), /v1/users 단독(미추정)
        var est = VersionZombieInference.estimate(List.of(
                ce("/v1/orders/{id}"), ce("/v2/orders/{id}"), ce("/v1/users/{id}")));
        assertThat(templatesOf(est)).containsExactly("/v1/orders/{id}");
    }
}
