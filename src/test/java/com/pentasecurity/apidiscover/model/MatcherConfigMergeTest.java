// MatcherConfig.merge 단위 테스트 — 합집합 dedup + includeWebForms 상속 (doc/09 §4, §6)
package com.pentasecurity.apidiscover.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MatcherConfigMergeTest {

    private static MatcherConfig cfg(List<String> apiPrefixes, List<String> excludePrefixes,
                                     Boolean includeWebForms) {
        return new MatcherConfig(apiPrefixes, List.of(), excludePrefixes, List.of(), includeWebForms);
    }

    @Test
    void unionsListsWithDedupPreservingOrder() {
        var global = cfg(List.of("/a", "/b"), List.of("/x"), null);
        var domain = cfg(List.of("/b", "/c"), List.of("/y"), null);
        var merged = MatcherConfig.merge(global, domain);
        assertThat(merged.apiPathPrefixes()).containsExactly("/a", "/b", "/c"); // 전역 먼저, dedup
        assertThat(merged.excludePathPrefixes()).containsExactly("/x", "/y");
    }

    @Test
    void unionsRegexListsWithDedup() {
        // P3-4: regex list 4종 중 api/exclude regex 합집합·dedup 직접 검증
        var global = new MatcherConfig(List.of(), List.of("/a.*"), List.of(), List.of("x.*"), null);
        var domain = new MatcherConfig(List.of(), List.of("/b.*", "/a.*"), List.of(), List.of("y.*"), null);
        var merged = MatcherConfig.merge(global, domain);
        assertThat(merged.apiPathRegexes()).containsExactly("/a.*", "/b.*"); // 전역 먼저, dedup
        assertThat(merged.excludePathRegexes()).containsExactly("x.*", "y.*");
    }

    @Test
    void includeWebFormsDomainOverridesGlobal() {
        assertThat(MatcherConfig.merge(cfg(List.of(), List.of(), false),
                cfg(List.of(), List.of(), true)).includeWebForms()).isTrue();
        assertThat(MatcherConfig.merge(cfg(List.of(), List.of(), true),
                cfg(List.of(), List.of(), false)).includeWebForms()).isFalse();
    }

    @Test
    void includeWebFormsInheritsGlobalWhenDomainNull() {
        assertThat(MatcherConfig.merge(cfg(List.of(), List.of(), true),
                cfg(List.of(), List.of(), null)).includeWebForms()).isTrue();
    }

    @Test
    void includeWebFormsDefaultsFalseWhenBothNull() {
        assertThat(MatcherConfig.merge(cfg(List.of(), List.of(), null),
                cfg(List.of(), List.of(), null)).includeWebForms()).isFalse();
    }

    @Test
    void mergeTreatsNullArgsAsEmpty() {
        var domain = cfg(List.of("/api"), List.of(), true);
        var merged = MatcherConfig.merge(null, domain);
        assertThat(merged.apiPathPrefixes()).containsExactly("/api");
        assertThat(merged.includeWebForms()).isTrue();
    }

    @Test
    void constructorNormalizesNullListsToEmpty() {
        var c = new MatcherConfig(null, null, null, null, null);
        assertThat(c.apiPathPrefixes()).isEmpty();
        assertThat(c.excludePathRegexes()).isEmpty();
        assertThat(c.includeWebForms()).isNull(); // nullable 유지(상속)
    }
}
