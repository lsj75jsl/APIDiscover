// InventoryBuilder 집계/정규화 단위 테스트 (doc/02 §3, §4)
package com.pentasecurity.apidiscover.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.NormalizationProperties;
import com.pentasecurity.apidiscover.config.SensitiveKeyProperties;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.DroppedByLimit;
import com.pentasecurity.apidiscover.model.DroppedNonExistent;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryBuilderTest {

    private static final String HOST = "api.example.com";

    private static final NormalizationProperties NORM = NormalizationProperties.defaults();
    private final InventoryBuilder builder = new InventoryBuilder(
            new PathNormalizer(), new EndpointKindClassifier(),
            new CardinalityNormalizer(NORM),
            new ParamCandidateExtractor(new SensitiveKeyMatcher(SensitiveKeyProperties.defaults()), NORM));

    private static ParsedRequest req(String method, String path, int status, String clientIp, long respMs) {
        return req(method, path, status, clientIp, respMs, null);
    }

    private static ParsedRequest req(String method, String path, int status, String clientIp,
                                     long respMs, String type) {
        return new ParsedRequest(method, path, List.of(), status, HOST, clientIp,
                "ua", Instant.EPOCH, respMs, 100, true, null, type, null);
    }

    private static ParsedRequest reqUa(String path, String userAgent) {
        return new ParsedRequest("GET", path, List.of(), 200, HOST, "a",
                userAgent, Instant.EPOCH, 10, 100, true, null, null, null);
    }

    @Test
    void buildWithLimitsIsNoRegressionForNormalInput() {
        // 소규모 입력: 승격 미발동·상한 미발동 → 템플릿 동일 + DroppedByLimit=(0,0), params non-null (무회귀)
        List<ParsedRequest> reqs = List.of(
                req("GET", "/users/1", 200, "a", 10),
                req("GET", "/users/2", 200, "b", 20));

        InventoryBuilder.InventoryResult result = builder.buildWithLimits(reqs, null);

        assertThat(result.droppedByLimit()).isEqualTo(new DroppedByLimit(0, 0));
        assertThat(result.endpoints()).singleElement().satisfies(e -> {
            assertThat(e.pathTemplate()).isEqualTo("/users/{id}");
            assertThat(e.params()).isNotNull();
            assertThat(e.params().path()).extracting("token").containsExactly("{id}"); // path 후보
        });
    }

    @Test
    void aggregatesConcretePathsIntoOneTemplateHeuristically() {
        List<ParsedRequest> reqs = List.of(
                req("GET", "/users/1", 200, "a", 10),
                req("GET", "/users/2", 200, "b", 20),
                req("GET", "/users/3", 200, "a", 30));

        List<DiscoveredEndpoint> d = builder.build(reqs, null); // 스펙 없음 → 휴리스틱

        assertThat(d).singleElement().satisfies(e -> {
            assertThat(e.pathTemplate()).isEqualTo("/users/{id}");
            assertThat(e.templateSource()).isEqualTo(TemplateSource.INFERRED);
            assertThat(e.metrics().hits()).isEqualTo(3);
            assertThat(e.metrics().distinctClients()).isEqualTo(2); // a,b
            assertThat(e.metrics().statusDist().get("2xx")).isEqualTo(3);
        });
    }

    @Test
    void usesSpecTemplateWhenMatcherMatches() {
        EndpointMatcher matcher = new EndpointMatcher(List.of(
                new CanonicalEndpoint("GET", "/users/{id}", null, false, null, "ref")));
        List<ParsedRequest> reqs = List.of(
                req("GET", "/users/1", 200, "a", 10),
                req("GET", "/users/2", 200, "b", 20));

        List<DiscoveredEndpoint> d = builder.build(reqs, matcher);

        assertThat(d).singleElement().satisfies(e -> {
            assertThat(e.pathTemplate()).isEqualTo("/users/{id}");
            assertThat(e.templateSource()).isEqualTo(TemplateSource.SPEC);
            assertThat(e.metrics().hits()).isEqualTo(2);
        });
    }

    @Test
    void separatesByMethodAndTemplate() {
        List<ParsedRequest> reqs = List.of(
                req("GET", "/users/1", 200, "a", 10),
                req("POST", "/users", 201, "a", 15),
                req("GET", "/users/2", 404, "a", 5));

        List<DiscoveredEndpoint> d = builder.build(reqs, null);

        assertThat(d).extracting(e -> e.method() + " " + e.pathTemplate())
                .containsExactlyInAnyOrder("GET /users/{id}", "POST /users");
    }

    @Test
    void assignsEndpointKindFromTypeField() {
        List<ParsedRequest> reqs = List.of(
                req("GET", "/mypage/orderlist", 200, "a", 10, "document"),
                req("GET", "/mypage/orderlist", 200, "b", 12, "document"),
                req("GET", "/js/app.min.js", 200, "c", 5, "library"),
                req("GET", "/api/orders", 200, "d", 8, "xhr"));

        List<DiscoveredEndpoint> d = builder.build(reqs, null);

        var byTemplate = d.stream().collect(
                java.util.stream.Collectors.toMap(DiscoveredEndpoint::pathTemplate, e -> e));
        assertThat(byTemplate.get("/mypage/orderlist").endpointKind())
                .isEqualTo(com.pentasecurity.apidiscover.model.EndpointKind.WEB_PAGE);
        assertThat(byTemplate.get("/js/app.min.js").endpointKind())
                .isEqualTo(com.pentasecurity.apidiscover.model.EndpointKind.STATIC);
        assertThat(byTemplate.get("/api/orders").endpointKind())
                .isEqualTo(com.pentasecurity.apidiscover.model.EndpointKind.API_CANDIDATE);
    }

    @Test
    void flagsNonBrowserUaWhenSdkClientsAreMajority() {
        // 3건 중 SDK 2건 → sdkUaCount*2=4 >= hits=3 → nonBrowserUa=true
        List<ParsedRequest> reqs = List.of(
                reqUa("/items/1", "okhttp/4.9"),
                reqUa("/items/2", "python-requests/2.31"),
                reqUa("/items/3", "Mozilla/5.0 (browser)"));

        assertThat(builder.build(reqs, null)).singleElement()
                .satisfies(e -> assertThat(e.nonBrowserUa()).isTrue());
    }

    @Test
    void nonBrowserUaBoundaryIsInclusiveAtExactlyHalf() {
        // 정확히 50%: SDK 2 / 전체 4 → sdkUaCount*2=4 == hits=4 → 포함(>=)이라 true
        List<ParsedRequest> half = List.of(
                reqUa("/items/1", "curl/8.0"),
                reqUa("/items/2", "axios/1.6"),
                reqUa("/items/3", "Mozilla/5.0 (browser)"),
                reqUa("/items/4", "Mozilla/5.0 (browser)"));
        assertThat(builder.build(half, null)).singleElement()
                .satisfies(e -> assertThat(e.nonBrowserUa()).isTrue());

        // 50% 미만: SDK 1 / 전체 4 → sdkUaCount*2=2 < hits=4 → false
        List<ParsedRequest> minority = List.of(
                reqUa("/items/1", "curl/8.0"),
                reqUa("/items/2", "Mozilla/5.0 (browser)"),
                reqUa("/items/3", "Mozilla/5.0 (browser)"),
                reqUa("/items/4", "Mozilla/5.0 (browser)"));
        assertThat(builder.build(minority, null)).singleElement()
                .satisfies(e -> assertThat(e.nonBrowserUa()).isFalse());
    }

    @Test
    void bucketsStatusAndComputesPercentiles() {
        List<ParsedRequest> reqs = List.of(
                req("GET", "/items/1", 200, "a", 10),
                req("GET", "/items/2", 200, "b", 20),
                req("GET", "/items/3", 404, "c", 30),
                req("GET", "/items/4", 500, "d", 40));

        DiscoveredEndpoint e = builder.build(reqs, null).get(0);

        assertThat(e.metrics().statusDist().get("2xx")).isEqualTo(2);
        assertThat(e.metrics().statusDist().get("4xx")).isEqualTo(1);
        assertThat(e.metrics().statusDist().get("5xx")).isEqualTo(1);
        // nearest-rank: p50 of [10,20,30,40] = ceil(0.5*4)-1 = idx1 = 20
        assertThat(e.metrics().p50RespMs()).isEqualTo(20);
        // p95 = ceil(0.95*4)-1 = idx3 = 40
        assertThat(e.metrics().p95RespMs()).isEqualTo(40);
    }

    // --- 실재성 404-only 필터 (doc/19) ---

    @Test
    void dropsInferred404OnlySignatureAndCounts() {
        // /probe/1·/probe/2 전부 404 → INFERRED /probe/{id} 100%-404 → 비실재 hard-drop, 정상 endpoint 는 보존
        List<ParsedRequest> reqs = List.of(
                req("GET", "/probe/1", 404, "a", 5),
                req("GET", "/probe/2", 404, "b", 6),
                req("GET", "/users/1", 200, "a", 10));
        InventoryBuilder.InventoryResult result = builder.buildWithLimits(reqs, null);

        assertThat(result.endpoints()).extracting(DiscoveredEndpoint::pathTemplate)
                .containsExactly("/users/{id}"); // /probe/{id} 제외
        assertThat(result.droppedNonExistent()).isEqualTo(new DroppedNonExistent(1));
    }

    @Test
    void preserves401And403OnlySignatures() {
        // 인증벽 뒤 실재 endpoint — 404 아님(status404<hits) → 보존(보안 미탐 방지). 통합 4xx 로 drop 하면 안 됨
        List<ParsedRequest> reqs = List.of(
                req("GET", "/secure/1", 401, "a", 5),
                req("GET", "/secure/2", 403, "b", 6));
        InventoryBuilder.InventoryResult result = builder.buildWithLimits(reqs, null);

        assertThat(result.endpoints()).extracting(DiscoveredEndpoint::pathTemplate)
                .containsExactly("/secure/{id}");
        assertThat(result.droppedNonExistent()).isEqualTo(DroppedNonExistent.NONE);
    }

    @Test
    void preservesSignatureWhen404MixedWith2xxOr5xx() {
        // 2xx/3xx/5xx 하나라도 = 라우트 실재 신호 → 100%-404 아님 → 보존
        List<ParsedRequest> reqs = List.of(
                req("GET", "/m/1", 404, "a", 5),
                req("GET", "/m/2", 200, "b", 6),
                req("GET", "/m/3", 500, "c", 7));
        InventoryBuilder.InventoryResult result = builder.buildWithLimits(reqs, null);

        assertThat(result.endpoints()).extracting(DiscoveredEndpoint::pathTemplate)
                .containsExactly("/m/{id}");
        assertThat(result.droppedNonExistent()).isEqualTo(DroppedNonExistent.NONE);
    }

    @Test
    void preservesMostly404SignatureForClassifierSoftPenalty() {
        // 404 다수(>90%)지만 100% 아님(2xx 1건) → 인벤토리 보존 → Classifier soft -0.7 대상으로 남김(hard-drop ⊄)
        List<ParsedRequest> reqs = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            reqs.add(req("GET", "/p/" + i, 404, "a", 5));
        }
        reqs.add(req("GET", "/p/10", 200, "a", 5)); // 실재 신호 1건
        InventoryBuilder.InventoryResult result = builder.buildWithLimits(reqs, null);

        assertThat(result.endpoints()).extracting(DiscoveredEndpoint::pathTemplate)
                .containsExactly("/p/{id}"); // 보존(status404 9 != hits 10)
        assertThat(result.droppedNonExistent()).isEqualTo(DroppedNonExistent.NONE);
    }

    @Test
    void preservesSpecMatched404OnlySignature() {
        // 스펙 매칭(SPEC) 은 권위 → 404-only 여도 보존(미배포 경고는 범위 밖). 필터 대상은 INFERRED 만
        EndpointMatcher matcher = new EndpointMatcher(List.of(
                new CanonicalEndpoint("GET", "/v1/legacy/{id}", null, false, null, "ref")));
        List<ParsedRequest> reqs = List.of(
                req("GET", "/v1/legacy/1", 404, "a", 5),
                req("GET", "/v1/legacy/2", 404, "b", 6));
        InventoryBuilder.InventoryResult result = builder.buildWithLimits(reqs, matcher);

        assertThat(result.endpoints()).singleElement().satisfies(e -> {
            assertThat(e.pathTemplate()).isEqualTo("/v1/legacy/{id}");
            assertThat(e.templateSource()).isEqualTo(TemplateSource.SPEC);
        });
        assertThat(result.droppedNonExistent()).isEqualTo(DroppedNonExistent.NONE);
    }
}
