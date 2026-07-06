// OpenApiSpecParser 단위 테스트 (doc/03 §2)
package com.pentasecurity.apidiscover.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiSpecParserTest {

    private final OpenApiSpecParser parser = new OpenApiSpecParser();

    private static final String OPENAPI_3 = """
            openapi: 3.0.1
            info:
              title: Example API
              version: 1.2.3
            servers:
              - url: https://api.example.com/v2
            paths:
              /users/{id}:
                get:
                  operationId: getUserById
                  responses:
                    '200':
                      description: ok
              /v1/orders/{orderId}:
                get:
                  operationId: getOrderV1
                  deprecated: true
                  responses:
                    '200':
                      description: ok
            """;

    @Test
    void parsesPathsWithServerBasePathAndVersion() {
        List<CanonicalEndpoint> endpoints = parser.parse(OPENAPI_3.getBytes(StandardCharsets.UTF_8)).endpoints();

        assertThat(endpoints).hasSize(2);
        assertThat(endpoints).allSatisfy(e -> {
            assertThat(e.method()).isEqualTo("GET");
            assertThat(e.host()).isEqualTo("api.example.com");
            assertThat(e.version()).isEqualTo("1.2.3");
        });
        // servers 의 basePath(/v2)가 결합된다 (doc/03 §2.2)
        assertThat(endpoints).extracting(CanonicalEndpoint::pathTemplate)
                .containsExactlyInAnyOrder("/v2/users/{id}", "/v2/v1/orders/{orderId}");
    }

    @Test
    void detectsDeprecatedForZombieDetection() {
        List<CanonicalEndpoint> endpoints = parser.parse(OPENAPI_3.getBytes(StandardCharsets.UTF_8)).endpoints();

        CanonicalEndpoint orders = endpoints.stream()
                .filter(e -> e.pathTemplate().endsWith("/orders/{orderId}"))
                .findFirst().orElseThrow();
        assertThat(orders.deprecated()).isTrue();

        CanonicalEndpoint users = endpoints.stream()
                .filter(e -> e.pathTemplate().endsWith("/users/{id}"))
                .findFirst().orElseThrow();
        assertThat(users.deprecated()).isFalse();
        assertThat(users.sourceRef()).isEqualTo("openapi#getUserById");
    }

    @Test
    void hostAgnosticWhenNoServers() {
        String noServer = """
                openapi: 3.0.1
                info:
                  title: t
                  version: "1"
                paths:
                  /ping:
                    get:
                      responses:
                        '200':
                          description: ok
                """;
        List<CanonicalEndpoint> endpoints = parser.parse(noServer.getBytes(StandardCharsets.UTF_8)).endpoints();

        assertThat(endpoints).singleElement().satisfies(e -> {
            assertThat(e.host()).isNull();
            assertThat(e.pathTemplate()).isEqualTo("/ping");
        });
    }

    @Test
    void throwsOnInvalidDocument() {
        byte[] garbage = "this is not openapi".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(garbage))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // D70: Swagger 2.0 문서(host+basePath, parameters type)를 v2-converter 로 3.0 변환해 파싱.
    // OpenAPIV3Parser(3.x 전용) 사용 시 "attribute openapi is missing" 으로 실패했음(RED — OpenAPIParser 로 교체해 green).
    private static final String SWAGGER_2 = """
            swagger: "2.0"
            info:
              title: Legacy API
              version: "1.0.0"
            host: api.example.com
            basePath: /v1
            paths:
              /products:
                get:
                  responses:
                    '200':
                      description: ok
              /products/{id}:
                delete:
                  deprecated: true
                  parameters:
                    - name: id
                      in: path
                      required: true
                      type: integer
                  responses:
                    '204':
                      description: no content
            """;

    @Test
    void parsesSwagger2WithHostBasePathAndDeprecated() {
        List<CanonicalEndpoint> endpoints = parser.parse(SWAGGER_2.getBytes(StandardCharsets.UTF_8)).endpoints();

        assertThat(endpoints).hasSize(2);
        // host+basePath 결합 (2.0 → 3.0 변환 시 servers 로 승격)
        assertThat(endpoints).extracting(CanonicalEndpoint::pathTemplate)
                .containsExactlyInAnyOrder("/v1/products", "/v1/products/{id}");
        assertThat(endpoints).allSatisfy(e -> assertThat(e.host()).isEqualTo("api.example.com"));
        CanonicalEndpoint del = endpoints.stream()
                .filter(e -> e.method().equals("DELETE")).findFirst().orElseThrow();
        assertThat(del.deprecated()).isTrue();
    }
}
