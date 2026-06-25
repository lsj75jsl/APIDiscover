// EndpointMatcher 단위 테스트 (doc/04 §2)
package com.pentasecurity.apidiscover.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class EndpointMatcherTest {

    private static CanonicalEndpoint ep(String method, String template, String host) {
        return new CanonicalEndpoint(method, template, host, false, null, template);
    }

    private final EndpointMatcher matcher = new EndpointMatcher(List.of(
            ep("GET", "/users/{id}", null),
            ep("GET", "/users/me", null),
            ep("GET", "/v2/orders/{orderId}/items", null),
            ep("GET", "/ping", "api.example.com"),
            ep("GET", "/health", null)));

    @Test
    void matchesVariableSegment() {
        assertThat(matcher.match("GET", "any", "/users/123"))
                .map(CanonicalEndpoint::pathTemplate)
                .contains("/users/{id}");
    }

    @Test
    void staticSegmentWinsOverVariable() {
        // /users/me 는 /users/{id} 와 /users/me 둘 다 매칭되지만 정적이 승리 (doc/04 §2.4)
        assertThat(matcher.match("GET", "any", "/users/me"))
                .map(CanonicalEndpoint::pathTemplate)
                .contains("/users/me");
    }

    @Test
    void trailingSlashIsIgnored() {
        assertThat(matcher.match("GET", "any", "/users/123/"))
                .map(CanonicalEndpoint::pathTemplate)
                .contains("/users/{id}");
    }

    @Test
    void segmentCountMustMatch() {
        assertThat(matcher.match("GET", "any", "/users/123/extra")).isEmpty();
        assertThat(matcher.match("GET", "any", "/users")).isEmpty();
    }

    @Test
    void methodMustMatch() {
        assertThat(matcher.match("POST", "any", "/users/123")).isEmpty();
    }

    @Test
    void multiSegmentVariableTemplate() {
        assertThat(matcher.match("GET", "any", "/v2/orders/A99/items"))
                .map(CanonicalEndpoint::pathTemplate)
                .contains("/v2/orders/{orderId}/items");
    }

    @Test
    void hostSpecificMatchesOnlyItsHost() {
        assertThat(matcher.match("GET", "api.example.com", "/ping"))
                .map(CanonicalEndpoint::pathTemplate)
                .contains("/ping");
        assertThat(matcher.match("GET", "other.com", "/ping")).isEmpty();
    }

    @Test
    void hostAgnosticMatchesAnyHost() {
        assertThat(matcher.match("GET", "whatever.com", "/health"))
                .map(CanonicalEndpoint::pathTemplate)
                .contains("/health");
    }

    // doc/27 §3 — base-path-strip at-match: as-is 우선 + 미매칭 시 strip prefix 재부착(prepend) 1회 재시도.
    @Test
    void stripPrefixReattachesAndMatchesProxyStrippedPath() {
        var m = new EndpointMatcher(List.of(ep("GET", "/v2/users/{id}", null)));
        // 프록시가 /v2 strip → 관측 /users/1 → as-is 미매칭, prefix 재부착(/v2/users/1)→매칭(false Shadow 해소)
        assertThat(m.match("GET", "any", "/users/1", "/v2"))
                .map(CanonicalEndpoint::pathTemplate).contains("/v2/users/{id}");
    }

    @Test
    void asIsMatchTakesPriorityOverStripRetry() {
        var m = new EndpointMatcher(List.of(ep("GET", "/v2/users/{id}", null)));
        // 비-strip 트래픽 /v2/users/1 은 as-is 매칭 → 재부착(double-prefix) 미발동
        assertThat(m.match("GET", "any", "/v2/users/1", "/v2"))
                .map(CanonicalEndpoint::pathTemplate).contains("/v2/users/{id}");
    }

    @Test
    void nullStripPrefixIsAsIsOnly() {
        var m = new EndpointMatcher(List.of(ep("GET", "/v2/users/{id}", null)));
        assertThat(m.match("GET", "any", "/users/1", null)).isEmpty(); // 현행 무회귀: strip 없이 미매칭
    }

    @Test
    void wrongStripPrefixDoesNotCreateSpuriousMatch() {
        var m = new EndpointMatcher(List.of(ep("GET", "/v2/users/{id}", null)));
        // 잘못된 prefix /api → /api/users/1 도 /v2/users/{id} 매칭 안 됨 (임의 새 오판 없음, opt-in 한정)
        assertThat(m.match("GET", "any", "/users/1", "/api")).isEmpty();
    }

    // doc/04 §7 case3 — 동일 path, 다른 method → method 포함 시그니처로 각자 distinct 매칭.
    // 기존 methodMustMatch 는 미정의 method 의 mismatch 만 검증 → 양 method 정의 시 정확 분리 매칭을 잠근다.
    @Test
    void sameTemplateDistinctMethodsMatchSeparately() {
        var m = new EndpointMatcher(List.of(
                ep("GET", "/orders/{id}", null),
                ep("POST", "/orders/{id}", null)));
        // 동일 템플릿이라 method() 로 구분 — 각 관측 method 가 자기 operation 에만 매칭
        assertThat(m.match("GET", "any", "/orders/9")).map(CanonicalEndpoint::method).contains("GET");
        assertThat(m.match("POST", "any", "/orders/9")).map(CanonicalEndpoint::method).contains("POST");
    }

    // doc/04 §7 case4 — specificity 앞 세그먼트 우선(§2.4) + 동률 결정성.
    // 기존 staticSegmentWinsOverVariable 은 마지막 세그먼트 정적 1건만 → front-segment 우선·동률 동작을 잠근다.
    @Test
    void specificityFrontSegmentPriorityAndTie() {
        // (a) 앞 세그먼트 우선: staticCount 동률(1=1)이라도 앞 세그먼트 정적(/api)이 변수를 이긴다.
        var frontSeg = new EndpointMatcher(List.of(
                ep("GET", "/{tenant}/config", null), // specificity [0,1]
                ep("GET", "/api/{key}", null)));      // specificity [1,0]
        // /api/config 가 둘 다 매칭하지만 앞 세그먼트 정적 우선 → /api/{key} (staticCount 만으로는 동률이라 갈리지 않음)
        assertThat(frontSeg.match("GET", "any", "/api/config"))
                .map(CanonicalEndpoint::pathTemplate).contains("/api/{key}");

        // (b) 동률(동일 specificity): 더 구체적인 후보 부재 → 먼저 정의된 것이 결정적으로 승리(무crash)
        var tie = new EndpointMatcher(List.of(
                ep("GET", "/a/{x}", null),
                ep("GET", "/a/{y}", null))); // 둘 다 [1,0]
        assertThat(tie.match("GET", "any", "/a/1"))
                .map(CanonicalEndpoint::pathTemplate).contains("/a/{x}");
    }
}
