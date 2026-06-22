// Classifier 2-pass 분류 + Shadow 신뢰도 단위 테스트 (doc/04 §3, §4)
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

    private static final String HOST = "api.example.com";

    private final Classifier classifier = new Classifier();

    private final List<CanonicalEndpoint> spec = List.of(
            ce("GET", "/v2/users/{id}", false),
            ce("GET", "/v2/orders/{orderId}", true),   // deprecated → 관찰되면 Zombie
            ce("GET", "/v2/legacy", false));           // 관찰 안 됨 → Unused
    private final EndpointMatcher matcher = new EndpointMatcher(spec);

    @Test
    void classifiesAllFiveOutcomes() {
        List<DiscoveredEndpoint> discovered = List.of(
                de("GET", "/v2/users/{id}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 100, "2xx", 5),
                de("GET", "/v2/orders/{orderId}", TemplateSource.SPEC, EndpointKind.UNKNOWN, 50, "2xx", 4),
                de("GET", "/internal/debug", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "2xx", 5),
                de("GET", "/admin/login", TemplateSource.INFERRED, EndpointKind.WEB_PAGE, 30, "2xx", 3));

        List<Finding> findings = classifier.classify(discovered, spec, matcher);

        assertThat(byClass(findings, Classification.ACTIVE))
                .extracting(Finding::pathTemplate).containsExactly("/v2/users/{id}");
        assertThat(byClass(findings, Classification.ZOMBIE))
                .extracting(Finding::pathTemplate).containsExactly("/v2/orders/{orderId}");
        assertThat(byClass(findings, Classification.UNUSED))
                .extracting(Finding::pathTemplate).containsExactly("/v2/legacy");
        assertThat(byClass(findings, Classification.SHADOW))
                .extracting(Finding::pathTemplate).containsExactly("/internal/debug");
        assertThat(byClass(findings, Classification.UNDOCUMENTED_WEB_PAGE))
                .extracting(Finding::pathTemplate).containsExactly("/admin/login");
    }

    @Test
    void shadowConfidenceDropsForFourxxOnlyAndInferred() {
        // 거의 전부 4xx + INFERRED + 단일 클라이언트 → 큰 감산
        DiscoveredEndpoint noisy =
                de("GET", "/probe/{id}", TemplateSource.INFERRED, EndpointKind.UNKNOWN, 100, "4xx", 1);
        List<Finding> findings = classifier.classify(List.of(noisy), spec, matcher);

        Finding.Shadow shadow = (Finding.Shadow) byClass(findings, Classification.SHADOW).get(0);
        // 1.0 - 0.7(4xx) - 0.2(단일클라) - 0.1(inferred) = 0.0
        assertThat(shadow.confidence()).isEqualTo(0.0);
    }

    @Test
    void healthyShadowKeepsHighConfidence() {
        DiscoveredEndpoint healthy =
                de("GET", "/internal/metrics", TemplateSource.SPEC, EndpointKind.UNKNOWN, 500, "2xx", 20);
        List<Finding> findings = classifier.classify(List.of(healthy), spec, matcher);

        Finding.Shadow shadow = (Finding.Shadow) byClass(findings, Classification.SHADOW).get(0);
        assertThat(shadow.confidence()).isEqualTo(1.0);
    }

    @Test
    void staticUnmatchedIsNotReportedAsShadow() {
        // 정적 리소스(STATIC)는 문서에 없어도 Shadow 로 보고하지 않음 (doc/02 §2.3)
        DiscoveredEndpoint staticJs =
                de("GET", "/theme/app.js", TemplateSource.INFERRED, EndpointKind.STATIC, 200, "2xx", 5);
        List<Finding> findings = classifier.classify(List.of(staticJs), spec, matcher);

        assertThat(byClass(findings, Classification.SHADOW)).isEmpty();
        assertThat(byClass(findings, Classification.UNDOCUMENTED_WEB_PAGE)).isEmpty();
        // 스펙 3건 미관찰: users/{id}·legacy → Unused 2건, orders(deprecated) → 미발행
        assertThat(byClass(findings, Classification.UNUSED)).hasSize(2);
    }

    @Test
    void deprecatedButUnobservedEmitsNoFinding() {
        // /v2/orders/{orderId} 가 트래픽에 없으면 Zombie 도 아니고 Finding 미발행(Deprecated-clean)
        List<Finding> findings = classifier.classify(List.of(), spec, matcher);
        assertThat(byClass(findings, Classification.ZOMBIE)).isEmpty();
        // /v2/users/{id} 와 /v2/legacy 는 미관찰 → Unused 2건
        assertThat(byClass(findings, Classification.UNUSED)).hasSize(2);
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
                method + " " + HOST + " " + template, method, HOST, template, source, kind, 0.9, metrics);
    }
}
