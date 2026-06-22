// SpecFormatDetector 단위 테스트 (doc/03 §5)
package com.pentasecurity.apidiscover.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SpecFormatDetectorTest {

    private final SpecFormatDetector detector = new SpecFormatDetector();

    @Test
    void detectsOpenApi() {
        String yaml = "openapi: 3.0.1\ninfo:\n  title: t\n  version: '1'\npaths: {}\n";
        assertThat(detector.detect(bytes(yaml))).isEqualTo(SpecFormat.OPENAPI);
    }

    @Test
    void detectsSwagger2() {
        String yaml = "swagger: '2.0'\ninfo:\n  title: t\n  version: '1'\npaths: {}\n";
        assertThat(detector.detect(bytes(yaml))).isEqualTo(SpecFormat.OPENAPI);
    }

    @Test
    void detectsCsvByHeader() {
        String csv = "method,path,host,deprecated\nGET,/users/{id},api.example.com,false\n";
        assertThat(detector.detect(bytes(csv))).isEqualTo(SpecFormat.CSV);
    }

    @Test
    void detectsPostmanBySchema() {
        String json = """
                { "info": { "name": "x",
                  "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
                  "item": [] }
                """;
        assertThat(detector.detect(bytes(json))).isEqualTo(SpecFormat.POSTMAN);
    }

    @Test
    void throwsOnUnknown() {
        assertThatThrownBy(() -> detector.detect(bytes("this is not a spec")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
