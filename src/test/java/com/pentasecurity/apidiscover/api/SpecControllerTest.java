// SpecController 단위 테스트 — PUT ?filename 수신·GET /spec 복수(filename·정렬·빈배열) (doc/35 M2/M6)
package com.pentasecurity.apidiscover.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
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
    void metaReturnsActiveDocumentsWithFilenameSortedDeterministically() {
        when(specStore.activeRecords(HOST)).thenReturn(List.of(
                rec("users", "u.yaml", 2L), rec("orders", "o.yaml", 1L)));

        List<SpecMetaView> list = controller.meta(HOST);

        assertThat(list).extracting(SpecMetaView::specName).containsExactly("orders", "users"); // specName asc 정렬
        assertThat(list).extracting(SpecMetaView::filename).containsExactly("o.yaml", "u.yaml");
        assertThat(list).allSatisfy(v -> assertThat(v.active()).isTrue());
    }

    @Test
    void metaEmptyWhenNoActiveSpec() {
        when(specStore.activeRecords(HOST)).thenReturn(List.of());
        assertThat(controller.meta(HOST)).isEmpty(); // 200 + [](404 아님)
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
