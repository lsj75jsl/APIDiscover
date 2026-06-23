// SpecCanonicalizer 단위 테스트 — dedupe·deprecated OR·안정정렬 (doc/14 §0.1, §6)
package com.pentasecurity.apidiscover.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecCanonicalizerTest {

    @Test
    void dedupesAndOrsDeprecatedAndSortsStably() {
        List<CanonicalEndpoint> in = List.of(
                new CanonicalEndpoint("GET", "/users/{id}", "api.example.com", false, "1", "a"),
                new CanonicalEndpoint("GET", "/users/{id}", "api.example.com", true, "1", "b"),  // dup → OR
                new CanonicalEndpoint("POST", "/users", "api.example.com", false, "1", "c"),
                new CanonicalEndpoint("GET", "/orders", "api.example.com", false, "1", "d"));

        List<CanonicalEndpoint> out = SpecCanonicalizer.canonicalize(in);

        // dedupe: (GET, host, /users/{id}) 1건
        assertThat(out).hasSize(3);
        // 안정 정렬: (host, template, method) → /orders, /users, /users/{id}
        assertThat(out).extracting(CanonicalEndpoint::pathTemplate)
                .containsExactly("/orders", "/users", "/users/{id}");
        // deprecated OR: 중복 중 하나라도 true → true
        assertThat(out).filteredOn(e -> e.pathTemplate().equals("/users/{id}"))
                .singleElement().extracting(CanonicalEndpoint::deprecated).isEqualTo(true);
    }

    @Test
    void distinctHostsAndMethodsKeptSeparate() {
        List<CanonicalEndpoint> in = List.of(
                new CanonicalEndpoint("GET", "/x", "a.example.com", false, null, "r1"),
                new CanonicalEndpoint("GET", "/x", "b.example.com", false, null, "r2"),  // 다른 host
                new CanonicalEndpoint("POST", "/x", "a.example.com", false, null, "r3")); // 다른 method
        assertThat(SpecCanonicalizer.canonicalize(in)).hasSize(3); // 셋 다 distinct
    }
}
