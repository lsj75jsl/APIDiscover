// Classifier 2-pass 분류 + ApiScorer 게이트 단위 테스트 (doc/04, doc/08)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.Classification;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.DroppedNonApi;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.MatcherConfig;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassifierTest {

    // 비-api 호스트 (host_api 신호 미발화 → 게이트가 path/method 신호로 결정)
    private static final String HOST = "shop.example.com";

    private final Classifier classifier = new Classifier(new ApiScorer());

    private final List<CanonicalEndpoint> spec = List.of(
            ce("GET", "/v2/users/{id}", false),
            ce("GET", "/v2/orders/{orderId}", true),   // deprecated → 관찰되면 Zombie
            ce("GET", "/v2/legacy", false));           // 관찰 안 됨 → Unused
    private final EndpointMatcher matcher = new EndpointMatcher(spec);

    @Test
    void classifiesActiveZombieUnusedAndGatedShadow() {
        List<DiscoveredEndpoint> discovered = List.of(
                de("GET", "/v2/users/{id}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 100, "2xx", 5),
                de("GET", "/v2/orders/{orderId}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 50, "2xx", 4),
                de("POST", "/api/debug", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "2xx", 5));

        List<Finding> findings = classifier.classify(discovered, spec, matcher);

        assertThat(byClass(findings, Classification.ACTIVE))
                .extracting(Finding::pathTemplate).containsExactly("/v2/users/{id}");
        assertThat(byClass(findings, Classification.ZOMBIE))
                .extracting(Finding::pathTemplate).containsExactly("/v2/orders/{orderId}");
        assertThat(byClass(findings, Classification.UNUSED))
                .extracting(Finding::pathTemplate).containsExactly("/v2/legacy");
        // /api/debug: api_seg + write + repeat → 게이트 통과 → Shadow
        assertThat(byClass(findings, Classification.SHADOW))
                .extracting(Finding::pathTemplate).containsExactly("/api/debug");
    }

    @Test
    void staticBelowGateIsDropped() {
        DiscoveredEndpoint staticJs =
                de("GET", "/theme/app.js", TemplateSource.INFERRED, EndpointKind.STATIC, 200, "2xx", 5);
        List<Finding> findings = classifier.classify(List.of(staticJs), spec, matcher);

        assertThat(byClass(findings, Classification.SHADOW)).isEmpty();
        assertThat(byClass(findings, Classification.UNUSED)).hasSize(2); // users/{id}, legacy
    }

    @Test
    void lowSignalUnmatchedIsDropped() {
        // 웹페이지류 /m03/{id}: id + repeat 만 → 0.27 < 0.70 → 보고 안 함
        DiscoveredEndpoint page =
                de("GET", "/m03/{id}", TemplateSource.INFERRED, EndpointKind.WEB_PAGE, 100, "2xx", 5);
        List<Finding> findings = classifier.classify(List.of(page), spec, matcher);

        assertThat(byClass(findings, Classification.SHADOW)).isEmpty();
    }

    @Test
    void optionsIsCorsSignalNotReported() {
        // OPTIONS 자체는 보고 안 함. 단 sibling GET 에 CORS 신호 전파.
        List<DiscoveredEndpoint> withCors = List.of(
                de("OPTIONS", "/api/widgets", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 10, "2xx", 5),
                de("GET", "/api/widgets", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "2xx", 5));

        List<Finding> findings = classifier.classify(withCors, spec, matcher);

        assertThat(findings).noneMatch(f -> f.method().equals("OPTIONS"));
        // GET /api/widgets: api_seg + repeat (0.67) + cors(0.30) → 통과
        assertThat(byClass(findings, Classification.SHADOW))
                .extracting(Finding::pathTemplate).containsExactly("/api/widgets");

        // CORS sibling 없으면 0.67 < 0.70 → 탈락
        List<Finding> noCors = classifier.classify(
                List.of(de("GET", "/api/widgets", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "2xx", 5)),
                spec, matcher);
        assertThat(byClass(noCors, Classification.SHADOW)).isEmpty();
    }

    @Test
    void shadowConfidenceDropsForFourxxOnly() {
        // api-like 라 게이트는 통과, 4xx-only + 단일클라 + inferred → shadow 신뢰도 0
        DiscoveredEndpoint noisy =
                de("POST", "/api/probe", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "4xx", 1);
        List<Finding> findings = classifier.classify(List.of(noisy), spec, matcher);

        Finding.Shadow shadow = (Finding.Shadow) byClass(findings, Classification.SHADOW).get(0);
        assertThat(shadow.confidence()).isEqualTo(0.0);
    }

    @Test
    void healthyShadowKeepsHighConfidence() {
        DiscoveredEndpoint healthy =
                de("POST", "/api/reports", TemplateSource.SPEC, EndpointKind.UNKNOWN, 500, "2xx", 20);
        List<Finding> findings = classifier.classify(List.of(healthy), spec, matcher);

        Finding.Shadow shadow = (Finding.Shadow) byClass(findings, Classification.SHADOW).get(0);
        assertThat(shadow.confidence()).isEqualTo(1.0);
    }

    // --- explicit-hint 매처 게이트 (doc/09) ---

    private static final ApiHintMatcher NO_WEBFORMS = new ApiHintMatcher(
            new MatcherConfig(List.of(), List.of(), List.of(), List.of(), false));

    @Test
    void specMatchBypassesExcludeAndWebForm() {
        // exclude 가 /v2 를 덮어도 spec 매칭 경로는 권위 우회 → Active (게이트 미진입)
        var hints = new ApiHintMatcher(
                new MatcherConfig(List.of(), List.of(), List.of("/v2"), List.of(), false));
        var observed = de("GET", "/v2/users/{id}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 100, "2xx", 5);
        var findings = classifier.classify(List.of(observed), spec, matcher, hints);
        assertThat(byClass(findings, Classification.ACTIVE))
                .extracting(Finding::pathTemplate).containsExactly("/v2/users/{id}");
    }

    @Test
    void excludedUnmatchedPathNotReported() {
        var hints = new ApiHintMatcher(
                new MatcherConfig(List.of(), List.of(), List.of("/internal"), List.of(), false));
        var d = de("POST", "/internal/debug", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "2xx", 5);
        var findings = classifier.classify(List.of(d), spec, matcher, hints);
        assertThat(byClass(findings, Classification.SHADOW)).isEmpty();
    }

    @Test
    void hintedUnmatchedPathReportedAsShadow() {
        // shop host 저득점 경로지만 api 힌트로 강제 admit → Shadow
        var hints = new ApiHintMatcher(
                new MatcherConfig(List.of("/custom"), List.of(), List.of(), List.of(), false));
        var d = de("GET", "/custom/data", TemplateSource.INFERRED, EndpointKind.WEB_PAGE, 100, "2xx", 5);
        var findings = classifier.classify(List.of(d), spec, matcher, hints);
        assertThat(byClass(findings, Classification.SHADOW))
                .extracting(Finding::pathTemplate).containsExactly("/custom/data");
    }

    @Test
    void webFormPostNotReportedWithoutStrongSignal() {
        var form = de("POST", "/account/save", TemplateSource.INFERRED, EndpointKind.WEB_PAGE, 100, "2xx", 5);
        var findings = classifier.classify(List.of(form), spec, matcher, NO_WEBFORMS);
        assertThat(byClass(findings, Classification.SHADOW)).isEmpty();
    }

    @Test
    void webFormPostReportedWithCorsOverride() {
        // OPTIONS sibling → CORS 강신호 → web-form drop override → Shadow
        var form = de("POST", "/account/save", TemplateSource.INFERRED, EndpointKind.WEB_PAGE, 100, "2xx", 5);
        var cors = de("OPTIONS", "/account/save", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 10, "2xx", 5);
        var findings = classifier.classify(List.of(cors, form), spec, matcher, NO_WEBFORMS);
        assertThat(byClass(findings, Classification.SHADOW))
                .extracting(Finding::pathTemplate).containsExactly("/account/save");
    }

    @Test
    void legacyThreeArgMatchesFourArgWithNone() {
        var d = de("POST", "/api/debug", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "2xx", 5);
        var legacy = classifier.classify(List.of(d), spec, matcher);
        var explicit = classifier.classify(List.of(d), spec, matcher, ApiHintMatcher.NONE);
        assertThat(byClass(legacy, Classification.SHADOW))
                .extracting(Finding::pathTemplate).containsExactly("/api/debug");
        assertThat(explicit).usingRecursiveComparison().isEqualTo(legacy);
    }

    // --- 5-arg 오버로드 (effective scorer 전달, doc/10 §6) ---

    @Test
    void fiveArgUsesPassedScorer() {
        // /api/things on shop host: api_seg(0.55)+repeat(0.12)=0.67 → MIDDLE(0.70) 탈락, LOW(0.55) 통과
        var d = de("GET", "/api/things", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "2xx", 5);

        // 기본 field scorer(MIDDLE) 경유(4-arg) → 미보고
        var midFindings = classifier.classify(List.of(d), spec, matcher, ApiHintMatcher.NONE);
        assertThat(byClass(midFindings, Classification.SHADOW)).isEmpty();

        // LOW scorer 전달(5-arg) → admit → Shadow
        var lowFindings = classifier.classify(
                List.of(d), spec, matcher, new ApiScorer(ApiScorer.Profile.LOW), ApiHintMatcher.NONE);
        assertThat(byClass(lowFindings, Classification.SHADOW))
                .extracting(Finding::pathTemplate).containsExactly("/api/things");
    }

    // --- classifyWithMetrics 사유별 카운트 (doc/12 §1) ---

    @Test
    void classifyWithMetricsCountsDropReasons() {
        // exclude="/internal" + includeWebForms=false
        var hints = new ApiHintMatcher(
                new MatcherConfig(List.of(), List.of(), List.of("/internal"), List.of(), false));
        List<DiscoveredEndpoint> discovered = List.of(
                de("OPTIONS", "/api/widgets", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 10, "2xx", 5),  // CORS-only → 카운트X
                de("GET", "/v2/users/{id}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 100, "2xx", 5),        // spec 매칭 → Active
                de("POST", "/api/orders", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "2xx", 5),      // ADMIT → Shadow
                de("POST", "/internal/debug", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "2xx", 5),  // DROP_EXCLUDED
                de("POST", "/form/save", TemplateSource.INFERRED, EndpointKind.WEB_PAGE, 100, "2xx", 5),      // DROP_WEB_FORM
                de("GET", "/m03/{id}", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "2xx", 5));        // DROP_LOW_SCORE

        ClassificationResult res = classifier.classifyWithMetrics(
                discovered, spec, matcher, new ApiScorer(), hints);

        assertThat(res.dropped()).isEqualTo(new DroppedNonApi(1, 1, 1));
        assertThat(res.dropped().total()).isEqualTo(3);
        assertThat(byClass(res.findings(), Classification.SHADOW))
                .extracting(Finding::pathTemplate).containsExactly("/api/orders"); // ADMIT 만 Shadow
        assertThat(res.findings()).noneMatch(f -> f.method().equals("OPTIONS"));   // OPTIONS 미보고·미카운트

        // 불변식: discovered(non-OPTIONS) = specMatched(Active+Zombie) + shadow + dropped.total (doc/12 §1)
        // 캐비엇: 1:1 spec 매칭 가정. host-agnostic(host=null) spec 이 다중 host 트래픽에 매칭되면 specMatched 측이
        //         observedSpecKeys 중복제거로 근사가 된다(dropped 메트릭 자체는 항상 정확). 본 테스트는 단일 host.
        long nonOptions = discovered.stream().filter(d -> !d.method().equals("OPTIONS")).count();
        int shadow = byClass(res.findings(), Classification.SHADOW).size();
        int specMatched = byClass(res.findings(), Classification.ACTIVE).size()
                + byClass(res.findings(), Classification.ZOMBIE).size();
        assertThat(nonOptions).isEqualTo(specMatched + shadow + res.dropped().total());
    }

    // --- helpers ---

    private static List<Finding> byClass(List<Finding> findings, Classification c) {
        return findings.stream().filter(f -> f.classification() == c).toList();
    }

    private static CanonicalEndpoint ce(String method, String template, boolean deprecated) {
        return new CanonicalEndpoint(method, template, null, deprecated, null, "ref:" + template);
    }

    private static DiscoveredEndpoint de(String method, String template, TemplateSource source,
                                         EndpointKind kind, long hits, String statusBucket,
                                         long distinctClients) {
        var metrics = new DiscoveredEndpoint.Metrics(
                hits, Instant.EPOCH, Instant.EPOCH, Map.of(statusBucket, hits),
                distinctClients, 10, 50);
        return new DiscoveredEndpoint(
                method + " " + HOST + " " + template, method, HOST, template, source, kind, 0.9,
                false, false, metrics);
    }
}
