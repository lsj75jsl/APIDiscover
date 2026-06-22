// ApiScorer 점수/프로파일 단위 테스트 (doc/08, 보정 반영)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.MatcherConfig;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.time.Instant;
import java.util.List;
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

    // --- explicit-hint 매처 (doc/09) ---

    private static ApiHintMatcher hints(List<String> apiPrefixes, List<String> excludePrefixes,
                                        boolean includeWebForms) {
        return new ApiHintMatcher(new MatcherConfig(
                apiPrefixes, List.of(), excludePrefixes, List.of(), includeWebForms));
    }

    @Test
    void explicitHintModeDisablesBuiltinPathShape() {
        // www /api/v1/things: pathless 모드면 api_seg(0.55)+version(0.26)=0.81
        var d = de("www.example.com", "GET", "/api/v1/things", EndpointKind.UNKNOWN, 2, false, false);
        assertThat(middle.score(d, false)).isEqualTo(0.81);
        // explicit-hint 모드(비매칭 힌트) → 내장 path-shape 비활성 + pathHint 미가산 → 0.0 (이중계상 없음)
        assertThat(middle.score(d, false, hints(List.of("/zzz"), List.of(), false))).isEqualTo(0.0);
    }

    @Test
    void explicitHintForceAdmitsBelowThreshold() {
        // www /svc/data: score=pathHint 0.55 < 0.70 이지만 힌트 매치 → 임계 우회 ADMIT
        var d = de("www.example.com", "GET", "/svc/data", EndpointKind.UNKNOWN, 2, false, false);
        var matched = hints(List.of("/svc"), List.of(), false);
        assertThat(middle.score(d, false, matched)).isEqualTo(0.55);
        assertThat(middle.evaluate(d, false, matched)).isEqualTo(ApiScorer.Gate.ADMIT);
    }

    @Test
    void excludeForceDropsHighScore() {
        // api.* + cors + api_seg + write → 고득점(1.0)이지만 exclude 매치 → DROP_EXCLUDED
        var d = de("api.example.com", "POST", "/api/admin", EndpointKind.UNKNOWN, 100, true, true);
        var excl = hints(List.of(), List.of("/api/admin"), false);
        assertThat(middle.score(d, true, excl)).isEqualTo(1.0);
        assertThat(middle.evaluate(d, true, excl)).isEqualTo(ApiScorer.Gate.DROP_EXCLUDED);
    }

    @Test
    void excludeBeatsHintWhenBothMatch() {
        var d = de("www.example.com", "GET", "/x/y", EndpointKind.UNKNOWN, 2, false, false);
        var both = hints(List.of("/x"), List.of("/x"), false);
        assertThat(middle.evaluate(d, false, both)).isEqualTo(ApiScorer.Gate.DROP_EXCLUDED);
    }

    @Test
    void webFormPostDroppedWithoutStrongSignalButGetIsNot() {
        var matcher = hints(List.of(), List.of(), false); // includeWebForms=false
        var post = de("www.example.com", "POST", "/account/update", EndpointKind.WEB_PAGE, 100, false, false);
        assertThat(middle.evaluate(post, false, matcher)).isEqualTo(ApiScorer.Gate.DROP_WEB_FORM);
        // GET 은 web-form 대상 아님 → score 게이트로(저득점) → DROP_LOW_SCORE (DROP_WEB_FORM 아님)
        var get = de("www.example.com", "GET", "/account/update", EndpointKind.WEB_PAGE, 100, false, false);
        assertThat(middle.evaluate(get, false, matcher)).isEqualTo(ApiScorer.Gate.DROP_LOW_SCORE);
    }

    @Test
    void webFormPostAdmittedWithCorsOverride() {
        var matcher = hints(List.of(), List.of(), false);
        var post = de("www.example.com", "POST", "/account/update", EndpointKind.WEB_PAGE, 100, false, false);
        // cors 강신호 → web-form drop override → score 게이트(cors 0.30+write 0.34+repeat 0.12=0.76) → ADMIT
        assertThat(middle.evaluate(post, true, matcher)).isEqualTo(ApiScorer.Gate.ADMIT);
    }

    @Test
    void webFormPostGoesToScoreGateWhenIncludeWebFormsTrue() {
        var matcher = hints(List.of(), List.of(), true); // includeWebForms=true → drop 미적용
        var post = de("www.example.com", "POST", "/account/update", EndpointKind.WEB_PAGE, 100, false, false);
        // write 0.34 + repeat 0.12 = 0.46 < 0.70 → DROP_LOW_SCORE (DROP_WEB_FORM 아님)
        assertThat(middle.evaluate(post, false, matcher)).isEqualTo(ApiScorer.Gate.DROP_LOW_SCORE);
    }
}
