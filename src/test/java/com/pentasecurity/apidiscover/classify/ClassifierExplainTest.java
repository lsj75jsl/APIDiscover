// Classifier.classifyExplained 판단 근거 단위 테스트 — basis 분류별·findings↔rationale 정합·스캔경로 무회귀 (doc/34 §2/§3)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.ApiBasis;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.Classification;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.EndpointRationale;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassifierExplainTest {

    private static final String HOST = "shop.example.com"; // 비-api host → Shadow 는 path/method 신호로 결정

    private final ApiScorer scorer = new ApiScorer();
    private final Classifier classifier = new Classifier(scorer);

    private final List<CanonicalEndpoint> spec = List.of(
            ce("GET", "/v2/users/{id}", false),        // 관찰됨 → Active
            ce("GET", "/v2/orders/{orderId}", true),   // deprecated + 관찰됨 → Zombie
            ce("GET", "/v2/legacy", false));           // 미관찰 → Unused
    private final EndpointMatcher matcher = new EndpointMatcher(spec);

    private final List<DiscoveredEndpoint> discovered = List.of(
            de("GET", "/v2/users/{id}", EndpointKind.UNKNOWN),
            de("GET", "/v2/orders/{orderId}", EndpointKind.UNKNOWN),
            de("POST", "/api/debug", EndpointKind.UNKNOWN));   // api_seg + write → Shadow

    private Classifier.ExplainedClassification explain() {
        return classifier.classifyExplained(discovered, spec, matcher, scorer, ApiHintMatcher.NONE, null);
    }

    @Test
    void rationaleParallelsFindingsByOrderAndIdentity() {
        var r = explain();
        assertThat(r.rationale()).hasSameSizeAs(r.findings());
        for (int i = 0; i < r.findings().size(); i++) {
            Finding f = r.findings().get(i);
            EndpointRationale er = r.rationale().get(i);
            assertThat(er.method()).isEqualTo(f.method());
            assertThat(er.host()).isEqualTo(f.host());
            assertThat(er.pathTemplate()).isEqualTo(f.pathTemplate());
            assertThat(er.classification()).isEqualTo(f.classification());
        }
    }

    @Test
    void shadowBasisIsScoreWithGateAndSignals() {
        var basis = (ApiBasis.ScoreBasis) basisOf(Classification.SHADOW, "/api/debug");
        // /api/debug: api_seg + write + repeat → ADMIT. apiScore = scorer.scoreExplain 총점, threshold = effective
        var d = discovered.stream().filter(x -> x.pathTemplate().equals("/api/debug")).findFirst().orElseThrow();
        assertThat(basis.gate()).isEqualTo("ADMIT");
        assertThat(basis.mode()).isEqualTo("pathless");
        assertThat(basis.threshold()).isEqualTo(scorer.threshold());
        assertThat(basis.apiScore()).isEqualTo(scorer.scoreExplain(d, false, ApiHintMatcher.NONE).total());
        assertThat(basis.signals()).isNotEmpty();
        assertThat(basis.signals()).anySatisfy(s -> assertThat(s.key()).isEqualTo("apiSegment"));
    }

    @Test
    void activeBasisIsSpecMatchNotDeprecated() {
        var basis = (ApiBasis.SpecMatchBasis) basisOf(Classification.ACTIVE, "/v2/users/{id}");
        assertThat(basis.deprecated()).isFalse();
        assertThat(basis.estimated()).isFalse();
        assertThat(basis.specRef()).isEqualTo("ref:/v2/users/{id}");
    }

    @Test
    void deprecatedZombieBasisIsSpecMatchDeprecated() {
        var basis = (ApiBasis.SpecMatchBasis) basisOf(Classification.ZOMBIE, "/v2/orders/{orderId}");
        assertThat(basis.deprecated()).isTrue();
        assertThat(basis.estimated()).isFalse();
    }

    @Test
    void unusedBasisIsSpecOnly() {
        var basis = (ApiBasis.SpecOnlyBasis) basisOf(Classification.UNUSED, "/v2/legacy");
        assertThat(basis.specRef()).isEqualTo("ref:/v2/legacy");
    }

    @Test
    void scanPathFindingsIdenticalToExplainPath() {
        // 스캔 경로(rationaleOut 미전달)와 explain 경로의 findings 가 완전 동일 → report_json/ETag 무영향(doc/34 §3·§4)
        List<Finding> scanPath = classifier.classifyWithMetrics(
                discovered, spec, matcher, scorer, ApiHintMatcher.NONE, Map.of(), null, null).findings();
        assertThat(explain().findings()).isEqualTo(scanPath);
    }

    @Test
    void basisJsonCarriesTypeDiscriminator() throws Exception {
        // /discovery 응답 계약(doc/34 §2): basis 는 polymorphic "type" 판별자로 직렬화
        var mapper = new ObjectMapper();
        assertThat(mapper.writeValueAsString(basisOf(Classification.SHADOW, "/api/debug")))
                .contains("\"type\":\"score\"").contains("\"apiScore\"").contains("\"signals\"");
        assertThat(mapper.writeValueAsString(basisOf(Classification.ACTIVE, "/v2/users/{id}")))
                .contains("\"type\":\"spec_match\"").contains("\"specRef\"");
        assertThat(mapper.writeValueAsString(basisOf(Classification.UNUSED, "/v2/legacy")))
                .contains("\"type\":\"spec_only\"");
    }

    // --- helpers ---

    private ApiBasis basisOf(Classification c, String pathTemplate) {
        return explain().rationale().stream()
                .filter(er -> er.classification() == c && er.pathTemplate().equals(pathTemplate))
                .map(EndpointRationale::basis)
                .findFirst().orElseThrow();
    }

    private static CanonicalEndpoint ce(String method, String template, boolean deprecated) {
        return new CanonicalEndpoint(method, template, null, deprecated, null, "ref:" + template);
    }

    private static DiscoveredEndpoint de(String method, String template, EndpointKind kind) {
        var metrics = new DiscoveredEndpoint.Metrics(
                100, Instant.EPOCH, Instant.EPOCH, Map.of("2xx", 100L), 5, 10, 50);
        return new DiscoveredEndpoint(
                method + " " + HOST + " " + template, method, HOST, template, TemplateSource.INFERRED, kind, 0.9,
                false, false, metrics, ParamCandidates.EMPTY);
    }
}
