// CombinedDiscoveryController 단위 테스트 — /discovery 요약(summary+apis) · /discovery/detail 원본 (사용자 요청)
package com.pentasecurity.apidiscover.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.DiscoverySummaryView;
import com.pentasecurity.apidiscover.batch.CombinedDiscoveryService;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.model.SpecSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class CombinedDiscoveryControllerTest {

    private static final String HOST = "api.example.com";
    private final CombinedDiscoveryService service = mock(CombinedDiscoveryService.class);
    private final CombinedDiscoveryController controller = new CombinedDiscoveryController(service);

    @Test
    void discoverySummarizesFindingsByClassificationWithApiLists() {
        when(service.forHost(HOST)).thenReturn(combined(List.of(
                new Finding.Shadow(HOST, "GET", "/stats", 0.9, "r", ParamCandidates.EMPTY),
                new Finding.Shadow(HOST, "POST", "/clips", 1.0, "r", ParamCandidates.EMPTY),
                new Finding.Active(HOST, "GET", "/v2/users", "ref"))));

        DiscoverySummaryView v = controller.discovery(HOST);

        assertThat(v.host()).isEqualTo(HOST);
        assertThat(v.summary().discovered()).isEqualTo(3); // 전체 finding
        assertThat(v.summary().shadow()).isEqualTo(2);
        assertThat(v.summary().active()).isEqualTo(1);
        assertThat(v.apis().discovered()).containsExactly(
                "GET [https://api.example.com/stats]",
                "POST [https://api.example.com/clips]",
                "GET [https://api.example.com/v2/users]");
        assertThat(v.apis().shadow()).containsExactly(
                "GET [https://api.example.com/stats]", "POST [https://api.example.com/clips]");
        assertThat(v.apis().active()).containsExactly("GET [https://api.example.com/v2/users]");
        assertThat(v.apis().zombie()).isEmpty();
        assertThat(v.apis().unused()).isEmpty();
    }

    @Test
    void discoveryDetailReturnsRawCombined() {
        CombinedDiscovery expected = combined(List.of());
        when(service.forHost(HOST)).thenReturn(expected);

        assertThat(controller.discoveryDetail(HOST)).isSameAs(expected); // 상세=forHost 원본 그대로
    }

    private static CombinedDiscovery combined(List<Finding> findings) {
        return new CombinedDiscovery(HOST, 0L, SpecMergeStrategy.MERGE, findings, List.of(), SpecSource.EMPTY);
    }
}
