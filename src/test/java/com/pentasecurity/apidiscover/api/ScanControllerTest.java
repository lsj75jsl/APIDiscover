// ScanController 단위 테스트 — /scan-status latestSpec(M4) + /result serve-time rationale·ETag 불변(M5) (doc/35)
package com.pentasecurity.apidiscover.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.ScanStatusView;
import com.pentasecurity.apidiscover.batch.CombinedDiscoveryService;
import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.model.ApiBasis;
import com.pentasecurity.apidiscover.model.Classification;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import com.pentasecurity.apidiscover.model.EndpointRationale;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.model.SpecSource;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScanControllerTest {

    private static final String HOST = "api.example.com";

    private final ScanResultRepository scanRepo = mock(ScanResultRepository.class);
    private final DiscoveryJobService jobService = mock(DiscoveryJobService.class);
    private final SpecStore specStore = mock(SpecStore.class);
    private final CombinedDiscoveryService combined = mock(CombinedDiscoveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScanController controller =
            new ScanController(scanRepo, jobService, specStore, combined, objectMapper);

    @Test
    void statusIncludesLatestSpecFilenameAndApiCount() {
        when(scanRepo.findById(HOST)).thenReturn(Optional.of(scan()));
        when(specStore.activeMeta(HOST)).thenReturn(Optional.of(spec("users-api.yaml", 18)));

        ScanStatusView view = controller.status(HOST);

        assertThat(view.latestSpec().filename()).isEqualTo("users-api.yaml");
        assertThat(view.latestSpec().endpointCount()).isEqualTo(18); // 추출 API 수
        assertThat(view.latestSpec().uploadedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void statusLatestSpecNullWhenNoSpec() {
        when(scanRepo.findById(HOST)).thenReturn(Optional.of(scan()));
        when(specStore.activeMeta(HOST)).thenReturn(Optional.empty());

        assertThat(controller.status(HOST).latestSpec()).isNull(); // 스펙 없음=null(무회귀)
    }

    // --- M5: GET /result serve-time rationale 가산 ---

    @Test
    void resultInjectsRationalePreservingReportFieldsAndEtag() throws Exception {
        ScanResult r = scan();
        r.setReportJson("{\"host\":\"api.example.com\",\"findings\":[]}");
        r.setVersion("v9");
        when(scanRepo.findById(HOST)).thenReturn(Optional.of(r));
        when(combined.forHost(HOST)).thenReturn(combinedWith(List.of(new EndpointRationale(
                "GET", HOST, "/v2/users/{id}", Classification.ACTIVE,
                new ApiBasis.SpecMatchBasis("ref", false, false)))));

        var resp = controller.result(HOST, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getETag()).isEqualTo("\"v9\""); // ETag=report version 유지
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("host").asText()).isEqualTo("api.example.com"); // report_json 기존 필드 불변
        assertThat(body.has("findings")).isTrue();
        assertThat(body.get("rationale").get(0).get("classification").asText()).isEqualTo("ACTIVE"); // 가산
        assertThat(body.get("rationale").get(0).get("basis").get("type").asText()).isEqualTo("spec_match");
    }

    @Test
    void resultNotModifiedSkipsRationaleRecompute() {
        ScanResult r = scan();
        r.setReportJson("{}");
        r.setVersion("v9");
        when(scanRepo.findById(HOST)).thenReturn(Optional.of(r));

        var resp = controller.result(HOST, "\"v9\""); // If-None-Match 일치

        assertThat(resp.getStatusCode().value()).isEqualTo(304);
        assertThat(resp.getBody()).isNull();
        verify(combined, never()).forHost(any()); // 304 는 rationale 재계산 안 함(ETag=report 만 추적)
    }

    @Test
    void resultNoReportReturns204() {
        when(scanRepo.findById(HOST)).thenReturn(Optional.of(scan())); // reportJson null
        assertThat(controller.result(HOST, null).getStatusCode().value()).isEqualTo(204);
    }

    private static CombinedDiscovery combinedWith(List<EndpointRationale> rationale) {
        return new CombinedDiscovery(HOST, 0L, SpecMergeStrategy.MERGE, List.of(), List.of(),
                SpecSource.EMPTY, null, rationale);
    }

    private static ScanResult scan() {
        ScanResult r = new ScanResult();
        r.setHost(HOST);
        r.setState("OK");
        r.setLastScanAt(Instant.EPOCH);
        r.setVersion("v1");
        return r;
    }

    private static SpecRecord spec(String filename, int endpointCount) {
        SpecRecord r = new SpecRecord();
        r.setHost(HOST);
        r.setSpecName("default");
        r.setFilename(filename);
        r.setFormat(SpecFormat.OPENAPI);
        r.setSpecVersion(1L);
        r.setEndpointCount(endpointCount);
        r.setUploadedAt(Instant.EPOCH);
        r.setActive(true);
        return r;
    }
}
