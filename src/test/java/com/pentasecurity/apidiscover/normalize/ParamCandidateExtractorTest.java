// ParamCandidateExtractor query/path 후보 + 상한 + sensitive 단위 테스트 (doc/13 §2, §3, §5)
package com.pentasecurity.apidiscover.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.NormalizationProperties;
import com.pentasecurity.apidiscover.config.SensitiveKeyProperties;
import com.pentasecurity.apidiscover.model.ParamCandidates.PathParam;
import com.pentasecurity.apidiscover.model.ParamCandidates.QueryParam;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.QueryParamObs;
import com.pentasecurity.apidiscover.model.TemplateSource;
import com.pentasecurity.apidiscover.model.ValueLenBucket;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParamCandidateExtractorTest {

    private static final String H = "api.example.com";

    private final ParamCandidateExtractor extractor = new ParamCandidateExtractor(
            new SensitiveKeyMatcher(SensitiveKeyProperties.defaults()), NormalizationProperties.defaults());

    private static ParsedRequest req(QueryParamObs... qps) {
        return new ParsedRequest("GET", "/x", List.of(qps), 200, H, "ip", "ua",
                Instant.EPOCH, 1, 1, true, null, null, null);
    }

    private static Acc acc(String template) {
        return new Acc("GET", H, template, TemplateSource.INFERRED);
    }

    @Test
    void extractsQueryNamePresenceAndBuckets() {
        Acc a = acc("/users/{id}");
        a.add(req(new QueryParamObs("expand", ValueLenBucket.S)));
        a.add(req(new QueryParamObs("expand", ValueLenBucket.M))); // 같은 이름 → count 2, buckets {S,M}

        var res = extractor.extract(a);
        assertThat(res.candidates().query()).singleElement().satisfies(qp -> {
            assertThat(qp.name()).isEqualTo("expand");
            assertThat(qp.count()).isEqualTo(2);
            assertThat(qp.lenBuckets()).containsExactlyInAnyOrder(ValueLenBucket.S, ValueLenBucket.M);
            assertThat(qp.sensitive()).isFalse();
        });
        assertThat(res.droppedParams()).isZero();
    }

    @Test
    void extractsPathVariableSegments() {
        var path = extractor.extract(acc("/users/{id}/orders/{var}")).candidates().path();
        assertThat(path).extracting(PathParam::position).containsExactly(1, 3);
        assertThat(path).extracting(PathParam::token).containsExactly("{id}", "{var}");
    }

    @Test
    void sensitiveParamSuppressesBuckets() {
        Acc a = acc("/login");
        a.add(req(new QueryParamObs("password", ValueLenBucket.M)));
        var qp = extractor.extract(a).candidates().query().get(0);
        assertThat(qp.name()).isEqualTo("password"); // 이름 보존
        assertThat(qp.sensitive()).isTrue();
        assertThat(qp.lenBuckets()).isEmpty();        // 값 길이 버킷 억제(REDACTED)
    }

    @Test
    void perEndpointParamCapDropsLowestCount() {
        var props = new NormalizationProperties(5000, 2, 0.3, 20, 0.7, new int[]{8, 32, 128});
        var capExtractor = new ParamCandidateExtractor(
                new SensitiveKeyMatcher(SensitiveKeyProperties.defaults()), props);
        Acc a = acc("/x");
        a.add(req(new QueryParamObs("p1", ValueLenBucket.S)));
        a.add(req(new QueryParamObs("p1", ValueLenBucket.S), new QueryParamObs("p2", ValueLenBucket.S)));
        a.add(req(new QueryParamObs("p1", ValueLenBucket.S), new QueryParamObs("p2", ValueLenBucket.S),
                new QueryParamObs("p3", ValueLenBucket.S)));

        var res = capExtractor.extract(a); // count: p1=3, p2=2, p3=1 → cap 2 → p3 drop
        assertThat(res.candidates().query()).hasSize(2);
        assertThat(res.droppedParams()).isEqualTo(1);
        assertThat(res.candidates().query()).extracting(QueryParam::name)
                .containsExactlyInAnyOrder("p1", "p2");
    }
}
