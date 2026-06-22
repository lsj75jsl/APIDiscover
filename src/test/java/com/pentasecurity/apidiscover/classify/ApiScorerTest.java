// ApiScorer 점수/프로파일 단위 테스트 (doc/08, 보정 반영)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;

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
}
