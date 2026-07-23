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
    void numericIdWithParenLabelBecomesId() {
        // 앱이 경로에 넣은 사람용 라벨(숫자ID(라벨)) → {id} 로 수렴 (인코딩·비인코딩 라벨 모두)
        assertThat(normalizer.inferTemplate("/campaigns/1337477(%EC%B2%B4%ED%97%98%EB%8B%A8)"))
                .isEqualTo("/campaigns/{id}");
        assertThat(normalizer.inferTemplate("/campaigns/990312(체험단)")).isEqualTo("/campaigns/{id}");
    }

    @Test
    void staticSegmentIsKept() {
        assertThat(normalizer.inferTemplate("/users/me")).isEqualTo("/users/me");
        assertThat(normalizer.inferTemplate("/campaigns/summer(2026)")).isEqualTo("/campaigns/summer(2026)"); // 숫자 시작 아님=유지
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
