// ApiScorer 점수/프로파일 단위 테스트 (doc/08, 보정 반영)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.MatcherConfig;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.SignalContribution;
import com.pentasecurity.apidiscover.model.TemplateSource;
import com.pentasecurity.apidiscover.normalize.EndpointKindClassifier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiScorerTest {

    private final ApiScorer middle = new ApiScorer(); // MIDDLE

    // ★정적 리소스 파일명 토큰(activeNameTokens)은 volatile 정적 상태 — Spring 컨텍스트 테스트가 바꿀 수 있어 기본값 리셋(D56).
    @BeforeEach
    void resetStaticRules() {
        EndpointKindClassifier.applyRules(
                EndpointKindClassifier.DEFAULT_STATIC_EXT, EndpointKindClassifier.DEFAULT_NAME_TOKENS);
    }

    private static DiscoveredEndpoint de(String host, String method, String tmpl,
                                         EndpointKind kind, long hits, boolean query, boolean sdk) {
        var m = new DiscoveredEndpoint.Metrics(
                hits, Instant.EPOCH, Instant.EPOCH, Map.of("2xx", hits), 5, 10, 50);
        return new DiscoveredEndpoint(method + " " + host + " " + tmpl, method, host, tmpl,
                TemplateSource.INFERRED, kind, 0.0, query, sdk, m, ParamCandidates.EMPTY);
    }

    /** 8.3 요청측 4신호 세팅 DiscoveredEndpoint (host=비-API, GET, hits 100 → repeat 발화). */
    private static DiscoveredEndpoint de83(String tmpl, boolean accept, boolean xrw,
                                           boolean origin, boolean auth) {
        var m = new DiscoveredEndpoint.Metrics(100, Instant.EPOCH, Instant.EPOCH, Map.of("2xx", 100L), 5, 10, 50);
        return new DiscoveredEndpoint("GET www.example.com " + tmpl, "GET", "www.example.com", tmpl,
                TemplateSource.INFERRED, EndpointKind.UNKNOWN, 0.0, false, false,
                accept, xrw, origin, auth, m, ParamCandidates.EMPTY);
    }

    @Test
    void apiSubdomainWithCorsIsCandidate() {
        // api.weble.net /users/{id} 케이스: host_api + cors + id + repeat
        var d = de("api.example.com", "GET", "/users/{id}", EndpointKind.UNKNOWN, 100, false, false);
        assertThat(middle.score(d, true)).isGreaterThanOrEqualTo(0.70);
        assertThat(middle.isApiCandidate(d, true)).isTrue();
    }

    @Test
    void oversizePathTemplateIsHardVetoed() {
        // D68: 초장문 경로(>2,048자, 공격 페이로드/블롭)는 힌트·점수 무관 최우선 비-API —
        // discovered_endpoint unique btree 인덱스 행 한계(압축 후 ~2.7KB) 이하만 persist 가능.
        var d = de("api.example.com", "GET", "/blog/" + "x".repeat(ApiScorer.MAX_PATH_TEMPLATE_CHARS),
                EndpointKind.UNKNOWN, 100, true, true);
        assertThat(middle.evaluate(d, true, ApiHintMatcher.NONE)).isEqualTo(ApiScorer.Gate.DROP_OVERSIZE);
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

    // --- 정적 파일 하드 veto + 정적 리소스 파일명 감점 (D55, 사용자 요구) ---

    @Test
    void staticFileIsHardVetoedRegardlessOfApiKeywordOrScore() {
        // /api/v2/app.js : api_seg + version + api host + cors → 고득점이지만 STATIC → 무조건 DROP_STATIC
        var d = de("api.example.com", "GET", "/api/v2/app.js", EndpointKind.STATIC, 100, true, true);
        assertThat(middle.evaluate(d, true, ApiHintMatcher.NONE)).isEqualTo(ApiScorer.Gate.DROP_STATIC);
        assertThat(middle.isApiCandidate(d, true)).isFalse(); // api 키워드·강신호 있어도 비-API
    }

    @Test
    void staticResourceFilenamePenalisesImgPhpButNotRealApi() {
        // /api/blogwidget/img.php (WEB_PAGE): api_seg 0.55 + query 0.12 + repeat 0.12 = 0.79 이지만
        // 파일명 'img' → staticAssetPenalty -0.60 → 0.19 < 0.70 → 비-API (img.php 오탐 해소)
        var img = de("www.example.com", "GET", "/api/blogwidget/img.php", EndpointKind.WEB_PAGE, 100, true, false);
        assertThat(middle.score(img, false)).isLessThan(0.70);
        assertThat(middle.isApiCandidate(img, false)).isFalse();

        // 대조: 같은 모양이나 정적 파일명 아님(.php=실 API 가능) → 감점 없어 API 후보 = 감점이 결정적
        var api = de("www.example.com", "GET", "/api/blogwidget/list.php", EndpointKind.WEB_PAGE, 100, true, false);
        assertThat(middle.isApiCandidate(api, false)).isTrue();
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
                TemplateSource.INFERRED, EndpointKind.UNKNOWN, 0.0, false, false, m, ParamCandidates.EMPTY);
        assertThatCode(() -> middle.score(nullHost, false)).doesNotThrowAnyException();

        // template=null — segments() 가 빈 배열로 안전 처리
        var nullTemplate = new DiscoveredEndpoint("GET api.example.com null", "GET", "api.example.com", null,
                TemplateSource.INFERRED, EndpointKind.UNKNOWN, 0.0, false, false, m, ParamCandidates.EMPTY);
        assertThatCode(() -> middle.score(nullTemplate, false)).doesNotThrowAnyException();

        // metrics=null — repeat 신호 분기에서 null 가드
        var nullMetrics = new DiscoveredEndpoint("GET api.example.com /x", "GET", "api.example.com", "/x",
                TemplateSource.INFERRED, EndpointKind.UNKNOWN, 0.0, false, false, null, ParamCandidates.EMPTY);
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

    // --- response_type_api 신호 (doc/17 §2) — API_CANDIDATE 양성-only ---

    @Test
    void apiCandidateAddsResponseTypeApiWeight() {
        // 동일 endpoint 에서 kind 만 다름 → 차이 = responseTypeApi(MIDDLE 0.25)
        var base = de("www.example.com", "GET", "/x", EndpointKind.UNKNOWN, 2, false, false);
        assertThat(middle.score(base, false)).isEqualTo(0.0);  // $type 부재(UNKNOWN) → 무가산 베이스라인
        var api = de("www.example.com", "GET", "/x", EndpointKind.API_CANDIDATE, 2, false, false);
        assertThat(middle.score(api, false)).isEqualTo(0.25);  // API_CANDIDATE → +0.25
    }

    @Test
    void webPageAndAbsentTypeGetNoResponseTypeApiBoost() {
        // 비대칭: document(WEB_PAGE)·$type 부재(UNKNOWN) 무가산·무감점 (doc/17 §2)
        var web = de("www.example.com", "GET", "/x", EndpointKind.WEB_PAGE, 2, false, false);
        assertThat(middle.score(web, false)).isEqualTo(0.0);
        var unknown = de("www.example.com", "GET", "/x", EndpointKind.UNKNOWN, 2, false, false);
        assertThat(middle.score(unknown, false)).isEqualTo(0.0);
    }

    @Test
    void staticAppliesPenaltyOnlyNotResponseTypeApi() {
        // STATIC ⊕ API_CANDIDATE 상호배타 → STATIC 은 penalty 만, responseTypeApi 미발화
        var d = de("api.example.com", "GET", "/lib/app.js", EndpointKind.STATIC, 100, false, false);
        // host_api 0.40 + cors 0.30 + repeat 0.12 + static -0.60 = 0.22 (responseTypeApi 0.25 미가산)
        assertThat(middle.score(d, true)).isEqualTo(0.22);
    }

    @Test
    void customResponseTypeApiWeightIsAcceptedAndApplied() {
        var w = ApiScorer.applyOverrides(
                ApiScorer.presetWeights(ApiScorer.Profile.MIDDLE), Map.of("responseTypeApi", 0.5), null);
        assertThat(w.responseTypeApi()).isEqualTo(0.5);
        var scorer = new ApiScorer(w);
        var api = de("www.example.com", "GET", "/x", EndpointKind.API_CANDIDATE, 2, false, false);
        assertThat(scorer.score(api, false)).isEqualTo(0.5); // override 반영(베이스 0 + 0.5)
    }

    @Test
    void explicitHintAndApiCandidateCofireAsIndependentEvidence() {
        // P3-1: explicit-hint 모드에서 api 힌트(pathHint) + API_CANDIDATE(responseTypeApi)는 독립 증거로 함께 가산
        var matched = hints(List.of("/svc"), List.of(), false);
        var unmatched = hints(List.of("/zzz"), List.of(), false);
        var apiKind = de("www.example.com", "GET", "/svc/data", EndpointKind.API_CANDIDATE, 2, false, false);
        var nonApiKind = de("www.example.com", "GET", "/svc/data", EndpointKind.UNKNOWN, 2, false, false);

        double pathHintAlone = middle.score(nonApiKind, false, matched);    // 힌트 매치 + 비-API kind
        double responseTypeAlone = middle.score(apiKind, false, unmatched); // 힌트 비매치(pathHint 미발화) + API_CANDIDATE
        double both = middle.score(apiKind, false, matched);                // 두 신호 동시

        assertThat(pathHintAlone).isEqualTo(0.55);    // pathHint(MIDDLE)
        assertThat(responseTypeAlone).isEqualTo(0.25); // responseTypeApi(MIDDLE)
        assertThat(both).isEqualTo(0.80);             // 합산(이중계상 아님, 독립 가산)
        assertThat(both).isEqualTo(pathHintAlone + responseTypeAlone);
        assertThat(both).isGreaterThan(pathHintAlone).isGreaterThan(responseTypeAlone); // 각 단독보다 큼
    }

    @Test
    void responseTypeApiUsesPresetWeightForHighAndLow() {
        // P3-2: HIGH(0.18)·LOW(0.32) preset 에서 API_CANDIDATE 가산 = 정확히 preset 값(UNKNOWN 베이스라인 대비)
        var high = new ApiScorer(ApiScorer.Profile.HIGH);
        var low = new ApiScorer(ApiScorer.Profile.LOW);
        // hits=1: HIGH(repeatMin 5)·LOW(repeatMin 2) 모두 repeat 미발화 → 베이스라인 0.0
        var base = de("www.example.com", "GET", "/x", EndpointKind.UNKNOWN, 1, false, false);
        var api = de("www.example.com", "GET", "/x", EndpointKind.API_CANDIDATE, 1, false, false);
        assertThat(high.score(base, false)).isEqualTo(0.0);
        assertThat(high.score(api, false)).isEqualTo(0.18); // HIGH responseTypeApi
        assertThat(low.score(base, false)).isEqualTo(0.0);
        assertThat(low.score(api, false)).isEqualTo(0.32);  // LOW responseTypeApi
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

    // --- Weights ctor / presetWeights / applyOverrides (doc/10 §4) ---

    @Test
    void presetWeightsMatchProfiles() {
        assertThat(ApiScorer.presetWeights(ApiScorer.Profile.HIGH).threshold()).isEqualTo(0.85);
        assertThat(ApiScorer.presetWeights(ApiScorer.Profile.MIDDLE).threshold()).isEqualTo(0.70);
        assertThat(ApiScorer.presetWeights(ApiScorer.Profile.LOW).threshold()).isEqualTo(0.55);
        assertThat(ApiScorer.presetWeights(ApiScorer.Profile.HIGH).hostApiSubdomain()).isEqualTo(0.35);
    }

    @Test
    void weightsConstructorAndAccessor() {
        var w = ApiScorer.presetWeights(ApiScorer.Profile.LOW);
        var scorer = new ApiScorer(w);
        assertThat(scorer.weights()).isEqualTo(w);
        assertThat(scorer.threshold()).isEqualTo(0.55);
    }

    @Test
    void applyOverridesReplacesOnlyGivenKeys() {
        var base = ApiScorer.presetWeights(ApiScorer.Profile.MIDDLE);
        var out = ApiScorer.applyOverrides(base, Map.of("apiSegment", 0.9, "pathHint", 0.33), null);
        assertThat(out.apiSegment()).isEqualTo(0.9);     // override
        assertThat(out.pathHint()).isEqualTo(0.33);      // override
        assertThat(out.corsPreflight()).isEqualTo(0.30); // 미지정 → MIDDLE
        assertThat(out.threshold()).isEqualTo(0.70);     // thresholdOverride null → base
        assertThat(out.repeatMinCount()).isEqualTo(3);   // base 유지(override 범위 밖)
    }

    @Test
    void applyOverridesThresholdOnlyKeepsWeights() {
        var base = ApiScorer.presetWeights(ApiScorer.Profile.MIDDLE);
        var out = ApiScorer.applyOverrides(base, Map.of(), 0.42);
        assertThat(out.threshold()).isEqualTo(0.42);
        assertThat(out.apiSegment()).isEqualTo(0.55); // weights 불변
    }

    @Test
    void applyOverridesRejectsUnknownKey() {
        var base = ApiScorer.presetWeights(ApiScorer.Profile.MIDDLE);
        // 오타 키 → 조용한 무시 금지, fail-fast (P2-1)
        assertThatThrownBy(() -> ApiScorer.applyOverrides(base, Map.of("apiSegmnet", 0.5), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiSegmnet");
    }

    @Test
    void applyOverridesRejectsNonFiniteWeight() {
        var base = ApiScorer.presetWeights(ApiScorer.Profile.MIDDLE);
        assertThatThrownBy(() -> ApiScorer.applyOverrides(base, Map.of("apiSegment", Double.NaN), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiScorer.applyOverrides(base, Map.of("query", Double.POSITIVE_INFINITY), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyOverridesRejectsThresholdOutOfRange() {
        var base = ApiScorer.presetWeights(ApiScorer.Profile.MIDDLE);
        assertThatThrownBy(() -> ApiScorer.applyOverrides(base, Map.of(), -1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiScorer.applyOverrides(base, Map.of(), 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 8.3 요청측 4신호 (doc/40 §3·§6) — 양성 가산·부재 0·override·프리셋 ---

    @Test
    void newRequestSignalsArePositiveAdditive() {
        // base(pathId 0.15 + repeat 0.12 = 0.27) 에 각 신호가 정확히 가중치만큼 가산(MIDDLE).
        assertThat(middle.score(de83("/users/{id}", false, false, false, false), false)).isEqualTo(0.27);
        assertThat(middle.score(de83("/users/{id}", true, false, false, false), false)).isEqualTo(0.47);  // +accept .20
        assertThat(middle.score(de83("/users/{id}", false, true, false, false), false)).isEqualTo(0.55);  // +xhr .28
        assertThat(middle.score(de83("/users/{id}", false, false, true, false), false)).isEqualTo(0.42);  // +origin .15
        assertThat(middle.score(de83("/users/{id}", false, false, false, true), false)).isEqualTo(0.55);  // +auth .28
        // 전 4신호: 0.27 + 0.91 = 1.18 → clamp 1.0
        assertThat(middle.score(de83("/users/{id}", true, true, true, true), false)).isEqualTo(1.0);
    }

    @Test
    void newRequestSignalsAbsentContributeZero() {
        // 부재(전부 false) = 8.3 미설정 엔드포인트(11-arg 생성자)와 동일 점수(무회귀 핵심).
        var no83 = de("www.example.com", "GET", "/users/{id}", EndpointKind.UNKNOWN, 100, false, false);
        assertThat(middle.score(de83("/users/{id}", false, false, false, false), false))
                .isEqualTo(middle.score(no83, false));
    }

    @Test
    void newSignalPromotesApiStructuredBoundaryToAdmit() {
        // §5 시뮬 핵심: 이미 API 구조(apiSegment 0.55 + repeat 0.12 = 0.67) 경계가 auth(0.28) 로 0.70 넘어 ADMIT.
        var boundary = de83("/api/orders", false, false, false, true);
        assertThat(middle.score(boundary, false)).isGreaterThanOrEqualTo(0.70);
        assertThat(middle.isApiCandidate(boundary, false)).isTrue();
    }

    @Test
    void newSignalWeightsInPresetsAndKeys() {
        assertThat(ApiScorer.WEIGHT_KEYS).hasSize(18)
                .contains("acceptJson", "xRequestedWith", "originHeader", "authScheme");
        var mid = ApiScorer.presetWeights(ApiScorer.Profile.MIDDLE);
        assertThat(mid.acceptJson()).isEqualTo(0.20);
        assertThat(mid.xRequestedWith()).isEqualTo(0.28);
        assertThat(mid.originHeader()).isEqualTo(0.15);
        assertThat(mid.authScheme()).isEqualTo(0.28);
        var high = ApiScorer.presetWeights(ApiScorer.Profile.HIGH);
        assertThat(high.xRequestedWith()).isEqualTo(0.22);
        var low = ApiScorer.presetWeights(ApiScorer.Profile.LOW);
        assertThat(low.authScheme()).isEqualTo(0.34);
        assertThat(ApiScorer.weightsAsMap(mid))
                .containsKeys("acceptJson", "xRequestedWith", "originHeader", "authScheme");
    }

    @Test
    void applyOverridesAcceptsNewSignalKeys() {
        var base = ApiScorer.presetWeights(ApiScorer.Profile.MIDDLE);
        var out = ApiScorer.applyOverrides(base, Map.of("authScheme", 0.5, "acceptJson", 0.05), null);
        assertThat(out.authScheme()).isEqualTo(0.5);
        assertThat(out.acceptJson()).isEqualTo(0.05);
        assertThat(out.xRequestedWith()).isEqualTo(base.xRequestedWith()); // 미지정 유지
    }

    // --- scoreExplain 판단 근거 노출 (doc/34 §3) ---

    @Test
    void scoreExplainTotalEqualsScoreAndSumsContributions() {
        // score() 는 scoreExplain().total() 위임 — 동치 + contribution 합 재구성 정합
        var d = de("api.example.com", "POST", "/api/v1/users/{id}", EndpointKind.API_CANDIDATE, 100, true, true);
        var bd = middle.scoreExplain(d, true, ApiHintMatcher.NONE);
        assertThat(bd.total()).isEqualTo(middle.score(d, true));
        double raw = bd.signals().stream().mapToDouble(SignalContribution::contribution).sum();
        assertThat(Math.max(0.0, Math.min(1.0, Math.round(raw * 1000.0) / 1000.0))).isEqualTo(bd.total());
    }

    @Test
    void scoreExplainReportsPerSignalWeightFiredContribution() {
        // api.* host(발화 0.40) + cors(발화 0.30), query/static 미발화(contribution 0.0) — effective weight 그대로 노출
        var d = de("api.example.com", "GET", "/things", EndpointKind.UNKNOWN, 1, false, false);
        var sig = middle.scoreExplain(d, true, ApiHintMatcher.NONE).signals().stream()
                .collect(Collectors.toMap(SignalContribution::key, s -> s));
        assertThat(sig.get("hostApiSubdomain")).isEqualTo(new SignalContribution("hostApiSubdomain", 0.40, true, 0.40));
        assertThat(sig.get("corsPreflight")).isEqualTo(new SignalContribution("corsPreflight", 0.30, true, 0.30));
        assertThat(sig.get("query")).isEqualTo(new SignalContribution("query", 0.12, false, 0.0));
        assertThat(sig.get("staticAssetPenalty")).isEqualTo(new SignalContribution("staticAssetPenalty", -0.60, false, 0.0));
    }

    @Test
    void scoreExplainModeSelectsPathShapeVsPathHint() {
        // pathless 모드는 path-shape 신호 평가(pathHint 없음), explicit-hint 모드는 pathHint 만(path-shape 없음, doc/09 §2.3)
        var d = de("www.example.com", "GET", "/api/v1/things", EndpointKind.UNKNOWN, 2, false, false);
        var pathless = middle.scoreExplain(d, false, ApiHintMatcher.NONE).signals().stream()
                .map(SignalContribution::key).toList();
        assertThat(pathless).contains("apiSegment", "versionSegment").doesNotContain("pathHint");
        var hinted = middle.scoreExplain(d, false, hints(List.of("/api"), List.of(), false)).signals().stream()
                .map(SignalContribution::key).toList();
        assertThat(hinted).contains("pathHint").doesNotContain("apiSegment", "versionSegment");
    }
}
