// ScanController 단위 테스트 — /scan-status latestSpec(M4) + /result rationale·ETag(M5) + /scan-now 동기스캔(A1) (doc/35)
package com.pentasecurity.apidiscover.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.ScanStatusView;
import com.pentasecurity.apidiscover.batch.CombinedDiscoveryService;
import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
import com.pentasecurity.apidiscover.batch.DomainRegistrar;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.domain.SpecMetaProjection;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.model.ApiBasis;
import com.pentasecurity.apidiscover.model.Classification;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import com.pentasecurity.apidiscover.model.EndpointRationale;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.model.SpecSource;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ScanControllerTest {

    private static final String HOST = "api.example.com";

    private final ScanResultRepository scanRepo = mock(ScanResultRepository.class);
    private final DiscoveryJobService jobService = mock(DiscoveryJobService.class);
    private final SpecStore specStore = mock(SpecStore.class);
    private final CombinedDiscoveryService combined = mock(CombinedDiscoveryService.class);
    private final DomainRegistrar registrar = mock(DomainRegistrar.class);
    private final DomainConfigRepository domainRepo = mock(DomainConfigRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScanController controller =
            new ScanController(scanRepo, jobService, specStore, combined, registrar, domainRepo, objectMapper);

    @Test
    void statusIncludesLatestSpecFilenameAndApiCount() {
        when(scanRepo.findById(HOST)).thenReturn(Optional.of(scan()));
        when(specStore.latestSpecMeta(HOST)).thenReturn(Optional.of(spec("users-api.yaml", 18)));

        ScanStatusView view = controller.status(HOST);

        assertThat(view.latestSpec().filename()).isEqualTo("users-api.yaml");
        assertThat(view.latestSpec().endpointCount()).isEqualTo(18); // 추출 API 수
        assertThat(view.latestSpec().uploadedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void statusLatestSpecNullWhenNoSpec() {
        when(scanRepo.findById(HOST)).thenReturn(Optional.of(scan()));
        when(specStore.latestSpecMeta(HOST)).thenReturn(Optional.empty());

        assertThat(controller.status(HOST).latestSpec()).isNull(); // 스펙 없음=null(무회귀)
    }

    // --- M5: GET /result serve-time 판단근거를 각 finding 에 인라인(ⓒ) ---

    @Test
    void resultInlinesBasisIntoMatchingFindingsPreservingReportFieldsAndEtag() throws Exception {
        ScanResult r = scan();
        // findings: 매칭(/v2/users/{id}) 1건 + 비매칭(/other) 1건 — 매칭만 basis 인라인
        r.setReportJson("{\"host\":\"api.example.com\",\"findings\":["
                + "{\"host\":\"api.example.com\",\"method\":\"GET\",\"pathTemplate\":\"/v2/users/{id}\",\"confidence\":1.0,\"reason\":\"문서 매칭\"},"
                + "{\"host\":\"api.example.com\",\"method\":\"GET\",\"pathTemplate\":\"/other\",\"confidence\":0.6}]}");
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
        JsonNode matched = body.get("findings").get(0);
        assertThat(matched.get("confidence").asDouble()).isEqualTo(1.0);        // 기존 finding 필드 불변
        assertThat(matched.get("classification").asText()).isEqualTo("ACTIVE"); // ⓒ 인라인
        assertThat(matched.get("reason").asText()).isEqualTo("문서 매칭");        // reason 보존
        assertThat(matched.get("basis").get("type").asText()).isEqualTo("spec_match");
        // reason 은 classification 뒤로 재배치(사용자 요청 순서)
        List<String> order = fieldOrder(matched);
        assertThat(order.indexOf("reason")).isGreaterThan(order.indexOf("classification"));
        JsonNode unmatched = body.get("findings").get(1);
        assertThat(unmatched.has("basis")).isFalse();                           // 매칭 없으면 미가산
        assertThat(body.has("rationale")).isFalse();                            // 별도 배열 제거(인라인 대체)
    }

    // --- scan-status 유형별 API 목록(사용자 요청) ---

    @Test
    void statusIncludesPerCategoryApiListsFromReportFindings() {
        ScanResult r = scan();
        r.setReportJson("{\"host\":\"api.example.com\",\"findings\":["
                + "{\"host\":\"api.example.com\",\"method\":\"GET\",\"pathTemplate\":\"/stats\",\"confidence\":0.9},"
                + "{\"host\":\"api.example.com\",\"method\":\"POST\",\"pathTemplate\":\"/v2/users\",\"confidence\":1.0}]}");
        when(scanRepo.findById(HOST)).thenReturn(Optional.of(r));
        when(specStore.latestSpecMeta(HOST)).thenReturn(Optional.empty());
        when(combined.forHost(HOST)).thenReturn(combinedWith(List.of(
                new EndpointRationale("GET", HOST, "/stats", Classification.SHADOW,
                        new ApiBasis.SpecMatchBasis("ref", false, false)),
                new EndpointRationale("POST", HOST, "/v2/users", Classification.ACTIVE,
                        new ApiBasis.SpecMatchBasis("ref", false, false)))));

        var apis = controller.status(HOST).apis();

        assertThat(apis.discovered()).containsExactly(
                "GET [https://api.example.com/stats]", "POST [https://api.example.com/v2/users]");
        assertThat(apis.shadow()).containsExactly("GET [https://api.example.com/stats]");
        assertThat(apis.active()).containsExactly("POST [https://api.example.com/v2/users]");
        assertThat(apis.zombie()).isEmpty();
        assertThat(apis.unused()).isEmpty();
    }

    @Test
    void statusApiListsEmptyWhenNoReportAndSkipsForHost() {
        when(scanRepo.findById(HOST)).thenReturn(Optional.of(scan())); // reportJson null
        when(specStore.latestSpecMeta(HOST)).thenReturn(Optional.empty());

        var apis = controller.status(HOST).apis();

        assertThat(apis.discovered()).isEmpty();
        assertThat(apis.shadow()).isEmpty();
        verify(combined, never()).forHost(any()); // 미스캔=forHost 미호출(경량 유지)
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

    // --- A1: POST /scan-now 동기 즉시 스캔 (전부 mock, 운영 Loki 미호출) ---

    @Test
    void scanNowAutoRegistersScansAndReturnsCombined() {
        LogWindow w = new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
        when(registrar.registerIfAbsent(HOST)).thenReturn(domainConfig());
        when(jobService.onDemandWindow(any())).thenReturn(w);
        when(jobService.scanOnDemand(eq(HOST), eq(w), isNull())).thenReturn(scan());
        CombinedDiscovery expected = combinedWith(List.of());
        when(combined.forHost(HOST)).thenReturn(expected);

        CombinedDiscovery result = controller.scanNow(HOST, null);

        assertThat(result).isSameAs(expected);                 // /discovery 일관 결과 반환
        verify(registrar).registerIfAbsent(HOST);              // 미등록 자동등록
        verify(jobService).scanOnDemand(HOST, w, null);        // watermark 미전진 경로
        verify(domainRepo).markActive(eq(HOST), any());        // D82: 수동 스캔 = ACTIVE 승격
    }

    @Test
    void scanNowIsIdempotentForExistingDomain() {
        // registerIfAbsent 멱등 — 기존 도메인도 중복 등록 없이 스캔/반환(registrar 가 멱등 보장)
        when(registrar.registerIfAbsent(HOST)).thenReturn(domainConfig());
        when(jobService.onDemandWindow(any())).thenReturn(new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(60)));
        when(jobService.scanOnDemand(eq(HOST), any(), isNull())).thenReturn(scan());
        when(combined.forHost(HOST)).thenReturn(combinedWith(List.of()));

        assertThat(controller.scanNow(HOST, null)).isNotNull();
        verify(registrar).registerIfAbsent(HOST);
    }

    @Test
    void scanNowNormalizesHostBeforeRegisterAndScan() {
        LogWindow w = new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
        when(registrar.registerIfAbsent(HOST)).thenReturn(domainConfig());
        when(jobService.onDemandWindow(any())).thenReturn(w);
        when(jobService.scanOnDemand(eq(HOST), eq(w), isNull())).thenReturn(scan());
        when(combined.forHost(HOST)).thenReturn(combinedWith(List.of()));

        controller.scanNow("API.Example.COM", null); // 대문자 → 정규화 "api.example.com"

        verify(registrar).registerIfAbsent(HOST);
        verify(jobService).scanOnDemand(HOST, w, null);
        verify(combined).forHost(HOST);
    }

    @Test
    void scanNowPassesWindowToOnDemandWindow() {
        Duration window = Duration.ofHours(2);
        when(registrar.registerIfAbsent(HOST)).thenReturn(domainConfig());
        when(jobService.onDemandWindow(window)).thenReturn(new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(7200)));
        when(jobService.scanOnDemand(eq(HOST), any(), isNull())).thenReturn(scan());
        when(combined.forHost(HOST)).thenReturn(combinedWith(List.of()));

        controller.scanNow(HOST, window);

        verify(jobService).onDemandWindow(window); // window 파라미터 위임(기본은 onDemandWindow 내부 max-window)
    }

    @Test
    void scanNowBlankHostReturns400() {
        assertThatThrownBy(() -> controller.scanNow("  ", null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(registrar, never()).registerIfAbsent(any());
    }

    @Test
    void scanNowMapsLokiFailureToBadGateway() {
        when(registrar.registerIfAbsent(HOST)).thenReturn(domainConfig());
        when(jobService.onDemandWindow(any())).thenReturn(new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(60)));
        when(jobService.scanOnDemand(any(), any(), any())).thenThrow(new IllegalStateException("Loki query failed"));

        assertThatThrownBy(() -> controller.scanNow(HOST, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    /** JSON object 필드의 직렬화 순서(순서 검증용). */
    private static List<String> fieldOrder(JsonNode obj) {
        List<String> names = new java.util.ArrayList<>();
        obj.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static DomainConfig domainConfig() {
        DomainConfig d = new DomainConfig();
        d.setHost(HOST);
        return d;
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

    private static SpecMetaProjection spec(String filename, int endpointCount) {
        // ★REST 메타는 projection(rawDoc oid 미접근, doc/28) — latestSpecMeta 가 SpecMetaProjection 반환
        return new SpecMetaProjection(SpecFormat.OPENAPI, 1L, endpointCount, Instant.EPOCH, "default", filename, true);
    }
}
