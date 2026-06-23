// MatcherConfig / weights map JSON 왕복 테스트 (doc/10 §2, §7). 손상 fail-fast 는 resolver 테스트
package com.pentasecurity.apidiscover.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatcherConfigJsonTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void matcherConfigRoundTripsListsAndNullableFlag() throws Exception {
        var cfg = new MatcherConfig(
                List.of("/api", "/internal/v2"),
                List.of("/svc/[a-z]+/data"),
                List.of("/legacy"),
                List.of(".*\\.js"),
                null); // nullable includeWebForms 보존 확인

        String json = om.writeValueAsString(cfg);
        MatcherConfig back = om.readValue(json, MatcherConfig.class);

        assertThat(back.apiPathPrefixes()).containsExactly("/api", "/internal/v2");
        assertThat(back.apiPathRegexes()).containsExactly("/svc/[a-z]+/data");
        assertThat(back.excludePathPrefixes()).containsExactly("/legacy");
        assertThat(back.excludePathRegexes()).containsExactly(".*\\.js");
        assertThat(back.includeWebForms()).isNull();
    }

    @Test
    void matcherConfigRoundTripsExplicitWebFormsFalse() throws Exception {
        var cfg = new MatcherConfig(List.of(), List.of(), List.of(), List.of(), false);
        MatcherConfig back = om.readValue(om.writeValueAsString(cfg), MatcherConfig.class);
        assertThat(back.includeWebForms()).isFalse();
    }

    @Test
    void absentListsDeserializeToEmpty() throws Exception {
        // 부분 JSON(매처 일부 필드만) → 누락 list 는 빈 list 로 정규화(compact ctor)
        MatcherConfig back = om.readValue("{\"apiPathPrefixes\":[\"/api\"]}", MatcherConfig.class);
        assertThat(back.apiPathPrefixes()).containsExactly("/api");
        assertThat(back.apiPathRegexes()).isEmpty();
        assertThat(back.excludePathPrefixes()).isEmpty();
        assertThat(back.includeWebForms()).isNull();
    }

    @Test
    void customWeightsMapRoundTrips() throws Exception {
        Map<String, Double> weights = Map.of("apiSegment", 0.9, "query", 0.7, "pathHint", 0.33);
        String json = om.writeValueAsString(weights);
        Map<String, Double> back = om.readValue(json, new TypeReference<Map<String, Double>>() {});
        assertThat(back).containsEntry("apiSegment", 0.9).containsEntry("query", 0.7).containsEntry("pathHint", 0.33);
    }
}
