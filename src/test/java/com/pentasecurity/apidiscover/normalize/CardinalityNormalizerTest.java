// CardinalityNormalizer 승격/상한 단위 테스트 (doc/13 §1, §5)
package com.pentasecurity.apidiscover.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.NormalizationProperties;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.QueryParamObs;
import com.pentasecurity.apidiscover.model.TemplateSource;
import com.pentasecurity.apidiscover.model.ValueLenBucket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardinalityNormalizerTest {

    private static final String H = "api.example.com";

    private final CardinalityNormalizer norm = new CardinalityNormalizer(NormalizationProperties.defaults());

    private static ParsedRequest req() {
        return reqAt(Instant.EPOCH, 200);
    }

    private static ParsedRequest reqAt(Instant ts, int status, QueryParamObs... qps) {
        return new ParsedRequest("GET", "/x", List.of(qps), status, H, "ip", "ua",
                ts, 1, 1, true, null, null, null);
    }

    private static Acc acc(String template, int hits) {
        Acc a = new Acc("GET", H, template, TemplateSource.INFERRED);
        for (int i = 0; i < hits; i++) {
            a.add(req());
        }
        return a;
    }

    @Test
    void promotesHighCardinalitySlugAndRemerges() {
        List<Acc> in = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            // 휴리스틱이 못 잡은 slug. 형제마다 ts/status/query 를 달리해 mergeFrom 합산 검증
            int status = (i < 5) ? 404 : 200; // 5건 4xx, 20건 2xx
            Acc a = new Acc("GET", H, "/products/slug" + i + "/reviews", TemplateSource.INFERRED);
            a.add(reqAt(Instant.EPOCH.plusSeconds(i), status, new QueryParamObs("q", ValueLenBucket.S)));
            in.add(a);
        }
        CardinalityNormalizer.Result res = norm.normalize(in);

        assertThat(res.accs()).hasSize(1);
        Acc merged = res.accs().get(0);
        assertThat(merged.template()).isEqualTo("/products/{var}/reviews"); // 위치1 → {var}
        assertThat(merged.hits()).isEqualTo(25);                            // hits 합산
        assertThat(merged.queryObs().get("q").count).isEqualTo(25);         // queryObs 합산

        var m = merged.toEndpoint(EndpointKind.UNKNOWN, 0.0, ParamCandidates.EMPTY).metrics();
        assertThat(m.firstSeen()).isEqualTo(Instant.EPOCH);                 // min
        assertThat(m.lastSeen()).isEqualTo(Instant.EPOCH.plusSeconds(24));  // max
        assertThat(m.statusDist().get("2xx")).isEqualTo(20L);              // 합산
        assertThat(m.statusDist().get("4xx")).isEqualTo(5L);
        assertThat(res.droppedTemplates()).isZero();
    }

    @Test
    void promotionBoundaryAtMinDistinct() {
        // distinct=20 (정확히 statVarMinDistinct) → 승격
        List<Acc> at = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            at.add(acc("/c/slug" + i + "/r", 1));
        }
        assertThat(norm.normalize(at).accs()).hasSize(1);

        // distinct=19 (임계-1) → 미승격 (off-by-one 회귀 방어). ratio·수렴은 충족
        List<Acc> below = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            below.add(acc("/c/slug" + i + "/r", 1));
        }
        assertThat(norm.normalize(below).accs()).hasSize(19);
    }

    @Test
    void smallSampleNotPromoted() {
        List<Acc> in = new ArrayList<>();
        for (int i = 0; i < 5; i++) { // distinct 5 < 20 → 미승격(무회귀)
            in.add(acc("/products/slug" + i + "/reviews", 1));
        }
        assertThat(norm.normalize(in).accs()).hasSize(5);
    }

    @Test
    void lowRatioNotPromoted() {
        List<Acc> in = new ArrayList<>();
        for (int i = 0; i < 25; i++) { // distinct 25, 각 100 hits → ratio 0.01 < 0.3 → 미승격
            in.add(acc("/items/v" + i, 100));
        }
        assertThat(norm.normalize(in).accs()).hasSize(25);
    }

    @Test
    void convergenceGuardBlocksDominantValue() {
        // distinct=20, clusterHits=59, ratio=0.339≥0.3, distinct≥20 이지만 한 값(40)이 지배 → 수렴 0.32<0.7 → 미승격
        List<Acc> in = new ArrayList<>();
        in.add(acc("/orders/hot", 40));
        for (int i = 0; i < 19; i++) {
            in.add(acc("/orders/x" + i, 1));
        }
        assertThat(norm.normalize(in).accs()).hasSize(20);
    }

    @Test
    void hostTemplateCapDropsLowestHits() {
        var props = new NormalizationProperties(2, 50, 0.3, 20, 0.7, new int[]{8, 32, 128});
        var capNorm = new CardinalityNormalizer(props);
        List<Acc> in = new ArrayList<>(List.of(
                acc("/a", 100), acc("/b", 50), acc("/c", 10), acc("/d", 5), acc("/e", 1)));

        CardinalityNormalizer.Result res = capNorm.normalize(in);

        assertThat(res.accs()).hasSize(2);                  // 상한 2
        assertThat(res.droppedTemplates()).isEqualTo(3);    // 초과 3개 drop
        assertThat(res.accs()).extracting(Acc::template)
                .containsExactlyInAnyOrder("/a", "/b");      // hits 높은 순 보존
    }
}
