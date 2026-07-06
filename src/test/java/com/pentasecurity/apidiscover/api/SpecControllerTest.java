// SpecController 단위 테스트 — PUT ?filename·GET /spec 복수(M6) (doc/35 M6)
package com.pentasecurity.apidiscover.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.domain.SpecMetaProjection;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import com.pentasecurity.apidiscover.spec.SpecStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecControllerTest {

    private static final String HOST = "api.example.com";

    private final SpecStore specStore = mock(SpecStore.class);
    private final SpecController controller = new SpecController(specStore);

    @Test
    void uploadPassesFilenameToStoreAndReturnsMeta() {
        when(specStore.upload(eq(HOST), any(byte[].class), eq("users-api.yaml")))
                .thenReturn(rec("default", "users-api.yaml", 1L));

        SpecMetaView view = controller.upload(HOST, "x".getBytes(StandardCharsets.UTF_8), "users-api.yaml");

        assertThat(view.filename()).isEqualTo("users-api.yaml");
        verify(specStore).upload(eq(HOST), any(byte[].class), eq("users-api.yaml"));
    }

    @Test
    void metaMapsProjectionsToViewsWithFilename() {
        // ★projection 조회(activeSpecMetas) — rawDoc oid 미접근. 정렬은 repo @Query(여기선 repo 순서 보존 매핑 검증)
        when(specStore.activeSpecMetas(HOST)).thenReturn(List.of(
                proj("orders", "o.yaml", 1L), proj("users", "u.yaml", 2L)));

        List<SpecMetaView> list = controller.meta(HOST);

        assertThat(list).extracting(SpecMetaView::specName).containsExactly("orders", "users");
        assertThat(list).extracting(SpecMetaView::filename).containsExactly("o.yaml", "u.yaml");
        assertThat(list).allSatisfy(v -> assertThat(v.active()).isTrue());
    }

    @Test
    void metaEmptyWhenNoActiveSpec() {
        when(specStore.activeSpecMetas(HOST)).thenReturn(List.of());
        assertThat(controller.meta(HOST)).isEmpty(); // 200 + [](404 아님)
    }

    @Test
    void invalidSpecMapsToBadRequestNotServerError() {
        // D70: 무효/미인식 문서(파싱 실패)는 400. 종전엔 IllegalArgumentException 이 uncaught → 500 으로 샜다.
        when(specStore.upload(eq(HOST), any(byte[].class), any()))
                .thenThrow(new IllegalArgumentException("invalid OpenAPI document: attribute openapi is missing"));

        assertThatThrownBy(() -> controller.upload(HOST, "bad".getBytes(StandardCharsets.UTF_8), "bad.json"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static SpecMetaProjection proj(String specName, String filename, long version) {
        return new SpecMetaProjection(SpecFormat.OPENAPI, version, 5, Instant.EPOCH, specName, filename, true);
    }

    private static SpecRecord rec(String specName, String filename, long version) {
        SpecRecord r = new SpecRecord();
        r.setHost(HOST);
        r.setSpecName(specName);
        r.setFilename(filename);
        r.setFormat(SpecFormat.OPENAPI);
        r.setSpecVersion(version);
        r.setEndpointCount(5);
        r.setUploadedAt(Instant.EPOCH);
        r.setActive(true);
        return r;
    }
}
