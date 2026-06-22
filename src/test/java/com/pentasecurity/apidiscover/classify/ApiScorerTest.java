// ApiScorer 점수/프로파일 단위 테스트 (doc/08, 보정 반영)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiScorerTest {

    private final ApiScorer middle = new ApiScorer(); // MIDDLE

    private static DiscoveredEndpoint de(String host, String method, String tmpl,
                                         EndpointKind kind, long hits, boolean query, boolean sdk) {
        var m = new DiscoveredEndpoint.Metrics(
                hits, Instant.EPOCH, Instant.EPOCH, Map.of("2xx", hits), 5, 10, 50);
        return new DiscoveredEndpoint(method + " " + host + " " + tmpl, method, host, tmpl,
                TemplateSource.INFERRED, kind, 0.0, query, sdk, m);
    }

    @Test
    void apiSubdomainWithCorsIsCandidate() {
        // api.weble.net /users/{id} 케이스: host_api + cors + id + repeat
        var d = de("api.example.com", "GET", "/users/{id}", EndpointKind.UNKNOWN, 100, false, false);
        assertThat(middle.score(d, true)).isGreaterThanOrEqualTo(0.70);
        assertThat(middle.isApiCandidate(d, true)).isTrue();
    }

    @Test
    void webPageOnNonApiHostIsNotCandidate() {
        // dreampark /m03/{id} 케이스: id + repeat 만 → 0.27
        var d = de("www.example.com", "GET", "/m03/{id}", EndpointKind.UNKNOWN, 100, false, false);
        assertThat(middle.score(d, false)).isLessThan(0.70);
        assertThat(middle.isApiCandidate(d, false)).isFalse();
    }

    @Test
    void staticIsHeavilyPenalised() {
        var d = de("api.example.com", "GET", "/lib/app.js", EndpointKind.STATIC, 200, false, false);
        assertThat(middle.score(d, false)).isLessThan(0.70);
    }

    @Test
    void apiSegmentWithWriteMethodIsCandidate() {
        var d = de("www.example.com", "POST", "/api/orders", EndpointKind.UNKNOWN, 100, false, false);
        assertThat(middle.isApiCandidate(d, false)).isTrue();
    }

    @Test
    void corsPreflightLiftsBorderlineEndpoint() {
        var d = de("api.example.com", "GET", "/things", EndpointKind.UNKNOWN, 100, false, false);
        // host_api 0.40 + repeat 0.12 = 0.52 (미달) → cors +0.30 = 0.82 (통과)
        assertThat(middle.isApiCandidate(d, false)).isFalse();
        assertThat(middle.isApiCandidate(d, true)).isTrue();
    }

    @Test
    void profileChangesDecision() {
        // www /api/things: api_seg + repeat. MIDDLE 0.67<0.70(탈락), LOW 임계 0.55(통과)
        var d = de("www.example.com", "GET", "/api/things", EndpointKind.UNKNOWN, 100, false, false);
        assertThat(new ApiScorer(ApiScorer.Profile.MIDDLE).isApiCandidate(d, false)).isFalse();
        assertThat(new ApiScorer(ApiScorer.Profile.LOW).isApiCandidate(d, false)).isTrue();
        assertThat(new ApiScorer(ApiScorer.Profile.HIGH).threshold()).isEqualTo(0.85);
    }

    @Test
    void scoreHandlesNullFieldsSafely() {
        // host=null — LogLineParser 가 host 미존재 시 null 을 넘길 수 있음(F_HOST/F_REAL_HOST 모두 '-')
        var m = new DiscoveredEndpoint.Metrics(10, Instant.EPOCH, Instant.EPOCH, Map.of("2xx", 10L), 1, 1, 1);
        var nullHost = new DiscoveredEndpoint("GET null /users/{id}", "GET", null, "/users/{id}",
                TemplateSource.INFERRED, EndpointKind.UNKNOWN, 0.0, false, false, m);
        assertThatCode(() -> middle.score(nullHost, false)).doesNotThrowAnyException();

        // template=null — segments() 가 빈 배열로 안전 처리
        var nullTemplate = new DiscoveredEndpoint("GET api.example.com null", "GET", "api.example.com", null,
                TemplateSource.INFERRED, EndpointKind.UNKNOWN, 0.0, false, false, m);
        assertThatCode(() -> middle.score(nullTemplate, false)).doesNotThrowAnyException();

        // metrics=null — repeat 신호 분기에서 null 가드
        var nullMetrics = new DiscoveredEndpoint("GET api.example.com /x", "GET", "api.example.com", "/x",
                TemplateSource.INFERRED, EndpointKind.UNKNOWN, 0.0, false, false, null);
        assertThatCode(() -> middle.score(nullMetrics, false)).doesNotThrowAnyException();
    }

    @Test
    void scoreClampsToUpperBound() {
        // 모든 양성 신호 + CORS → 가산합이 1.0 을 넘어 상한 clamp
        var d = de("api.example.com", "POST", "/api/v1/users/{id}", EndpointKind.API_CANDIDATE, 100, true, true);
        assertThat(middle.score(d, true)).isEqualTo(1.0);
    }

    @Test
    void scoreClampsToLowerBound() {
        // static penalty 만(양성 신호 없음, repeat 미달) → 음수합이 하한 clamp
        var d = de("www.example.com", "GET", "/lib/app.js", EndpointKind.STATIC, 2, false, false);
        assertThat(middle.score(d, false)).isEqualTo(0.0);
    }

    @Test
    void individualSignalsContributeTheirWeight() {
        // 중립 베이스라인: www host + 무신호 경로 + repeat 미달(hits<3) → 0.0
        var base = de("www.example.com", "GET", "/x", EndpointKind.UNKNOWN, 2, false, false);
        assertThat(middle.score(base, false)).isEqualTo(0.0);

        // query 신호 (MIDDLE 0.12)
        assertThat(middle.score(
                de("www.example.com", "GET", "/x", EndpointKind.UNKNOWN, 2, true, false), false))
                .isEqualTo(0.12);
        // non_browser_ua(SDK) 신호 (MIDDLE 0.24)
        assertThat(middle.score(
                de("www.example.com", "GET", "/x", EndpointKind.UNKNOWN, 2, false, true), false))
                .isEqualTo(0.24);
        // version segment v\d+ (MIDDLE 0.26)
        assertThat(middle.score(
                de("www.example.com", "GET", "/v1", EndpointKind.UNKNOWN, 2, false, false), false))
                .isEqualTo(0.26);
        // graphql segment (MIDDLE 0.55)
        assertThat(middle.score(
                de("www.example.com", "GET", "/graphql", EndpointKind.UNKNOWN, 2, false, false), false))
                .isEqualTo(0.55);
        // machine endpoint (MIDDLE 0.20)
        assertThat(middle.score(
                de("www.example.com", "GET", "/health", EndpointKind.UNKNOWN, 2, false, false), false))
                .isEqualTo(0.20);
    }
}
