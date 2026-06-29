// SpecStore 업로드/버전관리/로드 단위 테스트 (doc/03 §7). repo 는 Mockito mock
package com.pentasecurity.apidiscover.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.domain.SpecRecordRepository;
import com.pentasecurity.apidiscover.match.EndpointMatcherCache;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final EndpointMatcherCache matcherCache = mock(EndpointMatcherCache.class);
    private final DomainConfigRepository domainRepo = mock(DomainConfigRepository.class); // findById empty → MERGE
    private final SpecStore store = new SpecStore(
            repo,
            new SpecFormatDetector(),
            new ObjectMapper(),
            matcherCache,
            domainRepo,
            List.of(new OpenApiSpecParser(), new PostmanSpecParser(new ObjectMapper()), new CsvSpecParser()));

    @Test
    void firstUploadCreatesActiveVersionOne() {
        when(repo.findFirstByHostOrderBySpecVersionDesc(HOST)).thenReturn(Optional.empty());
        when(repo.findByHostAndActiveIsTrue(HOST)).thenReturn(List.of());
        when(repo.save(any(SpecRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        SpecRecord saved = store.upload(HOST, OPENAPI);

        assertThat(saved.getSpecVersion()).isEqualTo(1L);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getFormat()).isEqualTo(SpecFormat.OPENAPI);
        assertThat(saved.getEndpointCount()).isEqualTo(2);
        assertThat(saved.getCanonicalJson()).contains("/v2/users/{id}");
    }

    @Test
    void uploadInvalidatesMatcherCacheForHost() {
        when(repo.findFirstByHostOrderBySpecVersionDesc(HOST)).thenReturn(Optional.empty());
        when(repo.findByHostAndActiveIsTrue(HOST)).thenReturn(List.of());
        when(repo.save(any(SpecRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        store.upload(HOST, OPENAPI);

        verify(matcherCache).invalidate(HOST); // save 후 구버전 슬롯 무효화 (doc/15 §2)
    }

    @Test
    void uploadStoresFilenameWhenProvidedElseNull() {
        when(repo.findFirstByHostOrderBySpecVersionDesc(HOST)).thenReturn(Optional.empty());
        when(repo.findByHostAndActiveIsTrue(HOST)).thenReturn(List.of());
        when(repo.save(any(SpecRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(store.upload(HOST, OPENAPI, "users-api.yaml").getFilename()).isEqualTo("users-api.yaml");
        assertThat(store.upload(HOST, OPENAPI).getFilename()).isNull(); // 미전달=null(doc/35 M2)
    }

    @Test
    void uploadDerivesSpecNameFromFilenameElseDefault() {
        when(repo.findFirstByHostOrderBySpecVersionDesc(HOST)).thenReturn(Optional.empty());
        when(repo.findByHostAndActiveIsTrue(HOST)).thenReturn(List.of());
        when(repo.save(any(SpecRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        // doc/36 M7.1: filename → specName(trim·소문자), 미전달/빈 = "default"(하위호환)
        assertThat(store.upload(HOST, OPENAPI, "Users-API.yaml").getSpecName()).isEqualTo("users-api.yaml");
        assertThat(store.upload(HOST, OPENAPI).getSpecName()).isEqualTo("default");        // 파일명 미전달
        assertThat(store.upload(HOST, OPENAPI, "   ").getSpecName()).isEqualTo("default");   // 빈 파일명
    }

    @Test
    void secondUploadIncrementsVersionAndDeactivatesPrevious() {
        SpecRecord prev = new SpecRecord();
        prev.setHost(HOST);
        prev.setSpecVersion(3L);
        prev.setActive(true);
        when(repo.findFirstByHostOrderBySpecVersionDesc(HOST)).thenReturn(Optional.of(prev));
        when(repo.findByHostAndActiveIsTrue(HOST)).thenReturn(List.of(prev));
        when(repo.save(any(SpecRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        SpecRecord saved = store.upload(HOST, OPENAPI);

        assertThat(saved.getSpecVersion()).isEqualTo(4L);
        assertThat(saved.isActive()).isTrue();
        assertThat(prev.isActive()).isFalse(); // 이전 활성 버전 비활성화
    }

    @Test
    void loadActiveCanonicalRoundTrips() {
        when(repo.findFirstByHostOrderBySpecVersionDesc(HOST)).thenReturn(Optional.empty());
        when(repo.findByHostAndActiveIsTrue(HOST)).thenReturn(List.of());
        when(repo.save(any(SpecRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        SpecRecord saved = store.upload(HOST, OPENAPI);

        // 활성 문서 집합 = [saved] (단일) → merge=canonicalize 동치 (doc/26 §5)
        when(repo.findByHostAndActiveIsTrue(HOST)).thenReturn(List.of(saved));

        List<CanonicalEndpoint> loaded = store.loadActiveCanonical(HOST);

        assertThat(loaded).hasSize(2);
        assertThat(loaded).extracting(CanonicalEndpoint::pathTemplate)
                .containsExactlyInAnyOrder("/v2/users/{id}", "/v2/v1/orders/{orderId}");
        assertThat(loaded).anyMatch(CanonicalEndpoint::deprecated);
    }

    // --- 멀티 스펙 + 병합 모드 (doc/26 §3/§5, 2단계) ---

    @Test
    void mergeModeKeepsSiblingDocsActiveAndUnionsCanonical() {
        List<SpecRecord> db = new ArrayList<>();
        SpecStore s = storeWith(db, null); // null → MERGE(현행)

        s.upload(HOST, "users", openapi("/users/{id}"));
        s.upload(HOST, "orders", openapi("/orders/{id}")); // 다른 name → 형제 유지

        assertThat(db).filteredOn(r -> r.isActive()).extracting(r -> r.getSpecName())
                .containsExactlyInAnyOrder("users", "orders");
        assertThat(s.loadActiveCanonical(HOST)).extracting(CanonicalEndpoint::pathTemplate)
                .containsExactlyInAnyOrder("/users/{id}", "/orders/{id}"); // union (stateful repo)
    }

    @Test
    void separateModeDeactivatesAllOtherDocsOnUpload() {
        List<SpecRecord> db = new ArrayList<>();
        SpecStore s = storeWith(db, SpecMergeStrategy.SEPARATE);

        s.upload(HOST, "users", openapi("/users/{id}"));
        s.upload(HOST, "orders", openapi("/orders/{id}")); // SEPARATE → users 도 비활성(전체 교체)

        assertThat(db).filteredOn(r -> r.isActive()).extracting(r -> r.getSpecName())
                .containsExactly("orders"); // 최신 1개만 active
    }

    @Test
    void versionGroupedKeepsSiblingsActiveLikeMerge() {
        List<SpecRecord> db = new ArrayList<>();
        SpecStore s = storeWith(db, SpecMergeStrategy.VERSION_GROUPED);

        s.upload(HOST, "v1", openapi("/v1/users/{id}"));
        s.upload(HOST, "v2", openapi("/v2/users/{id}")); // 공존(그룹 뷰는 3단계)

        assertThat(db).filteredOn(r -> r.isActive()).hasSize(2);
    }

    @Test
    void sameSpecNameReuploadReplacesSiblingInMergeMode() {
        List<SpecRecord> db = new ArrayList<>();
        SpecStore s = storeWith(db, null); // MERGE

        s.upload(HOST, "users", openapi("/users/{id}"));
        s.upload(HOST, "users", openapi("/users/{userId}")); // 같은 name 재업로드 → 교체

        assertThat(db).filteredOn(r -> r.isActive()).hasSize(1); // 같은 name 이전본 비활성
    }

    // --- 결정적 merge (doc/26 §5: dedupe + deprecated OR + latest-wins, 순서 무관) ---

    @Test
    void mergeIsOrderIndependentWithDeprecatedOrAndLatestUploadWins() {
        var older = new CanonicalEndpoint("GET", "/p", null, false, "1", "refA");
        var newerDep = new CanonicalEndpoint("GET", "/p", null, true, "2", "refB"); // 신버전·deprecated
        var docA = new SpecCanonicalizer.VersionedCanonical(1L, List.of(older));
        var docB = new SpecCanonicalizer.VersionedCanonical(2L, List.of(newerDep));

        List<CanonicalEndpoint> ab = SpecCanonicalizer.merge(List.of(docA, docB));
        List<CanonicalEndpoint> ba = SpecCanonicalizer.merge(List.of(docB, docA));

        assertThat(ab).isEqualTo(ba); // 업로드/문서 순서 무관 동일 SET
        assertThat(ab).singleElement().satisfies(e -> {
            assertThat(e.deprecated()).isTrue();        // deprecated OR
            assertThat(e.version()).isEqualTo("2");     // latest(specVersion 2) wins
            assertThat(e.sourceRef()).isEqualTo("refB");
        });
    }

    @Test
    void mergeKeepsDeprecatedOrEvenWhenLatestIsNotDeprecated() {
        var oldDep = new CanonicalEndpoint("GET", "/q", null, true, "1", "refA");
        var newActive = new CanonicalEndpoint("GET", "/q", null, false, "2", "refB");

        List<CanonicalEndpoint> m = SpecCanonicalizer.merge(List.of(
                new SpecCanonicalizer.VersionedCanonical(1L, List.of(oldDep)),
                new SpecCanonicalizer.VersionedCanonical(2L, List.of(newActive))));

        assertThat(m).singleElement().satisfies(e -> {
            assertThat(e.deprecated()).isTrue();      // OR — 구버전 deprecated 잔존(안전)
            assertThat(e.version()).isEqualTo("2");   // 비-deprecated 필드는 latest-wins
        });
    }

    // --- 합성 spec 버전 (doc/26 §8: 콘텐츠 해시·순서 무관·콘텐츠 변화 시만 변동) ---

    @Test
    void syntheticVersionStableForSameContentRegardlessOfDocOrder() {
        ObjectMapper om = new ObjectMapper();
        var a = new CanonicalEndpoint("GET", "/a", null, false, null, "r1");
        var b = new CanonicalEndpoint("GET", "/b", null, false, null, "r2");
        long v1 = SpecStore.syntheticVersion(SpecCanonicalizer.merge(List.of(
                new SpecCanonicalizer.VersionedCanonical(1L, List.of(a)),
                new SpecCanonicalizer.VersionedCanonical(2L, List.of(b)))), om);
        long v2 = SpecStore.syntheticVersion(SpecCanonicalizer.merge(List.of(
                new SpecCanonicalizer.VersionedCanonical(2L, List.of(b)),
                new SpecCanonicalizer.VersionedCanonical(1L, List.of(a)))), om);

        assertThat(v1).isEqualTo(v2).isNotZero();                          // 동일 콘텐츠=동일 버전
        assertThat(SpecStore.syntheticVersion(List.of(a), om)).isNotEqualTo(v1); // 콘텐츠 다르면 다른 버전
    }

    // --- helpers ---

    /** 멀티 업로드용 stateful SpecRecordRepository(in-memory) + 지정 모드 DomainConfig. */
    private static SpecStore storeWith(List<SpecRecord> db, SpecMergeStrategy mode) {
        SpecRecordRepository r = mock(SpecRecordRepository.class);
        when(r.findFirstByHostOrderBySpecVersionDesc(any())).thenAnswer(inv -> db.stream()
                .filter(x -> x.getHost().equals(inv.getArgument(0)))
                .max(Comparator.comparingLong(x -> x.getSpecVersion())));
        when(r.findByHostAndActiveIsTrue(any())).thenAnswer(inv -> db.stream()
                .filter(x -> x.getHost().equals(inv.getArgument(0)) && x.isActive()).toList());
        when(r.save(any(SpecRecord.class))).thenAnswer(inv -> {
            SpecRecord rec = inv.getArgument(0);
            // 생성 id 는 setter 미노출(D41) → 흉내 안 냄. identity 기반 add-once(id 값은 미사용·미단언).
            if (rec.getId() == null && !db.contains(rec)) {
                db.add(rec);
            }
            return rec;
        });
        DomainConfigRepository dRepo = mock(DomainConfigRepository.class);
        if (mode != null) {
            DomainConfig cfg = new DomainConfig();
            cfg.setHost(HOST);
            cfg.setSpecMergeStrategy(mode);
            when(dRepo.findById(HOST)).thenReturn(Optional.of(cfg));
        }
        return new SpecStore(r, new SpecFormatDetector(), new ObjectMapper(), mock(EndpointMatcherCache.class),
                dRepo, List.of(new OpenApiSpecParser(), new PostmanSpecParser(new ObjectMapper()), new CsvSpecParser()));
    }

    /** 단일 path OpenAPI(servers 없음 → host-agnostic). */
    private static byte[] openapi(String path) {
        return ("""
                openapi: 3.0.1
                info:
                  title: T
                  version: 1.0.0
                paths:
                  PATH:
                    get:
                      responses:
                        '200':
                          description: ok
                """.replace("PATH", path)).getBytes(StandardCharsets.UTF_8);
    }
}
