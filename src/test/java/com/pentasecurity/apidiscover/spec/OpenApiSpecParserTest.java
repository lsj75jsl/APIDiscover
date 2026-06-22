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
        List<CanonicalEndpoint> endpoints = parser.parse(OPENAPI_3.getBytes(StandardCharsets.UTF_8));

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
        List<CanonicalEndpoint> endpoints = parser.parse(OPENAPI_3.getBytes(StandardCharsets.UTF_8));

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
        List<CanonicalEndpoint> endpoints = parser.parse(noServer.getBytes(StandardCharsets.UTF_8));

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
}
