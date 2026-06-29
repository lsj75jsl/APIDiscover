// ScanController 단위 테스트 — GET /scan-status 의 latestSpec(filename·API수) 보강 (doc/35 M4)
package com.pentasecurity.apidiscover.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.ScanStatusView;
import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScanControllerTest {

    private static final String HOST = "api.example.com";

    private final ScanResultRepository scanRepo = mock(ScanResultRepository.class);
    private final DiscoveryJobService jobService = mock(DiscoveryJobService.class);
    private final SpecStore specStore = mock(SpecStore.class);
    private final ScanController controller = new ScanController(scanRepo, jobService, specStore);

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
