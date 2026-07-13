// Acc 근사(HLL distinct / KLL 분위수) 단위 테스트 (doc/22 §6)
package com.pentasecurity.apidiscover.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.time.Instant;
import java.util.List;
import org.assertj.core.data.Offset;
import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.Test;

class AccTest {

    private static Acc acc() {
        return new Acc("GET", "h", "/x", TemplateSource.INFERRED);
    }

    private static ParsedRequest req(String ip, long ms) {
        return new ParsedRequest("GET", "/x", List.of(), 200, "h", ip,
                "ua", Instant.EPOCH, ms, 100, true, null, null, null);
    }

    /** 8.3 신호 세팅용 — status/응답CT/accept/xrw/origin/auth (doc/40 §6). */
    private static ParsedRequest req83(int status, String ct, String accept, String xrw,
                                       String origin, String auth) {
        return new ParsedRequest("GET", "/x", List.of(), status, "h", "1.1.1.1", "ua",
                Instant.EPOCH, 5, 100, true, null, null, null, null, ct, accept, xrw, origin, auth);
    }

    /** i → 고유 IP(10.0.y.z), i<65536 에서 distinct. */
    private static String ip(int i) {
        return "10.0." + ((i >> 8) & 255) + "." + (i & 255);
    }

    private static DiscoveredEndpoint.Metrics metrics(Acc a) {
        return a.toEndpoint(EndpointKind.UNKNOWN, 0.0, ParamCandidates.EMPTY).metrics();
    }

    @Test
    void distinctClientsExactForSmallN() {
        // HLL 소-N exact → shadowConfidence '<=1' 경계 무오차 (doc/22 §2)
        assertThat(metrics(acc()).distinctClients()).isZero();
        Acc one = acc();
        one.add(req("1.1.1.1", 5));
        one.add(req("1.1.1.1", 6)); // 중복 IP
        assertThat(metrics(one).distinctClients()).isEqualTo(1);
        Acc two = acc();
        two.add(req("1.1.1.1", 5));
        two.add(req("2.2.2.2", 6));
        assertThat(metrics(two).distinctClients()).isEqualTo(2);
    }

    @Test
    void hllEstimateWithinThreePercentForLargeDistinct() {
        Acc a = acc();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            a.add(req(ip(i), 5));
        }
        assertThat(metrics(a).distinctClients()).isCloseTo(n, Percentage.withPercentage(3));
    }

    @Test
    void kllQuantilesExactForSmallN() {
        // N<=k → KLL exact, INCLUSIVE = 기존 nearest-rank 와 동일
        Acc a = acc();
        for (long ms : new long[] {10, 20, 30, 40}) {
            a.add(req("1.1.1.1", ms));
        }
        assertThat(metrics(a).p50RespMs()).isEqualTo(20);
        assertThat(metrics(a).p95RespMs()).isEqualTo(40);
    }

    @Test
    void kllQuantileAccurateForLargeStream() {
        // 1..10000 균등 → p50≈5000·p95≈9500 (rank err≈1.65%, 여유 허용오차로 flake 방지)
        Acc a = acc();
        for (int v = 1; v <= 10_000; v++) {
            a.add(req("1.1.1.1", v));
        }
        var m = metrics(a);
        assertThat(m.p50RespMs()).isCloseTo(5000, Offset.offset(500L));
        assertThat(m.p95RespMs()).isCloseTo(9500, Offset.offset(500L));
    }

    @Test
    void emptySketchesYieldZero() {
        var m = metrics(acc());
        assertThat(m.distinctClients()).isZero();
        assertThat(m.p50RespMs()).isZero();
        assertThat(m.p95RespMs()).isZero();
    }

    // --- 8.3 응답 CT 분포 + 요청측 신호 다수결 (doc/40 §4.3·§6) ---

    @Test
    void contentTypeDistAccumulatesOnly2xxAndNormalizes() {
        Acc a = acc();
        a.add(req83(200, "application/json", null, null, null, null));
        a.add(req83(200, "application/JSON; charset=utf-8", null, null, null, null)); // 정규화 → application/json
        a.add(req83(404, "text/html", null, null, null, null)); // 4xx → 미누적(가드①)
        a.add(req83(302, "text/html", null, null, null, null)); // 3xx → 미누적
        assertThat(a.contentTypeDist()).hasSize(1).containsEntry("application/json", 2L);
    }

    @Test
    void authFailureHtmlDoesNotPolluteContentTypeDist() {
        // 401/403-only(2xx 없음) → dist 빈 → 폴백(doc/19 보존, §4.3 가드①)
        Acc a = acc();
        a.add(req83(401, "text/html", null, null, null, null));
        a.add(req83(403, "text/html", null, null, null, null));
        assertThat(a.contentTypeDist()).isEmpty();
    }

    @Test
    void requestSignalsFireOnlyOnMajority() {
        Acc a = acc();
        a.add(req83(200, null, "application/json", "XMLHttpRequest", "https://x", "bearer"));
        a.add(req83(200, null, "application/json", null, null, null));
        a.add(req83(200, null, null, null, null, null)); // 3 hits: accept 2/3, 나머지 1/3
        var d = a.toEndpoint(EndpointKind.UNKNOWN, 0.0, ParamCandidates.EMPTY);
        assertThat(d.acceptJson()).isTrue();       // 2*2=4 >= 3
        assertThat(d.xRequestedWith()).isFalse();  // 1*2=2 < 3
        assertThat(d.originHeader()).isFalse();     // 1/3
        assertThat(d.authScheme()).isFalse();       // 1/3
    }

    @Test
    void mergeUnionApproximatesSingleStream() {
        // 분할 스트림 union/merge ≈ 단일 스트림 (doc/22 §4). 짝/홀 분할(disjoint IP) → union=전체 distinct
        Acc single = acc();
        Acc part1 = acc();
        Acc part2 = acc();
        int n = 4000;
        for (int i = 0; i < n; i++) {
            ParsedRequest r = req(ip(i), 1 + (i % 1000));
            single.add(r);
            (i % 2 == 0 ? part1 : part2).add(r);
        }
        part1.mergeFrom(part2);
        var merged = metrics(part1);
        var whole = metrics(single);

        assertThat(merged.distinctClients())
                .isCloseTo(whole.distinctClients(), Percentage.withPercentage(3)); // HLL union ≈ 합집합
        assertThat(merged.p50RespMs()).isCloseTo(whole.p50RespMs(), Offset.offset(60L)); // KLL merge 분포 보존
    }
}
