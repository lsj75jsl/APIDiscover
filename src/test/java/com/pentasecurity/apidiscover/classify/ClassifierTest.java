// Classifier 2-pass 분류 + ApiScorer 게이트 단위 테스트 (doc/04, doc/08)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.Classification;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.DroppedNonApi;
import com.pentasecurity.apidiscover.model.EndpointIdentity;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.MatcherConfig;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.SeverityBand;
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

    // doc/04 §7 case5 — 통계 보정 INFERRED 템플릿 → shadowConfidence −0.1 격리(§4.1).
    // healthyShadowKeepsHighConfidence(SPEC=1.0)가 control. 동일 신호에서 source 만 INFERRED 로 바꿔
    // 다른 감점/가점(4xx·hits<5·단일클라·API_CANDIDATE) 0 → INFERRED 단독 기여 = 1.0−0.1=0.9 를 잠근다.
    @Test
    void inferredOnlyShadowLosesExactlyPointOneConfidence() {
        DiscoveredEndpoint inferred =
                de("POST", "/api/reports", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 500, "2xx", 20);
        Finding.Shadow shadow = (Finding.Shadow) byClass(
                classifier.classify(List.of(inferred), spec, matcher), Classification.SHADOW).get(0);
        assertThat(shadow.confidence()).isEqualTo(0.9);
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

    // --- OPTIONS preflight inconclusive (doc/23 M1) ---

    @Test
    void optionsSpecOpWithObservedOptionsTrafficIsPreflightAmbiguous() {
        // 스펙 OPTIONS operation + OPTIONS 트래픽 관측(corsKeys hit) → Unused 이나 preflightAmbiguous=true
        List<CanonicalEndpoint> optSpec = List.of(ce("OPTIONS", "/api/widgets", false));
        var optMatcher = new EndpointMatcher(optSpec);
        List<DiscoveredEndpoint> discovered = List.of(
                de("OPTIONS", "/api/widgets", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 10, "2xx", 5));

        List<Finding> findings = classifier.classify(discovered, optSpec, optMatcher);

        var unused = byClass(findings, Classification.UNUSED);
        assertThat(unused).extracting(Finding::pathTemplate).containsExactly("/api/widgets");
        assertThat(((Finding.Unused) unused.get(0)).preflightAmbiguous()).isTrue();
    }

    @Test
    void optionsSpecOpWithNoOptionsTrafficIsPlainUnused() {
        // 스펙 OPTIONS operation + OPTIONS 트래픽 없음 → corsKeys 미hit → plain Unused(preflightAmbiguous=false)
        List<CanonicalEndpoint> optSpec = List.of(ce("OPTIONS", "/api/widgets", false));
        var optMatcher = new EndpointMatcher(optSpec);
        List<DiscoveredEndpoint> discovered = List.of(
                de("GET", "/other", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 5, "2xx", 2));

        List<Finding> findings = classifier.classify(discovered, optSpec, optMatcher);

        var unused = byClass(findings, Classification.UNUSED);
        assertThat(unused).extracting(Finding::pathTemplate).containsExactly("/api/widgets");
        assertThat(((Finding.Unused) unused.get(0)).preflightAmbiguous()).isFalse();
    }

    @Test
    void nonOptionsUnusedIsNeverPreflightAmbiguous() {
        // 비-OPTIONS Unused(/v2/legacy)는 OPTIONS 아니므로 preflightAmbiguous 절대 false (비대칭)
        List<DiscoveredEndpoint> discovered = List.of(
                de("GET", "/v2/users/{id}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 100, "2xx", 5));
        List<Finding> findings = classifier.classify(discovered, spec, matcher);

        Finding legacy = byClass(findings, Classification.UNUSED).stream()
                .filter(f -> f.pathTemplate().equals("/v2/legacy")).findFirst().orElseThrow();
        assertThat(((Finding.Unused) legacy).preflightAmbiguous()).isFalse();
    }

    @Test
    void concreteHostOptionsSpecMatchesSameHostTrafficOnly() {
        // optionsTrafficObserved concrete-host(host!=null) exact 분기: 동일 host→ambiguous / 다른 host→false(cross-host 비매칭)
        List<CanonicalEndpoint> optSpec = List.of(
                new CanonicalEndpoint("OPTIONS", "/api/widgets", "api.example.com", false, null, "ref"));
        var optMatcher = new EndpointMatcher(optSpec);

        List<Finding> same = classifier.classify(
                List.of(deH("api.example.com", "OPTIONS", "/api/widgets", 10, "2xx")), optSpec, optMatcher);
        assertThat(((Finding.Unused) byClass(same, Classification.UNUSED).get(0)).preflightAmbiguous()).isTrue();

        List<Finding> cross = classifier.classify(
                List.of(deH("other.example.com", "OPTIONS", "/api/widgets", 10, "2xx")), optSpec, optMatcher);
        assertThat(((Finding.Unused) byClass(cross, Classification.UNUSED).get(0)).preflightAmbiguous()).isFalse();
    }

    // --- M2: operator genuine-OPTIONS 힌트 (doc/23 §8) ---

    private static ApiHintMatcher optionsHints(String... prefixes) {
        return new ApiHintMatcher(new MatcherConfig(
                List.of(), List.of(), List.of(), List.of(), List.of(prefixes), false));
    }

    @Test
    void declaredGenuineOptionsWithSpecAndTrafficBecomesActive() {
        // 선언 + 스펙 OPTIONS op + OPTIONS 트래픽 → observed → Active (M1 ambiguous 아님)
        List<CanonicalEndpoint> optSpec = List.of(ce("OPTIONS", "/api/widgets", false));
        var optMatcher = new EndpointMatcher(optSpec);
        List<DiscoveredEndpoint> discovered = List.of(
                de("OPTIONS", "/api/widgets", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 10, "2xx", 5));

        List<Finding> findings = classifier.classify(discovered, optSpec, optMatcher, optionsHints("/api/widgets"));

        assertThat(byClass(findings, Classification.ACTIVE))
                .extracting(Finding::pathTemplate).containsExactly("/api/widgets");
        assertThat(byClass(findings, Classification.UNUSED)).isEmpty();
    }

    @Test
    void undeclaredOptionsSpecOpStaysM1Ambiguous() {
        // 미선언(NONE) → genuineOptions=false → M1 preflightAmbiguous 유지(Active 아님)
        List<CanonicalEndpoint> optSpec = List.of(ce("OPTIONS", "/api/widgets", false));
        var optMatcher = new EndpointMatcher(optSpec);
        List<DiscoveredEndpoint> discovered = List.of(
                de("OPTIONS", "/api/widgets", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 10, "2xx", 5));

        List<Finding> findings = classifier.classify(discovered, optSpec, optMatcher, ApiHintMatcher.NONE);

        var unused = byClass(findings, Classification.UNUSED);
        assertThat(unused).extracting(Finding::pathTemplate).containsExactly("/api/widgets");
        assertThat(((Finding.Unused) unused.get(0)).preflightAmbiguous()).isTrue();
        assertThat(byClass(findings, Classification.ACTIVE)).isEmpty();
    }

    @Test
    void declaredOptionsWithoutSpecMatchIsSkippedNoShadowEvenWhenOverDeclared() {
        // 과declare(/api) + 스펙엔 GET op 만(OPTIONS op 없음) → spec-match 한정으로 observed 미진입 → OPTIONS skip(Shadow 무폭발)
        List<CanonicalEndpoint> getSpec = List.of(ce("GET", "/api/widgets", false));
        var m = new EndpointMatcher(getSpec);
        List<DiscoveredEndpoint> discovered = List.of(
                de("OPTIONS", "/api/widgets", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 50, "2xx", 5));

        List<Finding> findings = classifier.classify(discovered, getSpec, m, optionsHints("/api"));

        assertThat(findings).noneMatch(f -> f.method().equals("OPTIONS")); // OPTIONS 미보고
        assertThat(byClass(findings, Classification.SHADOW)).isEmpty();     // preflight 홍수 무폭발(불변식 보존)
        // GET op 미관측 → Unused(비-OPTIONS → preflightAmbiguous=false)
        var unused = byClass(findings, Classification.UNUSED);
        assertThat(unused).extracting(Finding::pathTemplate).containsExactly("/api/widgets");
        assertThat(((Finding.Unused) unused.get(0)).preflightAmbiguous()).isFalse();
    }

    // --- M3: acrm 결정적 preflight 가용성 게이트 (doc/23 §9) ---

    /** OPTIONS discovered — hits 중 acrmCount 건이 preflight(acrm). hits-acrmCount = genuine. */
    private static DiscoveredEndpoint deAcrm(String template, long hits, long acrmCount) {
        var metrics = new DiscoveredEndpoint.Metrics(
                hits, Instant.EPOCH, Instant.EPOCH, Map.of("2xx", hits), 5, 10, 50, acrmCount);
        return new DiscoveredEndpoint("OPTIONS " + HOST + " " + template, "OPTIONS", HOST, template,
                TemplateSource.INFERRED, EndpointKind.UNKNOWN, 0.9, false, false, metrics, ParamCandidates.EMPTY);
    }

    @Test
    void activeGateGenuineOptionsBecomesActive() {
        // acrm 가용(Σacrm>0=ACTIVE) + genuine hit(acrm-absent) → 정상 매칭 → Active
        List<CanonicalEndpoint> optSpec = List.of(ce("OPTIONS", "/api/widgets", false));
        var m = new EndpointMatcher(optSpec);
        var findings = classifier.classify(List.of(deAcrm("/api/widgets", 10, 3)), optSpec, m); // 7 genuine
        assertThat(byClass(findings, Classification.ACTIVE))
                .extracting(Finding::pathTemplate).containsExactly("/api/widgets");
        assertThat(byClass(findings, Classification.UNUSED)).isEmpty();
    }

    @Test
    void activeGatePurePreflightIsPlainUnusedNotAmbiguous() {
        // 전부 preflight(acrm) → genuine 0 → skip. ACTIVE 라 M1 ambiguous 자동 승급(plain Unused)
        List<CanonicalEndpoint> optSpec = List.of(ce("OPTIONS", "/api/widgets", false));
        var m = new EndpointMatcher(optSpec);
        var findings = classifier.classify(List.of(deAcrm("/api/widgets", 5, 5)), optSpec, m);
        var unused = byClass(findings, Classification.UNUSED);
        assertThat(unused).extracting(Finding::pathTemplate).containsExactly("/api/widgets");
        assertThat(((Finding.Unused) unused.get(0)).preflightAmbiguous()).isFalse();
        assertThat(byClass(findings, Classification.ACTIVE)).isEmpty();
    }

    @Test
    void gateBoundaryAcrmZeroDormantOnePlusActive() {
        List<CanonicalEndpoint> optSpec = List.of(ce("OPTIONS", "/api/widgets", false));
        var m = new EndpointMatcher(optSpec);
        // acrm 0 → Σ=0 → DORMANT → M1 ambiguous(corsKeys hit, 미선언)
        var dormant = classifier.classify(List.of(deAcrm("/api/widgets", 5, 0)), optSpec, m);
        assertThat(((Finding.Unused) byClass(dormant, Classification.UNUSED).get(0)).preflightAmbiguous()).isTrue();
        // acrm 1 → Σ=1 → ACTIVE, genuine 4 → 매칭 → Active
        var active = classifier.classify(List.of(deAcrm("/api/widgets", 5, 1)), optSpec, m);
        assertThat(byClass(active, Classification.ACTIVE))
                .extracting(Finding::pathTemplate).containsExactly("/api/widgets");
    }

    @Test
    void activeMixedGenuineAndPreflightSignatures() {
        // genuine(/api/widgets, acrm 0) + pure-preflight(/api/things, acrm 전부). Σacrm>0(=ACTIVE) → genuine→Active, preflight→skip
        List<CanonicalEndpoint> optSpec = List.of(
                ce("OPTIONS", "/api/widgets", false), ce("OPTIONS", "/api/things", false));
        var m = new EndpointMatcher(optSpec);
        var findings = classifier.classify(List.of(
                deAcrm("/api/widgets", 8, 0), deAcrm("/api/things", 6, 6)), optSpec, m);

        assertThat(byClass(findings, Classification.ACTIVE))
                .extracting(Finding::pathTemplate).containsExactly("/api/widgets");
        var unused = byClass(findings, Classification.UNUSED);
        assertThat(unused).extracting(Finding::pathTemplate).containsExactly("/api/things");
        assertThat(((Finding.Unused) unused.get(0)).preflightAmbiguous()).isFalse(); // ACTIVE → 승급
    }

    @Test
    void activeGenuineUnmatchedOptionsBecomesShadowButPreflightExcludedFloodSafe() {
        // P3: ACTIVE + genuine + 미문서(미매칭) OPTIONS → fall-through → gate → Shadow (신규 경로).
        // pure-preflight(acrm>0) 분은 제외 → preflight flood 가 Shadow 로 안 터짐(불변식2 flood-safety).
        List<CanonicalEndpoint> emptySpec = List.of();
        var m = new EndpointMatcher(emptySpec);
        List<DiscoveredEndpoint> discovered = List.of(
                deAcrm("/api/v1/probe", 5, 0),        // genuine 미문서 OPTIONS(api_seg+version+repeat→ADMIT) → Shadow
                deAcrm("/api/v1/preflight", 10, 10)); // pure preflight → skip(보고 안 함). 게이트 ACTIVE 유발(Σacrm>0)

        List<Finding> findings = classifier.classify(discovered, emptySpec, m);

        var shadows = byClass(findings, Classification.SHADOW);
        assertThat(shadows).hasSize(1); // genuine 1건만 (preflight flood 없음)
        assertThat(shadows.get(0).method()).isEqualTo("OPTIONS");
        assertThat(shadows.get(0).pathTemplate()).isEqualTo("/api/v1/probe");
        assertThat(findings).noneMatch(f -> f.pathTemplate().equals("/api/v1/preflight")); // preflight 미보고(flood-safe)
    }

    // --- cross-scan recency entrenchment (doc/24) ---

    @Test
    void priorFirstSeenEntrenchmentRaisesZombieSeverityBand() {
        // deprecated spec + 트래픽 → Zombie. priorFirstSeen(이력 최초 100일 전) → severity entrenchment 보강(band 상향)
        List<CanonicalEndpoint> depSpec = List.of(ce("GET", "/v2/old", true));
        var m = new EndpointMatcher(depSpec);
        var d = de("GET", "/v2/old", TemplateSource.SPEC, EndpointKind.UNKNOWN, 10, "4xx", 1); // base 낮음(LOW)

        Finding.Zombie cold = (Finding.Zombie) byClass(
                classifier.classifyWithMetrics(List.of(d), depSpec, m, new ApiScorer(), ApiHintMatcher.NONE)
                        .findings(), Classification.ZOMBIE).get(0);
        // de() ts=EPOCH → lastSeen=EPOCH. 이력 최초 = 100일 전 → lifespan 100일(≥SAT).
        // 키 = EndpointIdentity.key(method, 스캔host, template) — upsert/영속/prior 와 동일(d.signature() 아님, 발산 무관)
        Map<String, Instant> prior = Map.of(
                EndpointIdentity.key("GET", HOST, "/v2/old"), Instant.EPOCH.minusSeconds(100L * 86_400));
        Finding.Zombie hot = (Finding.Zombie) byClass(
                classifier.classifyWithMetrics(List.of(d), depSpec, m, new ApiScorer(), ApiHintMatcher.NONE, prior, null, HOST)
                        .findings(), Classification.ZOMBIE).get(0);

        assertThat(cold.severity().band()).isEqualTo(SeverityBand.LOW);
        assertThat(hot.severity().score()).isGreaterThan(cold.severity().score());
        assertThat(hot.severity().band()).isEqualTo(SeverityBand.MEDIUM);
        assertThat(hot.confidence()).isEqualTo(1.0);   // confidence·estimated 불변(severity 만 보강)
        assertThat(hot.estimated()).isFalse();
    }

    @Test
    void recencyMatchesByIdentityKeyDespiteSignatureDivergence() {
        // ★signature 발산 회귀가드(P3): DiscoveredEndpoint.signature 가 (method,host,최종 template)와 어긋나도
        // recency 는 EndpointIdentity.key(method,스캔host,template)로 lookup → entrenchment 정확 매칭(severity 과소 방지).
        List<CanonicalEndpoint> depSpec = List.of(ce("GET", "/v2/old", true)); // deprecated → Zombie
        var m = new EndpointMatcher(depSpec);
        var metrics = new DiscoveredEndpoint.Metrics(10, Instant.EPOCH, Instant.EPOCH, Map.of("4xx", 10L), 1, 10, 50);
        // signature 발산: 최종 template="/v2/old" 이나 signature 는 승격 전 "/old-pre-promote"
        var divergent = new DiscoveredEndpoint(
                "GET " + HOST + " /old-pre-promote", "GET", HOST, "/v2/old",
                TemplateSource.SPEC, EndpointKind.UNKNOWN, 0.9, false, false, metrics, ParamCandidates.EMPTY);

        // prior 는 영속/upsert 와 동일하게 제약 튜플 키로 적재(발산 signature 아님)
        Map<String, Instant> prior = Map.of(
                EndpointIdentity.key("GET", HOST, "/v2/old"), Instant.EPOCH.minusSeconds(100L * 86_400));
        Finding.Zombie z = (Finding.Zombie) byClass(
                classifier.classifyWithMetrics(List.of(divergent), depSpec, m, new ApiScorer(), ApiHintMatcher.NONE,
                        prior, null, HOST).findings(), Classification.ZOMBIE).get(0);

        // 수정 전(lookup=d.signature()=".../old-pre-promote")이면 prior miss → entrenchment 0 → band LOW(과소).
        assertThat(z.severity().band()).isEqualTo(SeverityBand.MEDIUM); // identity 키 매칭 → entrenchment 적용
    }

    // --- Active/Zombie param 후보 (doc/25 §B) ---

    @Test
    void activeAndZombieExposeObservedQueryAndSpecPathParams() {
        // query=관측 누적(ev), path=spec 템플릿 변수(권위)
        List<CanonicalEndpoint> spec2 = List.of(
                ce("GET", "/v2/users/{id}", false),    // Active
                ce("GET", "/v2/orders/{id}", true));   // deprecated → Zombie
        var m = new EndpointMatcher(spec2);
        List<Finding> findings = classifier.classify(List.of(
                deP("a.example.com", "/v2/users/{id}", query("expand")),
                deP("a.example.com", "/v2/orders/{id}", query("expand"))), spec2, m);

        Finding.Active active = (Finding.Active) byClass(findings, Classification.ACTIVE).get(0);
        assertThat(active.params().query()).extracting(ParamCandidates.QueryParam::name).containsExactly("expand");
        assertThat(active.params().path()).extracting(ParamCandidates.PathParam::token).containsExactly("{id}");

        Finding.Zombie zombie = (Finding.Zombie) byClass(findings, Classification.ZOMBIE).get(0);
        assertThat(zombie.params().query()).extracting(ParamCandidates.QueryParam::name).containsExactly("expand");
        assertThat(zombie.params().path()).extracting(ParamCandidates.PathParam::token).containsExactly("{id}");
    }

    @Test
    void evidenceUnionsQueryParamsAcrossHosts() {
        // host-agnostic spec 이 여러 host d 에 매칭 → query 이름 union (doc/25 §B.2)
        List<CanonicalEndpoint> spec2 = List.of(ce("GET", "/legacy/{id}", true)); // host=null, deprecated → Zombie
        var m = new EndpointMatcher(spec2);
        Finding.Zombie z = (Finding.Zombie) byClass(classifier.classify(List.of(
                deP("a.example.com", "/legacy/{id}", query("a")),
                deP("b.example.com", "/legacy/{id}", query("b"))), spec2, m), Classification.ZOMBIE).get(0);
        assertThat(z.params().query()).extracting(ParamCandidates.QueryParam::name)
                .containsExactlyInAnyOrder("a", "b");
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
                false, false, metrics, ParamCandidates.EMPTY);
    }

    /** host 지정 변형(host-agnostic evidence 합산 테스트용). */
    private static DiscoveredEndpoint deH(String host, String method, String template,
                                          long hits, String statusBucket) {
        var metrics = new DiscoveredEndpoint.Metrics(
                hits, Instant.EPOCH, Instant.EPOCH, Map.of(statusBucket, hits), 5, 10, 50);
        return new DiscoveredEndpoint(method + " " + host + " " + template, method, host, template,
                TemplateSource.SPEC, EndpointKind.UNKNOWN, 0.9, false, false, metrics, ParamCandidates.EMPTY);
    }

    /** host+params 지정(doc/25 §B Active/Zombie params·멀티host union 테스트). SPEC 매칭 GET. */
    private static DiscoveredEndpoint deP(String host, String template, ParamCandidates params) {
        var metrics = new DiscoveredEndpoint.Metrics(
                10, Instant.EPOCH, Instant.EPOCH, Map.of("2xx", 10L), 5, 10, 50);
        return new DiscoveredEndpoint("GET " + host + " " + template, "GET", host, template,
                TemplateSource.SPEC, EndpointKind.UNKNOWN, 0.9, false, false, metrics, params);
    }

    private static ParamCandidates query(String... names) {
        List<ParamCandidates.QueryParam> q = java.util.Arrays.stream(names)
                .map(n -> new ParamCandidates.QueryParam(n, 1, java.util.Set.of(), false)).toList();
        return new ParamCandidates(q, List.of());
    }

    // --- 버전 Zombie + severity (doc/16) ---

    @Test
    void explicitDeprecatedZombieGetsSeverityNotEstimated() {
        // 기존 spec 의 /v2/orders/{orderId} deprecated 관측 → conf 1.0·estimated=false (무회귀) + severity 가산
        var findings = classifier.classify(List.of(
                de("GET", "/v2/orders/{orderId}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 100, "2xx", 5)),
                spec, matcher);
        Finding.Zombie z = (Finding.Zombie) byClass(findings, Classification.ZOMBIE).get(0);
        assertThat(z.confidence()).isEqualTo(1.0);
        assertThat(z.estimated()).isFalse();
        assertThat(z.severity()).isNotNull();
    }

    @Test
    void estimatedZombieWhenOlderVersionStillActive() {
        var verSpec = List.of(ce("GET", "/v1/orders/{id}", false), ce("GET", "/v2/orders/{id}", false));
        var verMatcher = new EndpointMatcher(verSpec);
        var findings = classifier.classify(List.of(
                de("GET", "/v1/orders/{id}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 100, "2xx", 5),
                de("GET", "/v2/orders/{id}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 200, "2xx", 5)),
                verSpec, verMatcher);

        var zombies = byClass(findings, Classification.ZOMBIE);
        assertThat(zombies).extracting(Finding::pathTemplate).containsExactly("/v1/orders/{id}");
        Finding.Zombie z = (Finding.Zombie) zombies.get(0);
        assertThat(z.confidence()).isEqualTo(0.6);  // 추정
        assertThat(z.estimated()).isTrue();
        assertThat(z.severity()).isNotNull();
        // 신버전 v2 는 Active 유지(무회귀)
        assertThat(byClass(findings, Classification.ACTIVE))
                .extracting(Finding::pathTemplate).containsExactly("/v2/orders/{id}");
    }

    @Test
    void olderNotEstimatedWhenNewerVersionUnused() {
        // P3-2(a): 신버전 v2 미관측(Unused) → active 신버전 없음 → 구버전 v1 추정 안 함(Active 유지)
        var verSpec = List.of(ce("GET", "/v1/orders/{id}", false), ce("GET", "/v2/orders/{id}", false));
        var verMatcher = new EndpointMatcher(verSpec);
        var findings = classifier.classify(List.of(
                de("GET", "/v1/orders/{id}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 100, "2xx", 5)),
                verSpec, verMatcher); // v2 미관측

        assertThat(byClass(findings, Classification.ZOMBIE)).isEmpty();
        assertThat(byClass(findings, Classification.ACTIVE))
                .extracting(Finding::pathTemplate).containsExactly("/v1/orders/{id}");
        assertThat(byClass(findings, Classification.UNUSED))
                .extracting(Finding::pathTemplate).containsExactly("/v2/orders/{id}");
    }

    @Test
    void explicitDeprecatedOlderVersionStaysExplicitZombieNotEstimated() {
        // P3-2(b): 구버전 v1 이 명시 deprecated → 명시 Zombie(1.0) 유지, 추정(0.6)으로 하향/중복 없음
        var verSpec = List.of(ce("GET", "/v1/orders/{id}", true), ce("GET", "/v2/orders/{id}", false));
        var verMatcher = new EndpointMatcher(verSpec);
        var findings = classifier.classify(List.of(
                de("GET", "/v1/orders/{id}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 100, "2xx", 5),
                de("GET", "/v2/orders/{id}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 200, "2xx", 5)),
                verSpec, verMatcher);

        var zombies = byClass(findings, Classification.ZOMBIE);
        assertThat(zombies).extracting(Finding::pathTemplate).containsExactly("/v1/orders/{id}"); // 1건만
        Finding.Zombie z = (Finding.Zombie) zombies.get(0);
        assertThat(z.confidence()).isEqualTo(1.0); // 명시 우선
        assertThat(z.estimated()).isFalse();
        assertThat(byClass(findings, Classification.ACTIVE))
                .extracting(Finding::pathTemplate).containsExactly("/v2/orders/{id}");
    }

    @Test
    void evidenceSummedAcrossHostsForHostAgnosticSpec() {
        var verSpec = List.of(ce("GET", "/legacy/{id}", true)); // host-agnostic deprecated
        var verMatcher = new EndpointMatcher(verSpec);
        var findings = classifier.classify(List.of(
                deH("a.example.com", "GET", "/legacy/{id}", 100, "2xx"),
                deH("b.example.com", "GET", "/legacy/{id}", 200, "2xx")),
                verSpec, verMatcher);
        var zombies = byClass(findings, Classification.ZOMBIE);
        assertThat(zombies).hasSize(1); // 두 host d → 한 host-agnostic 키로 합산 → 단일 Zombie
        assertThat(((Finding.Zombie) zombies.get(0)).severity()).isNotNull();
    }
}
