// PostmanSpecParser 단위 테스트 (doc/14 §1, §6)
package com.pentasecurity.apidiscover.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostmanSpecParserTest {

    private final PostmanSpecParser parser = new PostmanSpecParser(new ObjectMapper());

    private static final String COLLECTION = """
            {
              "info": { "name": "Shop API", "version": "2.0.0",
                "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
              "variable": [ { "key": "baseUrl", "value": "https://api.example.com" } ],
              "item": [
                { "name": "Users", "item": [
                  { "name": "Get User", "request": {
                      "method": "get",
                      "url": { "host": ["{{baseUrl}}"], "path": ["v2","users",":id"] } } },
                  { "name": "Create User", "request": {
                      "method": "POST",
                      "url": "https://api.example.com/v2/users?active=true" } } ] },
                { "name": "Legacy [DEPRECATED]", "item": [
                  { "name": "Old List", "request": {
                      "method": "GET",
                      "url": { "host": ["{{baseUrl}}"], "path": ["v1","items"] } } } ] },
                { "name": "Search", "request": {
                    "method": "GET",
                    "description": "This endpoint is deprecated, use v2.",
                    "url": { "host": ["{{unknownHost}}"], "path": ["search","{{q}}"] } } },
                { "name": "Broken No Method", "request": { "url": { "path": ["broken"] } } }
              ]
            }
            """;

    private List<CanonicalEndpoint> parsed() {
        return parser.parse(COLLECTION.getBytes(StandardCharsets.UTF_8)).endpoints();
    }

    @Test
    void collectsWarningsForSkippedItems() {
        // doc/25 §A.1: "Broken No Method" item skip → warnings 수집(log 만 아님)
        SpecParseResult r = parser.parse(COLLECTION.getBytes(StandardCharsets.UTF_8));
        assertThat(r.warnings()).anyMatch(w -> w.contains("missing method"));
    }

    private CanonicalEndpoint byRef(List<CanonicalEndpoint> all, String ref) {
        return all.stream().filter(e -> e.sourceRef().equals(ref)).findFirst().orElseThrow();
    }

    @Test
    void dfsExtractsLeavesAndSkipsMethodless() {
        List<CanonicalEndpoint> all = parsed();
        // Broken(메서드 없음) skip → 4건
        assertThat(all).hasSize(4);
        assertThat(all).noneMatch(e -> e.sourceRef().contains("Broken"));
    }

    @Test
    void urlObjectPathArrayAndVarConversion() {
        CanonicalEndpoint getUser = byRef(parsed(), "postman#Users/Get User");
        assertThat(getUser.method()).isEqualTo("GET");                   // 대문자
        assertThat(getUser.pathTemplate()).isEqualTo("/v2/users/{id}");  // path 배열 join + :id→{id}
        assertThat(getUser.host()).isEqualTo("api.example.com");         // {{baseUrl}} 변수 치환
        assertThat(getUser.version()).isEqualTo("2.0.0");                // info.version
        assertThat(getUser.deprecated()).isFalse();
    }

    @Test
    void urlStringFormStripsSchemeHostAndQuery() {
        CanonicalEndpoint create = byRef(parsed(), "postman#Users/Create User");
        assertThat(create.method()).isEqualTo("POST");
        assertThat(create.pathTemplate()).isEqualTo("/v2/users"); // ?query 제거
        assertThat(create.host()).isEqualTo("api.example.com");
    }

    @Test
    void folderDeprecatedNameInheritedByChildren() {
        CanonicalEndpoint old = byRef(parsed(), "postman#Legacy [DEPRECATED]/Old List");
        assertThat(old.deprecated()).isTrue();          // 폴더 [DEPRECATED] 상속
        assertThat(old.pathTemplate()).isEqualTo("/v1/items");
    }

    @Test
    void unresolvedHostVariableBecomesNullAndDescriptionDeprecated() {
        CanonicalEndpoint search = byRef(parsed(), "postman#Search");
        assertThat(search.host()).isNull();                       // {{unknownHost}} 치환 실패 → null
        assertThat(search.pathTemplate()).isEqualTo("/search/{q}"); // {{q}}→{q}
        assertThat(search.deprecated()).isTrue();                 // description 키워드
    }

    @Test
    void multiElementHostArrayJoinsWithDots() {
        // P3-1: host 배열 ["api","example","com"] → api.example.com (변수 아님 → . join)
        String json = """
                { "info": { "name": "x", "version": "1" }, "item": [
                  { "name": "Ep", "request": { "method": "GET",
                      "url": { "host": ["api","example","com"], "path": ["v2","ping"] } } } ] }
                """;
        List<CanonicalEndpoint> all = parser.parse(json.getBytes(StandardCharsets.UTF_8)).endpoints();
        assertThat(all).singleElement().satisfies(e -> {
            assertThat(e.host()).isEqualTo("api.example.com");
            assertThat(e.pathTemplate()).isEqualTo("/v2/ping");
        });
    }

    @Test
    void urlMissingLeafSkippedRestParsed() {
        // P3-2: url 누락 leaf → skip + 나머지 정상
        String json = """
                { "info": { "name": "x", "version": "1" }, "item": [
                  { "name": "NoUrl", "request": { "method": "GET" } },
                  { "name": "Ok", "request": { "method": "GET", "url": { "path": ["ping"] } } } ] }
                """;
        List<CanonicalEndpoint> all = parser.parse(json.getBytes(StandardCharsets.UTF_8)).endpoints();
        assertThat(all).singleElement().satisfies(e -> {
            assertThat(e.sourceRef()).isEqualTo("postman#Ok");
            assertThat(e.pathTemplate()).isEqualTo("/ping");
        });
    }

    @Test
    void deprecatedMarkerVariantsRecognized() {
        // P3-3: 소문자 (deprecated)·대괄호 [deprecated] 표기도 deprecated=true
        String json = """
                { "info": { "name": "x", "version": "1" }, "item": [
                  { "name": "Old (deprecated)", "request": { "method": "GET", "url": { "path": ["a"] } } },
                  { "name": "Older [deprecated]", "request": { "method": "GET", "url": { "path": ["b"] } } } ] }
                """;
        List<CanonicalEndpoint> all = parser.parse(json.getBytes(StandardCharsets.UTF_8)).endpoints();
        assertThat(all).extracting(CanonicalEndpoint::deprecated).containsExactly(true, true);
    }

    @Test
    void rejectsNonObjectRootAndMissingItem() {
        assertThatThrownBy(() -> parser.parse("[]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("{\"info\":{}}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
