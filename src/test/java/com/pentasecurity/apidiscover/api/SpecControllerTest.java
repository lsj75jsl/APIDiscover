// SpecController 단위 테스트 — PUT ?filename·GET /spec 복수(M6) + /spec/changes 위임·status 파싱·host 400 (doc/35·36 M7a)
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
import com.pentasecurity.apidiscover.spec.SpecChanges;
import com.pentasecurity.apidiscover.spec.SpecDiffService;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SpecControllerTest {

    private static final String HOST = "api.example.com";

    private final SpecStore specStore = mock(SpecStore.class);
    private final SpecDiffService specDiffService = mock(SpecDiffService.class);
    private final SpecController controller = new SpecController(specStore, specDiffService);

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

    // --- /spec/changes (M7a) — 위임·status 파싱·host 정규화 ---

    @Test
    void changesNormalizesHostParsesStatusAndDelegates() {
        SpecChanges expected = new SpecChanges("api.example.com", List.of(), SpecChanges.SCOPE_DEPRECATED_VERSION_ONLY);
        when(specDiffService.changes(eq("api.example.com"), eq("users"), eq(1L), eq(3L), any())).thenReturn(expected);

        ArgumentCaptor<Set<String>> statusCap = ArgumentCaptor.forClass(Set.class);
        SpecChanges result = controller.changes("API.Example.COM", "users", 1L, 3L, "added, DELETED");

        assertThat(result).isSameAs(expected);
        verify(specDiffService).changes(eq("api.example.com"), eq("users"), eq(1L), eq(3L), statusCap.capture());
        assertThat(statusCap.getValue()).containsExactlyInAnyOrder("ADDED", "DELETED"); // 대문자·trim·분리
    }

    @Test
    void changesBlankHostReturns400() {
        assertThatThrownBy(() -> controller.changes("  ", null, null, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
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
