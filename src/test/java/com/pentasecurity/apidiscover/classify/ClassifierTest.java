// Classifier 2-pass 분류 + ApiScorer 게이트 단위 테스트 (doc/04, doc/08)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.Classification;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.Finding;
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
