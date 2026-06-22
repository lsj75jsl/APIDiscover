// PathNormalizer 휴리스틱 추론 단위 테스트 (doc/02 §3.2)
package com.pentasecurity.apidiscover.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PathNormalizerTest {

    private final PathNormalizer normalizer = new PathNormalizer();

    @Test
    void numericSegmentBecomesId() {
        assertThat(normalizer.inferTemplate("/users/12345")).isEqualTo("/users/{id}");
    }

    @Test
    void uuidSegmentBecomesUuid() {
        assertThat(normalizer.inferTemplate("/orders/550e8400-e29b-41d4-a716-446655440000/items"))
                .isEqualTo("/orders/{uuid}/items");
    }

    @Test
    void dateSegmentBecomesDate() {
        assertThat(normalizer.inferTemplate("/reports/2026-06-22")).isEqualTo("/reports/{date}");
    }

    @Test
    void staticSegmentIsKept() {
        assertThat(normalizer.inferTemplate("/users/me")).isEqualTo("/users/me");
    }

    @Test
    void trailingSlashIsStripped() {
        assertThat(normalizer.inferTemplate("/users/12345/")).isEqualTo("/users/{id}");
    }

    @Test
    void rootStaysRoot() {
        assertThat(normalizer.inferTemplate("/")).isEqualTo("/");
    }
}
