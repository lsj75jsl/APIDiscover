// DomainController 등록 정규화 단위 테스트 — create/PUT host normalize·blank 400·중복 정규화 매칭 (doc/05 §2.2)
package com.pentasecurity.apidiscover.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.DomainUpsert;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.DomainView;
import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
import com.pentasecurity.apidiscover.domain.ClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.domain.SpecMetaProjection;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DomainControllerTest {

    private final DomainConfigRepository repo = mock(DomainConfigRepository.class);
    private final SpecStore specStore = mock(SpecStore.class);
    private final ScanResultRepository scanRepo = mock(ScanResultRepository.class);
    // 실 resolver(빈 config repo mock) → resolve=MIDDLE effective(non-null, toView 동작). doc/34 일관.
    private final EffectiveClassificationResolver resolver = new EffectiveClassificationResolver(
            mock(ClassificationConfigRepository.class), mock(DomainClassificationConfigRepository.class),
            new ObjectMapper());
    private final DomainController controller = new DomainController(repo, specStore, scanRepo, resolver);

    @Test
    void createNormalizesHostBeforeSave() {
        when(specStore.latestSpecMeta(any())).thenReturn(Optional.empty());
        when(repo.existsById("example.com")).thenReturn(false);
        when(repo.save(any(DomainConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = controller.create(upsert("  Example.COM  ")).getBody(); // 대문자+공백

        ArgumentCaptor<DomainConfig> saved = ArgumentCaptor.forClass(DomainConfig.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getHost()).isEqualTo("example.com"); // trim+lowercase 저장
        assertThat(view.host()).isEqualTo("example.com");
        verify(repo).existsById("example.com");                          // 중복검사도 정규화 키
    }

    @Test
    void createBlankOrDashHostIs400() {
        for (String h : new String[]{null, "", "   ", "-", "  -  "}) {
            assertThatThrownBy(() -> controller.create(upsert(h)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void createDuplicateMatchesByNormalizedHost() {
        when(repo.existsById("example.com")).thenReturn(true); // 기존(정규화) 존재
        assertThatThrownBy(() -> controller.create(upsert("EXAMPLE.com")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void updateLooksUpByNormalizedPathHost() {
        when(specStore.latestSpecMeta(any())).thenReturn(Optional.empty());
        DomainConfig existing = new DomainConfig();
        existing.setHost("example.com");
        when(repo.findById("example.com")).thenReturn(Optional.of(existing));
        when(repo.save(any(DomainConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.update("EXAMPLE.COM", upsert(null)); // path host 비정규화 → 정규화 키로 매칭

        verify(repo).findById("example.com"); // 404 아님
    }

    @Test
    void updateBlankPathHostIs400() {
        assertThatThrownBy(() -> controller.update("  -  ", upsert(null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void getLooksUpByNormalizedPathHost() {
        when(specStore.latestSpecMeta(any())).thenReturn(Optional.empty());
        DomainConfig existing = new DomainConfig();
        existing.setHost("example.com");
        when(repo.findById("example.com")).thenReturn(Optional.of(existing));

        var view = controller.get("EXAMPLE.COM"); // 대문자 경로 → 정규화 매칭(404 아님)

        assertThat(view.host()).isEqualTo("example.com");
        verify(repo).findById("example.com");
    }

    @Test
    void deleteUsesNormalizedPathHost() {
        when(repo.existsById("example.com")).thenReturn(true);

        controller.delete("EXAMPLE.com"); // 대문자 경로 → 정규화 키로 삭제

        verify(repo).existsById("example.com");
        verify(repo).deleteById("example.com");
    }

    // --- M1: GET /domains 페이지네이션 (배열 body + 헤더) ---

    @Test
    void listReturnsArrayBodyWithPaginationHeaders() {
        when(specStore.latestSpecMeta(any())).thenReturn(Optional.empty());
        // 전체 1500건 가정, page0 = 2건 샘플(헤더 정합만 검증)
        when(repo.findAll(any(Pageable.class))).thenReturn(
                new PageImpl<>(List.of(dc("a.example.com"), dc("b.example.com")),
                        PageRequest.of(0, 1000, Sort.by("host")), 1500));

        var resp = controller.list(0, 1000);

        assertThat(resp.getBody()).extracting(DomainView::host) // body=JSON 배열(페이지 객체 아님)
                .containsExactly("a.example.com", "b.example.com");
        assertThat(resp.getHeaders().getFirst("X-Total-Count")).isEqualTo("1500");
        assertThat(resp.getHeaders().getFirst("X-Total-Pages")).isEqualTo("2"); // ceil(1500/1000)
        assertThat(resp.getHeaders().getFirst("X-Current-Page")).isEqualTo("0");
    }

    @Test
    void listClampsSizeToMaxAndNegativePageToZero() {
        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        when(repo.findAll(cap.capture())).thenReturn(new PageImpl<>(List.of()));

        controller.list(-5, 9999); // page<0 → 0, size>1000 → 1000

        assertThat(cap.getValue().getPageNumber()).isEqualTo(0);
        assertThat(cap.getValue().getPageSize()).isEqualTo(1000);
        assertThat(cap.getValue().getSort()).isEqualTo(Sort.by("host")); // 결정적 정렬
    }

    @Test
    void listPageBeyondRangeIsEmptyArrayWithHeaders() {
        when(repo.findAll(any(Pageable.class))).thenReturn(
                new PageImpl<>(List.of(), PageRequest.of(5, 1000, Sort.by("host")), 1500));

        var resp = controller.list(5, 1000);

        assertThat(resp.getBody()).isEmpty();
        assertThat(resp.getHeaders().getFirst("X-Total-Count")).isEqualTo("1500");
        assertThat(resp.getHeaders().getFirst("X-Current-Page")).isEqualTo("5");
    }

    // --- M3: PUT /domains/{host} 부분수정(present-only) ---

    @Test
    void updateAppliesOnlyPresentFieldsKeepingOthers() {
        when(specStore.latestSpecMeta(any())).thenReturn(Optional.empty());
        DomainConfig existing = existing();
        when(repo.findById("example.com")).thenReturn(Optional.of(existing));
        when(repo.save(any(DomainConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        // enabled=false 만 전달, 나머지 null=유지
        var view = controller.update("example.com",
                new DomainUpsert(null, false, null, null, null, null));

        assertThat(view.enabled()).isFalse();                  // 명시 false 적용(미전달과 구분)
        assertThat(view.hostnames()).containsExactly("edge1"); // 미전달 → 유지
        assertThat(view.intervalOverride()).isEqualTo("PT1H"); // 미전달 → 유지
        assertThat(view.basePathStrip()).isEqualTo("/v2");     // 미전달 → 유지
    }

    @Test
    void updateEmptyHostnamesClearsButNullKeeps() {
        when(specStore.latestSpecMeta(any())).thenReturn(Optional.empty());
        when(repo.findById("example.com")).thenReturn(Optional.of(existing()));
        when(repo.save(any(DomainConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = controller.update("example.com",
                new DomainUpsert(null, null, List.of(), null, null, null)); // []=비우기(non-null)
        assertThat(view.hostnames()).isEmpty();
    }

    @Test
    void updateFullPayloadIsNoRegression() {
        when(specStore.latestSpecMeta(any())).thenReturn(Optional.empty());
        when(repo.findById("example.com")).thenReturn(Optional.of(existing()));
        when(repo.save(any(DomainConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = controller.update("example.com",
                new DomainUpsert(null, false, List.of("new"), "PT2H", SpecMergeStrategy.SEPARATE, "/api"));

        assertThat(view.enabled()).isFalse();
        assertThat(view.hostnames()).containsExactly("new");
        assertThat(view.intervalOverride()).isEqualTo("PT2H");
        assertThat(view.specMergeStrategy()).isEqualTo(SpecMergeStrategy.SEPARATE);
        assertThat(view.basePathStrip()).isEqualTo("/api");
    }

    @Test
    void createWithNullEnabledDefaultsToTrueAndEmptyHostnames() {
        when(specStore.latestSpecMeta(any())).thenReturn(Optional.empty());
        when(repo.existsById("example.com")).thenReturn(false);
        when(repo.save(any(DomainConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        // create 무회귀: enabled 미전달(null)→기본 true, hostnames 미전달→[]
        var view = controller.create(new DomainUpsert("example.com", null, null, null, null, null)).getBody();

        assertThat(view.enabled()).isTrue();
        assertThat(view.hostnames()).isEmpty();
    }

    // --- M2: GET /domains/{host} 단건 상세 보강 ---

    @Test
    void getReturnsDetailViewWithLastScanSpecAndEffectiveClassification() {
        DomainConfig d = new DomainConfig();
        d.setHost("example.com");
        d.setEnabled(true);
        when(repo.findById("example.com")).thenReturn(Optional.of(d));
        when(specStore.latestSpecMeta("example.com")).thenReturn(Optional.of(spec("users-api.yaml", 18)));
        ScanResult sr = new ScanResult();
        sr.setHost("example.com");
        sr.setLastScanAt(Instant.EPOCH);
        when(scanRepo.findById("example.com")).thenReturn(Optional.of(sr));

        var view = controller.get("example.com");

        assertThat(view.lastScanAt()).isEqualTo(Instant.EPOCH);
        assertThat(view.spec().filename()).isEqualTo("users-api.yaml"); // M6/filename
        assertThat(view.spec().endpointCount()).isEqualTo(18);
        assertThat(view.effectiveClassification().profile()).isEqualTo(ClassificationProfile.MIDDLE);
        assertThat(view.effectiveClassification().weightsSource()).isEqualTo("preset");
        assertThat(view.effectiveClassification().weights()).hasSize(18);
    }

    @Test
    void getWithoutScanOrSpecYieldsNullsButAlwaysEffectiveClassification() {
        DomainConfig d = new DomainConfig();
        d.setHost("example.com");
        when(repo.findById("example.com")).thenReturn(Optional.of(d));
        when(specStore.latestSpecMeta("example.com")).thenReturn(Optional.empty());
        when(scanRepo.findById("example.com")).thenReturn(Optional.empty());

        var view = controller.get("example.com");

        assertThat(view.spec()).isNull();
        assertThat(view.lastScanAt()).isNull();
        assertThat(view.effectiveClassification()).isNotNull(); // resolver 는 항상 effective 반환(MIDDLE 기본)
    }

    private static SpecMetaProjection spec(String filename, int endpointCount) {
        // ★REST 메타는 projection(rawDoc oid 미접근, doc/28) — latestSpecMeta 가 SpecMetaProjection 반환
        return new SpecMetaProjection(SpecFormat.OPENAPI, 3L, endpointCount, Instant.EPOCH, "default", filename, true);
    }

    private static DomainConfig existing() {
        DomainConfig d = new DomainConfig();
        d.setHost("example.com");
        d.setEnabled(true);
        d.setHostnames(new ArrayList<>(List.of("edge1")));
        d.setIntervalOverride("PT1H");
        d.setBasePathStrip("/v2");
        return d;
    }

    private static DomainConfig dc(String host) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(true);
        return d;
    }

    private static DomainUpsert upsert(String host) {
        return new DomainUpsert(host, true, null, null, null, null);
    }
}
