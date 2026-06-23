// SpecStore 업로드/버전관리/로드 단위 테스트 (doc/03 §7). repo 는 Mockito mock
package com.pentasecurity.apidiscover.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.domain.SpecRecordRepository;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpecStoreTest {

    private static final String HOST = "api.example.com";
    private static final byte[] OPENAPI = ("""
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
            """).getBytes(StandardCharsets.UTF_8);

    private final SpecRecordRepository repo = mock(SpecRecordRepository.class);
    private final SpecStore store = new SpecStore(
            repo,
            new SpecFormatDetector(),
            new ObjectMapper(),
            List.of(new OpenApiSpecParser(), new PostmanSpecParser(new ObjectMapper()), new CsvSpecParser()));

    @Test
    void firstUploadCreatesActiveVersionOne() {
        when(repo.findFirstByHostOrderBySpecVersionDesc(HOST)).thenReturn(Optional.empty());
        when(repo.findByHostAndActiveIsTrue(HOST)).thenReturn(List.of());
        when(repo.save(any(SpecRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        SpecRecord saved = store.upload(HOST, OPENAPI);

        assertThat(saved.specVersion).isEqualTo(1L);
        assertThat(saved.active).isTrue();
        assertThat(saved.format).isEqualTo(SpecFormat.OPENAPI);
        assertThat(saved.endpointCount).isEqualTo(2);
        assertThat(saved.canonicalJson).contains("/v2/users/{id}");
    }

    @Test
    void secondUploadIncrementsVersionAndDeactivatesPrevious() {
        SpecRecord prev = new SpecRecord();
        prev.host = HOST;
        prev.specVersion = 3L;
        prev.active = true;
        when(repo.findFirstByHostOrderBySpecVersionDesc(HOST)).thenReturn(Optional.of(prev));
        when(repo.findByHostAndActiveIsTrue(HOST)).thenReturn(List.of(prev));
        when(repo.save(any(SpecRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        SpecRecord saved = store.upload(HOST, OPENAPI);

        assertThat(saved.specVersion).isEqualTo(4L);
        assertThat(saved.active).isTrue();
        assertThat(prev.active).isFalse(); // 이전 활성 버전 비활성화
    }

    @Test
    void loadActiveCanonicalRoundTrips() {
        when(repo.findFirstByHostOrderBySpecVersionDesc(HOST)).thenReturn(Optional.empty());
        when(repo.findByHostAndActiveIsTrue(HOST)).thenReturn(List.of());
        when(repo.save(any(SpecRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        SpecRecord saved = store.upload(HOST, OPENAPI);

        when(repo.findFirstByHostAndActiveIsTrueOrderBySpecVersionDesc(eq(HOST)))
                .thenReturn(Optional.of(saved));

        List<CanonicalEndpoint> loaded = store.loadActiveCanonical(HOST);

        assertThat(loaded).hasSize(2);
        assertThat(loaded).extracting(CanonicalEndpoint::pathTemplate)
                .containsExactlyInAnyOrder("/v2/users/{id}", "/v2/v1/orders/{orderId}");
        assertThat(loaded).anyMatch(CanonicalEndpoint::deprecated);
    }
}
