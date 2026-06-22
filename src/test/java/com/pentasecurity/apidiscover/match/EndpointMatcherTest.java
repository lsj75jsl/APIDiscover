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
}
