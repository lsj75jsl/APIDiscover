// 3종 포맷 Canonical 동일성 — OpenAPI/Postman/CSV → (method,host,template,deprecated,version) 동일 (doc/14 §5, §6)
package com.pentasecurity.apidiscover.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ThreeFormatEquivalenceTest {

    // 동일 논리 스펙: host api.example.com, basePath /v2, version 2.0.0, 4 endpoint(1 deprecated, {id} var)
    private static final String OPENAPI = """
            openapi: 3.0.1
            info:
              title: Shop
              version: 2.0.0
            servers:
              - url: https://api.example.com/v2
            paths:
              /users/{id}:
                get:
                  operationId: getUser
                  responses: { '200': { description: ok } }
              /users:
                post:
                  operationId: createUser
                  responses: { '200': { description: ok } }
              /orders/{orderId}:
                get:
                  operationId: getOrder
                  deprecated: true
                  responses: { '200': { description: ok } }
              /health:
                get:
                  operationId: health
                  responses: { '200': { description: ok } }
            """;

    private static final String POSTMAN = """
            {
              "info": { "name": "Shop", "version": "2.0.0",
                "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
              "variable": [ { "key": "baseUrl", "value": "https://api.example.com" } ],
              "item": [
                { "name": "Get User", "request": { "method": "GET",
                    "url": { "host": ["{{baseUrl}}"], "path": ["v2","users",":id"] } } },
                { "name": "Create User", "request": { "method": "POST",
                    "url": { "host": ["{{baseUrl}}"], "path": ["v2","users"] } } },
                { "name": "Get Order [DEPRECATED]", "request": { "method": "GET",
                    "url": { "host": ["{{baseUrl}}"], "path": ["v2","orders",":orderId"] } } },
                { "name": "Health", "request": { "method": "GET",
                    "url": { "host": ["{{baseUrl}}"], "path": ["v2","health"] } } }
              ]
            }
            """;

    private static final String CSV = """
            method,path,host,deprecated,version
            GET,/v2/users/:id,api.example.com,false,2.0.0
            POST,/v2/users,api.example.com,false,2.0.0
            GET,/v2/orders/:orderId,api.example.com,true,2.0.0
            GET,/v2/health,api.example.com,false,2.0.0
            """;

    private static final Set<String> EXPECTED = Set.of(
            "GET|api.example.com|/v2/users/{id}|false|2.0.0",
            "POST|api.example.com|/v2/users|false|2.0.0",
            "GET|api.example.com|/v2/orders/{orderId}|true|2.0.0",
            "GET|api.example.com|/v2/health|false|2.0.0");

    @Test
    void threeFormatsProduceEquivalentCanonical() {
        Set<String> openapi = tuples(new OpenApiSpecParser().parse(bytes(OPENAPI)).endpoints());
        Set<String> postman = tuples(new PostmanSpecParser(new ObjectMapper()).parse(bytes(POSTMAN)).endpoints());
        Set<String> csv = tuples(new CsvSpecParser().parse(bytes(CSV)).endpoints());

        // sourceRef(포맷별 provenance) 제외, (method,host,template,deprecated,version) 동일
        assertThat(openapi).isEqualTo(EXPECTED);
        assertThat(postman).isEqualTo(EXPECTED);
        assertThat(csv).isEqualTo(EXPECTED);
    }

    /** SpecCanonicalizer 적용 후 (method,host,template,deprecated,version) 튜플 집합. */
    private static Set<String> tuples(List<CanonicalEndpoint> list) {
        return SpecCanonicalizer.canonicalize(list).stream()
                .map(e -> e.method() + "|" + e.host() + "|" + e.pathTemplate()
                        + "|" + e.deprecated() + "|" + e.version())
                .collect(Collectors.toSet());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
