// CombinedDiscoveryService.forHost() 결합 뷰 테스트 (doc/26 §6/§7) — repo/SpecStore mock
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pentasecurity.apidiscover.classify.ApiScorer;
import com.pentasecurity.apidiscover.classify.Classifier;
import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
import com.pentasecurity.apidiscover.domain.ClassificationConfig;
import com.pentasecurity.apidiscover.domain.ClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRecord;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRepository;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.match.EndpointMatcherCache;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.Classification;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import com.pentasecurity.apidiscover.model.EndpointRationale;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.spec.ApiInventoryService;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CombinedDiscoveryServiceTest {

    private static final String HOST = "api.example.com";

    private final DiscoveredEndpointRepository discoveredRepo = mock(DiscoveredEndpointRepository.class);
    private final SpecStore specStore = mock(SpecStore.class);
    // DELETED→Zombie 결합 입력(doc/37 §6). 기본 빈 키집합 → 분류 무영향(무회귀). DELETED Zombie 테스트만 stub.
    private final ApiInventoryService apiInventoryService = stubInventory();

    private static ApiInventoryService stubInventory() {
        ApiInventoryService m = mock(ApiInventoryService.class);
        when(m.deletedKeys(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Set.of());
        return m;
    }
    private final DomainConfigRepository domainRepo = mock(DomainConfigRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ClassificationConfigRepository globalClassRepo = mock(ClassificationConfigRepository.class);
    private final EffectiveClassificationResolver resolver = new EffectiveClassificationResolver(
            globalClassRepo, mock(DomainClassificationConfigRepository.class), objectMapper);

    private final CombinedDiscoveryService service = new CombinedDiscoveryService(
            discoveredRepo, specStore, apiInventoryService, new EndpointMatcherCache(), new Classifier(new ApiScorer()),
            resolver, domainRepo, objectMapper);

    @Test
    void classifiesAccumulatedCatalogAgainstActiveSpec() {
        // 카탈로그: spec 매칭(Active) + 미매칭 API(Shadow). spec=host-agnostic /users/{id}.
        when(discoveredRepo.findByHost(HOST)).thenReturn(List.of(
                rec("GET", "/users/{id}"),       // spec 매칭 → Active
                rec("GET", "/v2/items/{id}")));  // 미문서·API 신호 → Shadow
        when(specStore.activeRecords(HOST)).thenReturn(List.of(specRec("users", 1L)));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/users/{id}", null, false, null, "ref")));

        CombinedDiscovery v = service.forHost(HOST);

        assertThat(byClass(v.findings(), Classification.ACTIVE)).extracting(Finding::pathTemplate)
                .containsExactly("/users/{id}");
        assertThat(byClass(v.findings(), Classification.SHADOW)).extracting(Finding::pathTemplate)
                .containsExactly("/v2/items/{id}");
        assertThat(v.versionGroups()).isEmpty(); // 기본 MERGE → flat
        assertThat(v.specVersion()).isNotZero();  // 합성 버전(스펙 존재)
    }

    @Test
    void noSpecMakesCatalogShadow() {
        when(discoveredRepo.findByHost(HOST)).thenReturn(List.of(rec("GET", "/v2/items/{id}")));
        when(specStore.activeRecords(HOST)).thenReturn(List.of()); // 무스펙

        CombinedDiscovery v = service.forHost(HOST);

        assertThat(byClass(v.findings(), Classification.SHADOW)).hasSize(1);
        assertThat(v.specVersion()).isZero();
        assertThat(v.specSource()).isEqualTo(com.pentasecurity.apidiscover.model.SpecSource.EMPTY);
    }

    @Test
    void versionGroupedSplitsFindingsByVersionLabel() {
        when(domainRepo.findById(HOST)).thenReturn(Optional.of(domain(SpecMergeStrategy.VERSION_GROUPED)));
        when(discoveredRepo.findByHost(HOST)).thenReturn(List.of(
                rec("GET", "/v1/items/{id}"), rec("GET", "/v2/items/{id}")));
        when(specStore.activeRecords(HOST)).thenReturn(List.of()); // 무스펙 → 둘 다 Shadow

        CombinedDiscovery v = service.forHost(HOST);

        assertThat(v.versionGroups()).extracting(CombinedDiscovery.VersionGroup::version)
                .containsExactly("v1", "v2"); // 라벨 정렬·path-version 그룹
        assertThat(v.versionGroups()).allSatisfy(g -> assertThat(g.findings()).hasSize(1));
    }

    @Test
    void flatModeHasNoVersionGroupsEvenForVersionedPaths() {
        when(domainRepo.findById(HOST)).thenReturn(Optional.of(domain(SpecMergeStrategy.MERGE)));
        when(discoveredRepo.findByHost(HOST)).thenReturn(List.of(
                rec("GET", "/v1/items/{id}"), rec("GET", "/v2/items/{id}")));
        when(specStore.activeRecords(HOST)).thenReturn(List.of());

        assertThat(service.forHost(HOST).versionGroups()).isEmpty(); // MERGE=flat
    }

    @Test
    void specSourceExposesActiveDocuments() {
        when(discoveredRepo.findByHost(HOST)).thenReturn(List.of());
        when(specStore.activeRecords(HOST)).thenReturn(List.of(specRec("users", 1L), specRec("orders", 2L)));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/users/{id}", null, false, null, "ref")));

        CombinedDiscovery v = service.forHost(HOST);

        assertThat(v.specSource().documents())
                .extracting(com.pentasecurity.apidiscover.model.SpecSource.SpecDocument::specName)
                .containsExactly("orders", "users"); // specName 정렬(결정적)
    }

    @Test
    void exposesEffectiveClassificationMiddleAndRationaleParallelToFindings() {
        // 기본(설정 부재) → MIDDLE preset. rationale 는 findings 와 1:1 병렬(doc/34 §2).
        when(discoveredRepo.findByHost(HOST)).thenReturn(List.of(
                rec("GET", "/users/{id}"), rec("GET", "/v2/items/{id}")));
        when(specStore.activeRecords(HOST)).thenReturn(List.of(specRec("users", 1L)));
        when(specStore.loadActiveCanonical(HOST)).thenReturn(List.of(
                new CanonicalEndpoint("GET", "/users/{id}", null, false, null, "ref")));

        CombinedDiscovery v = service.forHost(HOST);

        var ec = v.effectiveClassification();
        assertThat(ec.profile()).isEqualTo(ClassificationProfile.MIDDLE);
        assertThat(ec.threshold()).isEqualTo(0.70);
        assertThat(ec.weightsSource()).isEqualTo("preset");
        assertThat(ec.weights()).hasSize(18).containsKeys("hostApiSubdomain", "corsPreflight", "staticAssetPenalty");
        // rationale 병렬: 동일 크기·동일 분류 순서
        assertThat(v.rationale()).hasSameSizeAs(v.findings());
        assertThat(v.rationale()).extracting(EndpointRationale::classification)
                .isEqualTo(v.findings().stream().map(Finding::classification).toList());
    }

    @Test
    void customProfileExposesCustomWeightsSourceAndOverriddenWeight() {
        var cfg = new ClassificationConfig();
        cfg.setId(1L);
        cfg.setProfile(ClassificationProfile.CUSTOM);
        cfg.setCustomWeightsJson("{\"query\":0.99}");
        when(globalClassRepo.findById(1L)).thenReturn(Optional.of(cfg));
        when(discoveredRepo.findByHost(HOST)).thenReturn(List.of(rec("GET", "/v2/items/{id}")));
        when(specStore.activeRecords(HOST)).thenReturn(List.of());

        var ec = service.forHost(HOST).effectiveClassification();

        assertThat(ec.profile()).isEqualTo(ClassificationProfile.CUSTOM);
        assertThat(ec.weightsSource()).isEqualTo("custom");
        assertThat(ec.weights()).containsEntry("query", 0.99); // override 반영
    }

    // --- helpers ---

    private static List<Finding> byClass(List<Finding> findings, Classification c) {
        return findings.stream().filter(f -> f.classification() == c).toList();
    }

    private static DiscoveredEndpointRecord rec(String method, String template) {
        var r = new DiscoveredEndpointRecord();
        r.setHost(HOST);
        r.setMethod(method);
        r.setPathTemplate(template);
        r.setFirstSeen(Instant.EPOCH);
        r.setLastSeen(Instant.EPOCH);
        r.setHits(10);
        r.setStatusDistJson("{\"2xx\":10}");
        r.setHadQuery(true);
        r.setNonBrowserUa(true);
        r.setTemplateSource("INFERRED");
        r.setEndpointKind("API_CANDIDATE");
        r.setKindConfidence(0.9);
        return r;
    }

    private static SpecRecord specRec(String specName, long version) {
        var r = new SpecRecord();
        r.setHost(HOST);
        r.setSpecName(specName);
        r.setFormat(SpecFormat.OPENAPI);
        r.setSpecVersion(version);
        r.setActive(true);
        return r;
    }

    private static DomainConfig domain(SpecMergeStrategy mode) {
        var d = new DomainConfig();
        d.setHost(HOST);
        d.setSpecMergeStrategy(mode);
        return d;
    }
}
