// 실 PostgreSQL(Testcontainers) 통합 테스트 — @Lob→TEXT 실검증 + JPA/REST/304 e2e (doc/28, D40)
package com.pentasecurity.apidiscover.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pentasecurity.apidiscover.batch.DiscoveryJobService;
import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
import com.pentasecurity.apidiscover.domain.ActivityStatus;
import com.pentasecurity.apidiscover.domain.ClassificationConfig;
import com.pentasecurity.apidiscover.domain.ClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRecord;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRepository;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfig;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.domain.SpecRecordRepository;
import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.ingest.LokiClient;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.TemplateSource;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * H2 단위테스트가 못 잡는 실 PG 매핑/동작 검증(doc/28). docker/podman 부재 시 클래스 전체 auto-skip.
 * 싱글톤 컨테이너 + create-drop(Hibernate 가 엔티티→PG DDL 직접 생성 = @Lob 매핑 검증 대상).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PostgresIntegrationTest.class);

    @Container
    @ServiceConnection // 컨테이너 jdbc url/user/pw 를 datasource 로 자동 배선(Spring Boot 3.1+)
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop"); // ephemeral 에 엔티티→PG DDL 직접 생성
    }

    // 운영 Loki(192.168.8.100:3200) 실호출 차단 — 부팅 시 스케줄러 1회 실행 대비(doc/11 선례).
    @MockBean
    LokiClient lokiClient;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClassificationConfigRepository globalRepo;
    @Autowired DomainClassificationConfigRepository overrideRepo;
    @Autowired ScanResultRepository scanRepo;
    @Autowired SpecRecordRepository specRepo;
    @Autowired DiscoveredEndpointRepository discoveredRepo;
    @Autowired DomainConfigRepository domainRepo;
    @Autowired com.pentasecurity.apidiscover.domain.WatermarkRepository watermarkRepo;
    @Autowired EffectiveClassificationResolver resolver;
    @Autowired com.pentasecurity.apidiscover.batch.DomainUpserter domainUpserter;
    @Autowired DiscoveryJobService jobService;

    // 공유 컨텍스트·컨테이너(rollback 없음) → 메서드 간 상태 격리. round-trip 이 글로벌 설정(id=1)에
    // 의사 JSON 을 써넣어도 다음 테스트 오염 방지(globalRepo 비움 → resolver default). ClassificationControllerTest 선례.
    @BeforeEach
    void reset() {
        discoveredRepo.deleteAll();
        scanRepo.deleteAll();
        specRepo.deleteAll();
        overrideRepo.deleteAll();
        globalRepo.deleteAll();
        domainRepo.deleteAll();
        resolver.invalidateAll();
    }

    // --- L52: text 매핑 실검증 — @Lob String 9컬럼 + path_template(varchar→text, 실배포 오버플로 수정) ---

    @ParameterizedTest(name = "{0}.{1} → text")
    @CsvSource({
            "classification_config, custom_weights_json",
            "classification_config, matcher_json",
            "domain_classification_config, custom_weights_json",
            "domain_classification_config, matcher_json",
            "scan_result, report_json",
            "spec_record, canonical_json",
            "spec_record, warnings_json",
            "discovered_endpoint, status_dist_json",
            "discovered_endpoint, params_json",
            "discovered_endpoint, path_template",  // varchar(255) 오버플로 → text(임의 길이 경로, 실배포 발견)
    })
    void lobStringColumnsMapToText(String table, String column) {
        String dataType = jdbc.queryForObject(
                "select data_type from information_schema.columns where table_name = ? and column_name = ?",
                String.class, table, column);
        // 기대 = text. oid/bytea/varchar 면 실결함(테스트 느슨화 금지, D37) → 엔티티 수정으로 해소.
        assertThat(dataType).as("%s.%s PG 타입(text 기대)", table, column).isEqualTo("text");
    }

    @Test
    void lobStringColumnsRoundTripLargePayload() {
        String big = bigJson(); // 수십 KB — LOB 스트리밍/auto-commit 이슈 노출

        ClassificationConfig g = new ClassificationConfig();
        g.setId(1L);
        g.setCustomWeightsJson(big);
        g.setMatcherJson(big);
        globalRepo.save(g);
        ClassificationConfig gl = globalRepo.findById(1L).orElseThrow();
        assertThat(gl.getCustomWeightsJson()).isEqualTo(big);
        assertThat(gl.getMatcherJson()).isEqualTo(big);

        DomainClassificationConfig o = new DomainClassificationConfig();
        o.setHost("rt.example.com");
        o.setCustomWeightsJson(big);
        o.setMatcherJson(big);
        overrideRepo.save(o);
        DomainClassificationConfig ol = overrideRepo.findById("rt.example.com").orElseThrow();
        assertThat(ol.getCustomWeightsJson()).isEqualTo(big);
        assertThat(ol.getMatcherJson()).isEqualTo(big);

        ScanResult s = new ScanResult();
        s.setHost("rt.example.com");
        s.setReportJson(big);
        scanRepo.save(s);
        assertThat(scanRepo.findById("rt.example.com").orElseThrow().getReportJson()).isEqualTo(big);

        SpecRecord spec = new SpecRecord();
        spec.setHost("rt.example.com");
        spec.setSpecName("default");
        spec.setFormat(SpecFormat.OPENAPI);
        spec.setCanonicalJson(big);
        spec.setWarningsJson(big);
        SpecRecord sps = specRepo.save(spec);
        SpecRecord spl = specRepo.findById(sps.getId()).orElseThrow();
        assertThat(spl.getCanonicalJson()).isEqualTo(big);
        assertThat(spl.getWarningsJson()).isEqualTo(big);

        DiscoveredEndpointRecord d = new DiscoveredEndpointRecord();
        d.setHost("rt.example.com");
        d.setMethod("GET");
        d.setPathTemplate("/rt/{id}");
        d.setStatusDistJson(big);
        d.setParamsJson(big);
        DiscoveredEndpointRecord ds = discoveredRepo.save(d);
        DiscoveredEndpointRecord dl = discoveredRepo.findById(ds.getId()).orElseThrow();
        assertThat(dl.getStatusDistJson()).isEqualTo(big);
        assertThat(dl.getParamsJson()).isEqualTo(big);
    }

    @Test
    void longPathTemplateRoundTripsBeyondVarchar255() {
        // varchar(255) 오버플로(실배포 'value too long') → text 로 임의 길이 경로 저장/조회
        String longPath = "/api/v1/" + "segment/".repeat(50) + "{id}"; // >255자
        assertThat(longPath.length()).isGreaterThan(255);

        DiscoveredEndpointRecord d = new DiscoveredEndpointRecord();
        d.setHost("longpath.example.com");
        d.setMethod("GET");
        d.setPathTemplate(longPath);
        DiscoveredEndpointRecord saved = discoveredRepo.save(d); // varchar(255)면 여기서 실패했음
        assertThat(discoveredRepo.findById(saved.getId()).orElseThrow().getPathTemplate()).isEqualTo(longPath);
    }

    @Test
    void oversizePathTemplateInsertFailsOnRealPgIndexLimit() {
        // D68 근거 고정(영구 RED 증거): 컬럼(text)은 TOAST 로 임의 길이 저장 가능하지만
        // unique(host,method,path_template) btree 인덱스 행은 한계(압축 후 2,704B / 최대 8,191B)가 있어
        // 고엔트로피 초장문 경로는 INSERT 자체가 불가(SQLSTATE 54000, 실배포 keeperlabo.jp SQLi 43KB 재현).
        DiscoveredEndpointRecord d = new DiscoveredEndpointRecord();
        d.setHost("oversize.example.com");
        d.setMethod("GET");
        d.setPathTemplate("/a/" + randomAlnum(10_000)); // 고엔트로피 → pglz 압축으로도 한계 초과
        assertThatThrownBy(() -> discoveredRepo.saveAndFlush(d)).hasMessageContaining("index row");
    }

    @Test
    void upsertDiscoveredSkipsOversizePathBeforeInsertOnRealPg() {
        // D68 가드: 임계(2,048자) 초과 경로는 persist 전 차단 — 예외 없이 스킵되고 같은 배치의 정상 경로는 저장.
        // RED-확인: upsertDiscovered 의 길이 가드를 제거하면 위 인덱스 한계 예외로 red.
        String host = "oversize-guard.example.com";
        DiscoveredEndpoint giant = endpoint(host, "GET", "/a/" + randomAlnum(10_000), 1L);
        DiscoveredEndpoint normal = endpoint(host, "GET", "/api/items/{id}", 1L);

        jobService.upsertDiscovered(host, new HashMap<>(), List.of(giant, normal),
                new EndpointMatcher(List.of()),
                new LogWindow(Instant.EPOCH, Instant.parse("2026-07-04T00:00:00Z")));

        assertThat(discoveredRepo.findByHost(host))
                .extracting(DiscoveredEndpointRecord::getPathTemplate)
                .containsExactly("/api/items/{id}");
    }

    /** 시드 고정 의사난수 영숫자(재현 가능·비압축성) — Date/Random 시드 없는 비결정성 회피. */
    private static String randomAlnum(int n) {
        java.util.Random r = new java.util.Random(42);
        String alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(alpha.charAt(r.nextInt(alpha.length())));
        }
        return sb.toString();
    }

    @Test
    void upsertDiscoveredMergesIntraBatchDuplicateSignatureOnRealPg() {
        // 한 스캔의 discovered 리스트에 동일 signature 2개(T1 {var} 승격으로 두 경로가 같은 template 로 수렴 등, doc/13).
        // 수정 전: 2번째가 새 INSERT → unique(host,method,path_template) 위반 → 스캔 실패(결과 미저장).
        // 수정 후: prior 즉시 등록 → 같은 rec UPDATE 병합(1건, last-writer-wins).
        String host = "dup.example.com";
        String method = "GET";
        String template = "/api/items/{id}";
        DiscoveredEndpoint first = endpoint(host, method, template, 10L);
        DiscoveredEndpoint second = endpoint(host, method, template, 20L); // 동일 signature, hits 만 상이

        jobService.upsertDiscovered(host, new HashMap<>(), List.of(first, second),
                new EndpointMatcher(List.of()),
                new LogWindow(Instant.EPOCH, Instant.parse("2026-06-26T00:00:00Z")));

        List<DiscoveredEndpointRecord> recs = discoveredRepo.findByHost(host);
        assertThat(recs).hasSize(1);                       // 중복 병합(2 INSERT 아님 = unique 위반 없음)
        assertThat(recs.get(0).getHits()).isEqualTo(20L);  // last-writer-wins(마지막 d 반영)
    }

    @Test
    void upsertMatchesExistingRowByConstraintTupleNotSignatureOnRealPg() {
        // ★PR #31 이 놓친 케이스: signature 가 pathTemplate 과 발산("/" 정규화/방출 불일치, 실배포 발견).
        // prior 는 제약 튜플(method,host,path_template)로 키잉되는데 upsert 가 d.signature() 로 lookup 하면 기존 행 miss
        // → 신규 INSERT → unique 위반. 수정: lookup/put 도 제약 튜플(identityKey)로 → 기존 행 매칭(UPDATE).
        String host = "divergent.example.com";
        DiscoveredEndpointRecord existing = new DiscoveredEndpointRecord();
        existing.setHost(host);
        existing.setMethod("GET");
        existing.setPathTemplate("/"); // 기존 DB 행 (host, GET, "/")
        existing.setHits(5L);
        discoveredRepo.save(existing);

        // 이번 스캔: 제약 튜플=(GET,host,"/")이나 signature 는 발산(GET host /v1/legacy)
        DiscoveredEndpoint.Metrics m = new DiscoveredEndpoint.Metrics(
                99L, Instant.EPOCH, Instant.EPOCH, Map.of("2xx", 99L), 1, 5, 10);
        DiscoveredEndpoint divergent = new DiscoveredEndpoint(
                "GET " + host + " /v1/legacy", "GET", host, "/",
                TemplateSource.INFERRED, EndpointKind.UNKNOWN, 0.0, false, false, m, ParamCandidates.EMPTY);

        jobService.upsertDiscovered(host, priorFor(host), List.of(divergent),
                new EndpointMatcher(List.of()),
                new LogWindow(Instant.EPOCH, Instant.parse("2026-06-26T00:00:00Z")));

        List<DiscoveredEndpointRecord> recs = discoveredRepo.findByHost(host);
        assertThat(recs).hasSize(1);                      // 기존 (GET,host,"/") 행 UPDATE — 신규 INSERT 충돌 없음
        assertThat(recs.get(0).getHits()).isEqualTo(99L); // UPDATE 반영(기존 5 → 99)
    }

    @Test
    void upsertMergesDivergentParsedHostsUnderScanHostOnRealPg() {
        // ★host 축 발산(reviewer): LokiQueryBuilder 의 |= domain substring 라인필터로 referer/URL/UA 에 도메인 든
        // 다른 Host 라인도 매칭 → d.host() 가 스캔 도메인과 다를 수 있음. 둘 다 GET "/" 이되 파싱 host 가 D/E 로 갈리면
        // 키가 d.host() 면 두 신규 rec 둘 다 setHost(scanHost) INSERT → 충돌. 키를 host 파라미터로 통일 → 한 행 병합.
        String scanHost = "d.example.com";
        DiscoveredEndpoint fromD = endpoint("d.example.com", "GET", "/", 10L);
        DiscoveredEndpoint fromE = endpoint("e.example.com", "GET", "/", 20L); // d.host() 발산

        jobService.upsertDiscovered(scanHost, new HashMap<>(), List.of(fromD, fromE),
                new EndpointMatcher(List.of()),
                new LogWindow(Instant.EPOCH, Instant.parse("2026-06-26T00:00:00Z")));

        assertThat(discoveredRepo.findByHost(scanHost)).hasSize(1);             // (scanHost,GET,"/") 1행 병합
        assertThat(discoveredRepo.findByHost("e.example.com")).isEmpty();       // E 행 미생성(setHost=파라미터)
        assertThat(discoveredRepo.findByHost(scanHost).get(0).getHits()).isEqualTo(20L); // last-writer
    }

    @Test
    void upsertMatchesExistingRowWhenParsedHostDivergesOnRealPg() {
        // cross-batch host 축: 기존 (scanHost,GET,"/") 행 + 이번 스캔 d.host()=E 발산·튜플 GET/"/" → 기존 행 UPDATE(무위반).
        String scanHost = "d.example.com";
        DiscoveredEndpointRecord existing = new DiscoveredEndpointRecord();
        existing.setHost(scanHost);
        existing.setMethod("GET");
        existing.setPathTemplate("/");
        existing.setHits(5L);
        discoveredRepo.save(existing);

        DiscoveredEndpoint fromE = endpoint("e.example.com", "GET", "/", 77L); // d.host()=E 발산
        jobService.upsertDiscovered(scanHost, priorFor(scanHost), List.of(fromE),
                new EndpointMatcher(List.of()),
                new LogWindow(Instant.EPOCH, Instant.parse("2026-06-26T00:00:00Z")));

        assertThat(discoveredRepo.findByHost(scanHost)).hasSize(1);                  // 기존 행 UPDATE
        assertThat(discoveredRepo.findByHost(scanHost).get(0).getHits()).isEqualTo(77L);
    }

    // --- L53: REST e2e (실 PG 백엔드) ---

    @Test
    void discoveryReturnsSeededFindingFromRealPg() throws Exception {
        String host = "disc.example.com";
        registerDomain(host);
        DiscoveredEndpointRecord d = new DiscoveredEndpointRecord();
        d.setHost(host);
        d.setMethod("POST");
        d.setPathTemplate("/api/orders/{id}"); // api_seg + write → ApiScorer ADMIT → Shadow
        d.setTemplateSource("INFERRED");
        d.setEndpointKind("API_CANDIDATE");
        d.setKindConfidence(0.95);
        d.setFirstSeen(Instant.EPOCH);
        d.setLastSeen(Instant.EPOCH);
        d.setLastScanAt(Instant.EPOCH);
        d.setHits(100);
        d.setStatusDistJson("{\"2xx\":100}");
        d.setHadQuery(true);
        d.setNonBrowserUa(true);
        discoveredRepo.save(d);

        mvc.perform(get("/api/v1/domains/{host}/discovery", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value(host))
                .andExpect(jsonPath("$.findings").isArray())
                .andExpect(jsonPath("$.findings[*].pathTemplate", hasItem("/api/orders/{id}")));
    }

    // --- M2/M4/M6 메타조회 projection 실 PG 회귀가드 (auto-commit·heavy 컬럼 미로드, doc/28·37 §7) ---

    @Test
    void specMetaEndpointsServeViaProjectionInAutoCommit() throws Exception {
        // 실 PG: active SpecRecord 영속 후 REST 메타 GET(트랜잭션 밖 auto-commit)이 200. projection(SpecMetaProjection)이
        // 메타 컬럼만 SELECT(canonicalJson/warningsJson text 미로드). (rawDoc oid 컬럼은 doc/37 §7 에서 삭제 — 함정 구조 소멸.)
        String host = "specmeta.example.com";
        registerDomain(host);
        SpecRecord spec = new SpecRecord();
        spec.setHost(host);
        spec.setSpecName("default");
        spec.setFilename("users-api.yaml");
        spec.setFormat(SpecFormat.OPENAPI);
        spec.setSpecVersion(1L);
        spec.setEndpointCount(3);
        spec.setCanonicalJson("[]");
        spec.setUploadedAt(Instant.EPOCH);
        spec.setActive(true);
        specRepo.save(spec);

        // M6 GET /spec — projection → 200·List(filename 포함).
        mvc.perform(get("/api/v1/domains/{host}/spec", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").value("users-api.yaml"))
                .andExpect(jsonPath("$[0].endpointCount").value(3));

        // M2 GET /domains/{host} — spec 메타 projection → 200(spec.filename 포함).
        mvc.perform(get("/api/v1/domains/{host}", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spec.filename").value("users-api.yaml"));

        // M4 GET /scan-status — latestSpec projection(스캔 결과 선행 필요)
        ScanResult sr = new ScanResult();
        sr.setHost(host);
        sr.setVersion("v1");
        sr.setReportJson("{\"summary\":\"ok\"}");
        scanRepo.save(sr);
        mvc.perform(get("/api/v1/domains/{host}/scan-status", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestSpec.filename").value("users-api.yaml"));
    }

    @Test
    void forHostEndpointsServeSpecPresentDomainOnRealPg() throws Exception {
        // 실 PG: forHost(/discovery·/result M5)가 spec 보유 도메인에서 200. loadActiveCanonical/activeRecords 엔티티 로드를
        // @Transactional(readOnly) 로 감싼다(doc/28 §10·D51). (rawDoc oid 컬럼은 doc/37 §7 삭제 — 엔티티 로드가 더는 oid 미materialize.)
        String host = "forhost.example.com";
        registerDomain(host);
        SpecRecord spec = new SpecRecord();
        spec.setHost(host);
        spec.setSpecName("default");
        spec.setFormat(SpecFormat.OPENAPI);
        spec.setSpecVersion(1L);
        spec.setEndpointCount(0);
        spec.setCanonicalJson("[]");                                  // active spec → loadActiveCanonical 진입
        spec.setUploadedAt(Instant.EPOCH);
        spec.setActive(true);
        specRepo.save(spec);
        DiscoveredEndpointRecord d = new DiscoveredEndpointRecord();  // findings 생성용 검출 1건
        d.setHost(host);
        d.setMethod("POST");
        d.setPathTemplate("/api/orders/{id}");
        d.setTemplateSource("INFERRED");
        d.setEndpointKind("API_CANDIDATE");
        d.setKindConfidence(0.95);
        d.setFirstSeen(Instant.EPOCH);
        d.setLastSeen(Instant.EPOCH);
        d.setLastScanAt(Instant.EPOCH);
        d.setHits(100);
        d.setStatusDistJson("{\"2xx\":100}");
        d.setHadQuery(true);
        d.setNonBrowserUa(true);
        discoveredRepo.save(d);

        // /discovery — forHost: activeRecords/loadActiveCanonical 엔티티 로드를 readOnly tx 로 감싸 → 200
        mvc.perform(get("/api/v1/domains/{host}/discovery", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings").isArray());

        // /result(M5, ⓒ) — serve-time 판단근거를 각 finding 에 인라인. forHost 경유(readOnly tx)로 oid 안전 + basis 가산.
        ScanResult r = new ScanResult();
        r.setHost(host);
        r.setVersion("v1");
        // report_json findings 에 위 검출(POST /api/orders/{id}) 매칭 1건 → forHost SHADOW basis 인라인 검증(스펙 미매칭)
        r.setReportJson("{\"summary\":\"ok\",\"findings\":[{\"host\":\"" + host
                + "\",\"method\":\"POST\",\"pathTemplate\":\"/api/orders/{id}\",\"confidence\":0.7}]}");
        scanRepo.save(r);
        mvc.perform(get("/api/v1/domains/{host}/result", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("ok"))                        // report 기존 필드 불변
                .andExpect(jsonPath("$.findings[0].classification").value("SHADOW"))  // ⓒ 인라인(스펙 미매칭→SHADOW)
                .andExpect(jsonPath("$.findings[0].basis.type").value("score"))
                .andExpect(jsonPath("$.rationale").doesNotExist());                  // 별도 배열 제거(인라인 대체)
    }

    @Test
    void specListNullSpecNameOrdersFirstDeterministicallyOnRealPg() throws Exception {
        // ★실 PG 회귀(h2-pg-null-ordering-trap): M6 projection ORDER BY 'specName asc nulls first' 가 PG 에서 결정적인지.
        // nulls first 제거 시 H2 는 통과(ASC=NULLS FIRST 기본)하나 PG 는 NULLS LAST 로 순서 발산 → 아래 단언 RED.
        String host = "nullsort.example.com";
        registerDomain(host);
        specRepo.save(activeSpec(host, null, "a-null.yaml", 1L));    // specName=null (레거시 행)
        specRepo.save(activeSpec(host, "zzz", "b-zzz.yaml", 2L));    // specName 비-null

        mvc.perform(get("/api/v1/domains/{host}/spec", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").value("a-null.yaml")) // null specName 우선(nulls first)
                .andExpect(jsonPath("$[1].filename").value("b-zzz.yaml"));
    }

    private static SpecRecord activeSpec(String host, String specName, String filename, long version) {
        SpecRecord r = new SpecRecord();
        r.setHost(host);
        r.setSpecName(specName);
        r.setFilename(filename);
        r.setFormat(SpecFormat.OPENAPI);
        r.setSpecVersion(version);
        r.setEndpointCount(1);
        r.setCanonicalJson("[]");
        r.setUploadedAt(Instant.EPOCH);
        r.setActive(true);
        return r;
    }

    // --- 재업로드 멱등 + reconcile 정확성 회귀가드: PUT /spec 동일 filename 재업로드 (doc/37 §9⑦, #45 이관) ---

    @Test
    void reuploadSameFilenameViaHttpDoesNotHit500() throws Exception {
        // ★실 HTTP 경로(MockMvc PUT) — 테스트 메서드 tx 없음(컨트롤러→SpecStore 진입 오버로드 프록시 경유 검증).
        // 동일 filename 재업로드 = 구 active 비활성화 + reconcile. 진입 오버로드 @Transactional(#45) 로 tx 안에서 멱등 200.
        // rawDoc 삭제(doc/37 §7)로 oid 함정 구조 소멸 — 본 가드는 재업로드 멱등 + reconcile 정확성(/apis) 검증으로 이관.
        String host = "reupload.example.com";
        registerDomain(host);

        // 1차(비활성 대상 0) → 통과
        mvc.perform(put("/api/v1/domains/{host}/spec", host).param("filename", "users.yaml")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(openApi("1.0.0", "/users/{id}")))
                .andExpect(status().isOk());
        // ★2차 동일 filename 재업로드 = 구버전 비활성화 + reconcile → 200(500 아님)
        mvc.perform(put("/api/v1/domains/{host}/spec", host).param("filename", "users.yaml")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(openApi("2.0.0", "/users/{id}", "/orders/{id}")))
                .andExpect(status().isOk());

        // 재업로드가 reconcile → 신규 엔드포인트(/v2/orders/{id}) ADDED 로 인벤토리 반영
        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host).param("specName", "users.yaml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.pathTemplate=='/v2/orders/{id}')].lastChange").value(hasItem("ADDED")));
    }

    // --- 영속 API 인벤토리 reconcile + /apis + DELETED→Zombie 결합 (doc/37 §9). 전부 MockMvc 실 HTTP(auto-commit) 경로 ---

    @Test
    void uploadsUnionPerSpecNameAndIsolateDeletionOnRealPg() throws Exception {
        // ①문서A 업로드→A API+params(ACTIVE/ADDED) ②문서B 업로드→union(A 불변·B 추가) ④★격리(B 가 A 미삭제) ⑥결정적 정렬
        String host = "inv-union.example.com";
        registerDomain(host);
        putSpec(host, "a.csv", csv(
                "GET,/a/{id},false,v1,id:path:true:integer;q:query:false:string",
                "POST,/a,false,v1,name:body:true:object"));
        // ① A 의 API ACTIVE/ADDED + params 보존
        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host).param("specName", "a.csv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.pathTemplate=='/a/{id}')].status").value(hasItem("ACTIVE")))
                .andExpect(jsonPath("$[?(@.pathTemplate=='/a/{id}')].lastChange").value(hasItem("ADDED")))
                .andExpect(jsonPath("$[?(@.pathTemplate=='/a/{id}')].params[?(@.name=='id')].in").value(hasItem("PATH")));
        // ② 다른 specName 문서 B 업로드 → union
        putSpec(host, "b.csv", csv("GET,/b,false,v1,"));
        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))                       // A2 + B1
                .andExpect(jsonPath("$[0].specName").value("a.csv"))              // ⑥ 결정적 정렬(specName asc)
                // ④ ★격리: B 업로드가 A 의 API 를 DELETED 로 만들지 않음(WHERE spec_name 한정). 위반 시 RED.
                .andExpect(jsonPath("$[?(@.specName=='a.csv')].status",
                        everyItem(org.hamcrest.Matchers.is("ACTIVE"))));
    }

    @Test
    void reuploadReconcilesUpdatedDeletedAddedUnchangedOnRealPg() throws Exception {
        // ③ 문서 재업로드(같은 specName) → param 변경=UPDATED·drop=DELETED·신규=ADDED·동일=UNCHANGED ⑧ param 변경 판정
        String host = "inv-reconcile.example.com";
        registerDomain(host);
        putSpec(host, "s.csv", csv(
                "GET,/x/{id},false,v1,q:query:false:string",
                "GET,/drop,false,v1,",
                "GET,/same,false,v1,k:query:false:string"));
        // v2: /x param type string→integer=UPDATED, /drop 제거=DELETED, /new 추가=ADDED, /same 동일=UNCHANGED
        putSpec(host, "s.csv", csv(
                "GET,/x/{id},false,v1,q:query:false:integer",
                "GET,/new,false,v1,",
                "GET,/same,false,v1,k:query:false:string"));
        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host).param("specName", "s.csv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.pathTemplate=='/x/{id}')].lastChange").value(hasItem("UPDATED")))
                .andExpect(jsonPath("$[?(@.pathTemplate=='/x/{id}')].params[?(@.name=='q')].type").value(hasItem("integer")))
                .andExpect(jsonPath("$[?(@.pathTemplate=='/drop')].status").value(hasItem("DELETED")))
                .andExpect(jsonPath("$[?(@.pathTemplate=='/new')].lastChange").value(hasItem("ADDED")))
                .andExpect(jsonPath("$[?(@.pathTemplate=='/same')].lastChange").value(hasItem("UNCHANGED")));
    }

    @Test
    void deletedApiWithTrafficClassifiesAsDeletedFromSpecZombieOnRealPg() throws Exception {
        // ⑨ ★사용자 핵심: DELETED 인벤토리 키 ∩ 관측 트래픽 → Zombie(deleted-from-spec·confidence 0.8), SHADOW 아님.
        String host = "inv-zombie.example.com";
        registerDomain(host);
        putSpec(host, "z.csv", csv("GET,/legacy/{id},false,v1,"));     // v1: /legacy/{id} 문서화
        putSpec(host, "z.csv", csv("GET,/other,false,v1,"));            // v2: /legacy/{id} 제거 → DELETED
        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host).param("status", "DELETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.pathTemplate=='/legacy/{id}')].status").value(hasItem("DELETED")));

        DiscoveredEndpointRecord d = new DiscoveredEndpointRecord();    // 관측 트래픽 지속(/legacy/{id})
        d.setHost(host);
        d.setMethod("GET");
        d.setPathTemplate("/legacy/{id}");
        d.setTemplateSource("INFERRED");
        d.setEndpointKind("API_CANDIDATE");
        d.setKindConfidence(0.95);
        d.setFirstSeen(Instant.EPOCH);
        d.setLastSeen(Instant.EPOCH);
        d.setLastScanAt(Instant.EPOCH);
        d.setHits(50);
        d.setStatusDistJson("{\"2xx\":50}");
        d.setHadQuery(false);
        d.setNonBrowserUa(true);
        discoveredRepo.save(d);

        // /discovery → /legacy/{id} = Zombie(deleted-from-spec, confidence 0.8). specRef="deleted-from-spec" 는 이 경로 고유
        // (Shadow=specRef 없음·deprecated/version Zombie=스펙 sourceRef). 결합 제거 시 SHADOW/drop → 단언 RED.
        mvc.perform(get("/api/v1/domains/{host}/discovery", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings[?(@.pathTemplate=='/legacy/{id}')].specRef").value(hasItem("deleted-from-spec")))
                .andExpect(jsonPath("$.findings[?(@.pathTemplate=='/legacy/{id}')].confidence").value(hasItem(0.8)));
    }

    // --- P2-3 풍부한 param diff + breaking 판정 (doc/38 §3·§6) ---

    @Test
    void paramDiffBreakingRulesPersistedAndExposedOnRealPg() throws Exception {
        // 한 문서의 v1→v2 재업로드로 6 규칙 + 호환 widening 을 동시 검증. lastChangeBreaking + changedParams 영속·노출.
        String host = "inv-breaking.example.com";
        registerDomain(host);
        putSpec(host, "b.csv", csv(
                "GET,/req-add,false,v1,",
                "GET,/opt-add,false,v1,",
                "GET,/opt-rm,false,v1,q:query:false:string",
                "GET,/opt2req,false,v1,q:query:false:string",
                "GET,/req2opt,false,v1,q:query:true:string",
                "GET,/typechg,false,v1,q:query:false:string",
                "GET,/widen,false,v1,q:query:false:integer"));
        putSpec(host, "b.csv", csv(
                "GET,/req-add,false,v1,x:query:true:string",      // required 추가=breaking
                "GET,/opt-add,false,v1,x:query:false:string",     // optional 추가=non
                "GET,/opt-rm,false,v1,",                          // optional 제거=breaking
                "GET,/opt2req,false,v1,q:query:true:string",      // optional→required=breaking
                "GET,/req2opt,false,v1,q:query:false:string",     // required→optional=non
                "GET,/typechg,false,v1,q:query:false:integer",    // type 비호환(string→integer)=breaking
                "GET,/widen,false,v1,q:query:false:number"));     // type 호환(integer→number)=non

        var r = mvc.perform(get("/api/v1/domains/{host}/spec/apis", host).param("specName", "b.csv"))
                .andExpect(status().isOk());
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/req-add')].lastChangeBreaking").value(hasItem(true)));
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/opt-add')].lastChangeBreaking").value(hasItem(false)));
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/opt-rm')].lastChangeBreaking").value(hasItem(true)));
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/opt2req')].lastChangeBreaking").value(hasItem(true)));
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/req2opt')].lastChangeBreaking").value(hasItem(false)));
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/typechg')].lastChangeBreaking").value(hasItem(true)));
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/widen')].lastChangeBreaking").value(hasItem(false)));
        // changedParams 영속·노출(대표): added/removed/modified
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/req-add')].changedParams.added[*].name").value(hasItem("x")));
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/opt-rm')].changedParams.removed[*].name").value(hasItem("q")));
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/typechg')].changedParams.modified[*].toType").value(hasItem("integer")));
        r.andExpect(jsonPath("$[?(@.pathTemplate=='/widen')].changedParams.modified[*].fromType").value(hasItem("integer")));

        // ?breaking=true → breaking UPDATED 만(req-add·opt-rm·opt2req·typechg=4)
        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host).param("specName", "b.csv").param("breaking", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].lastChangeBreaking", everyItem(org.hamcrest.Matchers.is(true))));
    }

    // --- P2-4 도메인-merged 뷰 (doc/38 §4·§6) ---

    @Test
    void mergedViewMergesOverlapWithLatestWinsRulesOnRealPg() throws Exception {
        // docA·docB 가 같은 (method,path) 정의 → merged 1행: status ACTIVE·deprecated OR·version/params latest(sourceSpecVersion)·contributing 2.
        String host = "inv-merged.example.com";
        registerDomain(host);
        putSpec(host, "a.csv", csv(
                "GET,/shared,false,v1,q:query:false:string",
                "GET,/a-only,false,v1,"));
        putSpec(host, "b.csv", csv(
                "GET,/shared,true,v2,q:query:false:integer",       // deprecated·version v2·param integer (sourceSpecVersion 더 큼)
                "GET,/b-only,false,v2,"));

        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host).param("view", "merged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))                                   // /shared 1행(병합)+/a-only+/b-only
                .andExpect(jsonPath("$[?(@.pathTemplate=='/shared')].status").value(hasItem("ACTIVE")))
                .andExpect(jsonPath("$[?(@.pathTemplate=='/shared')].deprecated").value(hasItem(true)))   // OR
                .andExpect(jsonPath("$[?(@.pathTemplate=='/shared')].version").value(hasItem("v2")))       // latest-wins
                .andExpect(jsonPath("$[?(@.pathTemplate=='/shared')].params[*].type").value(hasItem("integer"))) // latest-active params
                .andExpect(jsonPath("$[?(@.pathTemplate=='/shared')].contributingSpecNames.length()").value(hasItem(2)));
        // 비-merged(현행 기본)와 공존 — per-document 행(specName 보유, /shared 는 문서별 2행)
        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))                                   // a.csv 2 + b.csv 2
                .andExpect(jsonPath("$[?(@.pathTemplate=='/shared')].specName")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("a.csv", "b.csv")));
    }

    @Test
    void mergedViewStatusCombosOnRealPg() throws Exception {
        // 전부 DELETED→DELETED, 혼합(한쪽 ACTIVE)→ACTIVE.
        String host = "inv-merged-status.example.com";
        registerDomain(host);
        putSpec(host, "c.csv", csv("GET,/alldel,false,v1,", "GET,/mixed,false,v1,"));
        putSpec(host, "d.csv", csv("GET,/mixed,false,v1,", "GET,/d-only,false,v1,"));
        // c.csv 재업로드(둘 다 제거) → c 의 /alldel·/mixed = DELETED
        putSpec(host, "c.csv", csv("GET,/placeholder,false,v1,"));

        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host).param("view", "merged"))
                .andExpect(status().isOk())
                // /alldel: c 전용·DELETED → 전부 DELETED → DELETED
                .andExpect(jsonPath("$[?(@.pathTemplate=='/alldel')].status").value(hasItem("DELETED")))
                // /mixed: c=DELETED·d=ACTIVE → 혼합 → ACTIVE
                .andExpect(jsonPath("$[?(@.pathTemplate=='/mixed')].status").value(hasItem("ACTIVE")))
                .andExpect(jsonPath("$[?(@.pathTemplate=='/mixed')].contributingSpecNames.length()").value(hasItem(2)));
        // status 필터(병합 후) — DELETED 만
        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host).param("view", "merged").param("status", "DELETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].pathTemplate").value(hasItem("/alldel")))
                .andExpect(jsonPath("$[*].status", everyItem(org.hamcrest.Matchers.is("DELETED"))));
    }

    @Test
    void mergedViewVersionFromActivePoolNotDeletedRowOnRealPg() throws Exception {
        // ★P3-1 엣지: docA 가 /shared 를 ACTIVE 로 유지(version 1.0.0)·docB 가 나중 업로드서 /shared 제거(그 DELETED 행 sourceSpecVersion 최대).
        // merged 대표값은 ACTIVE pool 기준 — version=1.0.0(삭제 문서 2.0.0 아님). 수정 전(latestAll) 이면 2.0.0 으로 RED.
        String host = "inv-merged-ver.example.com";
        registerDomain(host);
        putSpec(host, "a.csv", csv("GET,/shared,false,1.0.0,"));                 // sourceSpecVersion 1·ACTIVE 유지
        putSpec(host, "b.csv", csv("GET,/shared,false,2.0.0,", "GET,/b-keep,false,2.0.0,")); // sourceSpecVersion 2
        putSpec(host, "b.csv", csv("GET,/b-keep,false,2.0.0,"));                 // /shared 제거 → b.csv /shared DELETED(sourceSpecVersion 2)

        mvc.perform(get("/api/v1/domains/{host}/spec/apis", host).param("view", "merged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.pathTemplate=='/shared')].status").value(hasItem("ACTIVE")))
                .andExpect(jsonPath("$[?(@.pathTemplate=='/shared')].version").value(hasItem("1.0.0")))      // ★ACTIVE 문서값(삭제 문서 2.0.0 아님)
                .andExpect(jsonPath("$[?(@.pathTemplate=='/shared')].sourceSpecVersion").value(hasItem(1)))   // ACTIVE pool 기준(삭제 행 2 아님)
                .andExpect(jsonPath("$[?(@.pathTemplate=='/shared')].contributingSpecNames.length()").value(hasItem(2)));
    }

    private void putSpec(String host, String filename, byte[] content) throws Exception {
        mvc.perform(put("/api/v1/domains/{host}/spec", host).param("filename", filename)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(content))
                .andExpect(status().isOk());
    }

    /** CSV 스펙(method,path,deprecated,version,params) — params=name:in:required:type 세미콜론 구분(doc/37 §2). */
    private static byte[] csv(String... rows) {
        StringBuilder sb = new StringBuilder("method,path,deprecated,version,params\n");
        for (String r : rows) {
            sb.append(r).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 최소 유효 OpenAPI(servers /v2 prefix → canonical /v2 path). paths=GET 엔드포인트들. */
    private static byte[] openApi(String version, String... paths) {
        StringBuilder sb = new StringBuilder();
        sb.append("openapi: 3.0.1\n");
        sb.append("info:\n  title: Example API\n  version: ").append(version).append('\n');
        sb.append("servers:\n  - url: https://api.example.com/v2\n");
        sb.append("paths:\n");
        for (String p : paths) {
            sb.append("  ").append(p).append(":\n");
            sb.append("    get:\n      responses:\n        '200':\n          description: ok\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- L53: 조건부 GET 304 (reportJson @Lob TEXT read 동시 검증) ---

    @Test
    void resultConditionalGetReturns304OnMatchingEtag() throws Exception {
        String host = "etag.example.com";
        ScanResult r = new ScanResult();
        r.setHost(host);
        r.setVersion("v-abc123");
        r.setReportJson("{\"summary\":\"ok\"}");
        scanRepo.save(r);

        MvcResult first = mvc.perform(get("/api/v1/domains/{host}/result", host))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v-abc123\""))
                .andExpect(jsonPath("$.summary").value("ok")) // @Lob TEXT read 경로
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");

        mvc.perform(get("/api/v1/domains/{host}/result", host).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    // --- P3-1: 디스커버리 업서트 managed-tx 경로(실 PG) — hostnames 합집합·lastSeenAt 만, 사용자 설정 보존 ---

    @Test
    void discoveryUpsertMergesHostnamesAndPreservesUserSettingsOnRealPg() {
        String host = "upsert.example.com";
        DomainConfig seed = new DomainConfig();
        seed.setHost(host);
        seed.setHostnames(new java.util.ArrayList<>(java.util.List.of("OLD1")));
        seed.setEnabled(false);                                  // 사용자 설정
        seed.setBasePathStrip("/v2");
        seed.setSpecMergeStrategy(com.pentasecurity.apidiscover.model.SpecMergeStrategy.SEPARATE);
        seed.setIntervalOverride("PT30M");
        seed.setDiscoveredAt(Instant.parse("2026-06-01T00:00:00Z"));
        domainRepo.save(seed);

        Instant now = Instant.parse("2026-06-25T10:00:00Z");
        // 실 JPA managed-tx 업서트(별도 @Transactional 빈) — detached merge 아님
        boolean inserted = domainUpserter.upsert(host, java.util.Set.of("NEW1"), now);

        assertThat(inserted).isFalse();
        DomainConfig after = domainRepo.findById(host).orElseThrow();
        assertThat(after.getHostnames()).containsExactly("NEW1", "OLD1");           // 합집합
        assertThat(after.getLastSeenAt()).isEqualTo(now);                            // 갱신
        // ★사용자 설정 보존(managed dirty-check + @DynamicUpdate → 설정 컬럼 UPDATE 제외)
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getBasePathStrip()).isEqualTo("/v2");
        assertThat(after.getSpecMergeStrategy())
                .isEqualTo(com.pentasecurity.apidiscover.model.SpecMergeStrategy.SEPARATE);
        assertThat(after.getIntervalOverride()).isEqualTo("PT30M");
        assertThat(after.getDiscoveredAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z")); // 최초 발견 불변
    }

    // --- P1: findDueForScan null 정렬 결정화(실 PG) — PG 기본 ASC=NULLS LAST 회귀 가드(D48) ---

    @Test
    void findDueForScanOrdersNullsFirstOnRealPg() {
        // dated-due(과거 서로 다른 값) 여러 개 + null(신규 미스캔) 1개 → null 이 LIMIT K 안 맨 앞이어야 함.
        // PG 기본 ASC=NULLS LAST 였으면 null 이 후미라 top-K 에서 탈락 → 이 단언이 빨강(회귀 검출).
        Instant now = Instant.parse("2026-06-26T12:00:00Z");
        domainRepo.save(due("dated1.example.com", now.minus(Duration.ofHours(1))));
        domainRepo.save(due("dated2.example.com", now.minus(Duration.ofHours(2))));
        domainRepo.save(due("dated3.example.com", now.minus(Duration.ofHours(3)))); // 가장 오래
        domainRepo.save(due("never.example.com", null));                            // null=즉시 due

        List<DomainConfig> top2 = domainRepo.findDueForScan(now, PageRequest.of(0, 2)); // K=2

        assertThat(top2).hasSize(2);
        assertThat(top2.get(0).getHost()).as("nulls first — 신규 미스캔이 맨 앞").isEqualTo("never.example.com");
        assertThat(top2.get(1).getHost()).as("그다음 가장 오래된 dated").isEqualTo("dated3.example.com");
    }

    // --- ★무접속 중단(D82): activity_status 게이트 + sweep/markActive 전이 (실 PG) ---

    @Test
    void findDueForScanExcludesInactiveStatusOnRealPg() {
        // D82: 게이트 = activity_status(과거 lastSeenAt staleCutoff 대체). ★실 PG 가드: JPQL enum literal 술어가
        // 실 PG 에서 동작하는지(H2 가 가릴 수 있는 dialect, h2-pg trap). null lastSeenAt·ACTIVE 기본 = 포함.
        Instant now = Instant.parse("2026-06-26T12:00:00Z");
        domainRepo.save(seen("active.example.com", now.minus(Duration.ofDays(1))));  // ACTIVE 기본 → 포함
        domainRepo.save(seen("nullseen.example.com", null));                         // lastSeenAt null·ACTIVE → 포함
        DomainConfig inactive = seen("inactive.example.com", now.minus(Duration.ofDays(40)));
        inactive.setActivityStatus(ActivityStatus.INACTIVE);
        domainRepo.save(inactive);

        List<String> hosts = domainRepo.findDueForScan(now, PageRequest.of(0, 10))
                .stream().map(DomainConfig::getHost).toList();
        assertThat(hosts).contains("active.example.com", "nullseen.example.com");
        assertThat(hosts).doesNotContain("inactive.example.com"); // INACTIVE → 스캔 제외
    }

    @Test
    void sweepDeactivateStaleAndMarkActiveOnRealPg() {
        // D82 sweep: lastSeenAt<cutoff 인 ACTIVE → INACTIVE(실 PG bulk UPDATE). null lastSeenAt 은 미강등(is not null 가드).
        // markActive: INACTIVE→ACTIVE 복귀(수동 스캔 승격), 이미 ACTIVE 면 no-op. bulk UPDATE 후 findDueForScan(DB 술어)로 관측(L1 캐시 무관).
        Instant now = Instant.parse("2026-06-26T12:00:00Z");
        Instant cutoff = now.minus(Duration.ofDays(7));
        domainRepo.save(seen("recent.example.com", now.minus(Duration.ofDays(1))));  // 최근 → 유지
        domainRepo.save(seen("stale.example.com", now.minus(Duration.ofDays(40))));  // 무접속 → 강등
        domainRepo.save(seen("nullseen.example.com", null));                         // null → 미강등
        domainRepo.flush();

        assertThat(domainRepo.deactivateStale(now, cutoff)).isEqualTo(1); // stale 1건만 강등
        List<String> due = domainRepo.findDueForScan(now, PageRequest.of(0, 10))
                .stream().map(DomainConfig::getHost).toList();
        assertThat(due).contains("recent.example.com", "nullseen.example.com").doesNotContain("stale.example.com");

        assertThat(domainRepo.markActive("stale.example.com", now)).isEqualTo(1); // 승격
        assertThat(domainRepo.markActive("recent.example.com", now)).isZero();    // 이미 ACTIVE=no-op
        List<String> due2 = domainRepo.findDueForScan(now, PageRequest.of(0, 10))
                .stream().map(DomainConfig::getHost).toList();
        assertThat(due2).contains("stale.example.com"); // 재활성 후 다시 스캔 대상
    }

    @Test
    void findDueWithNewTrafficPartitionsOnRealPg() {
        // D64 활성 우선 — ★실 PG 가드: 크로스-엔티티 left join(on w.host=d.host) JPQL 이 실 PG 에서 동작·분할 정확한지
        // (H2 가 가릴 수 있는 dialect 발산 대비, h2-pg trap). 세 부류: 신규트래픽/무트래픽/미스캔.
        Instant now = Instant.parse("2026-06-26T12:00:00Z");
        Instant wmEnd = now.minus(Duration.ofHours(1));
        domainRepo.save(seen("busy.example.com", now.minus(Duration.ofMinutes(5))));   // lastSeen > wm = 신규
        domainRepo.save(seen("idle.example.com", now.minus(Duration.ofHours(2))));     // lastSeen <= wm = 무트래픽
        domainRepo.save(seen("fresh.example.com", now.minus(Duration.ofMinutes(5))));  // 워터마크 없음 = 미스캔(신규 취급)
        for (String h : List.of("busy.example.com", "idle.example.com")) {
            com.pentasecurity.apidiscover.domain.Watermark w = new com.pentasecurity.apidiscover.domain.Watermark();
            w.setHost(h);
            w.setLastEnd(wmEnd);
            watermarkRepo.save(w);
        }

        List<String> active = domainRepo.findDueWithNewTraffic(now, PageRequest.of(0, 10))
                .stream().map(DomainConfig::getHost).toList();
        List<String> rest = domainRepo.findDueWithoutNewTraffic(now, PageRequest.of(0, 10))
                .stream().map(DomainConfig::getHost).toList();

        assertThat(active).contains("busy.example.com", "fresh.example.com").doesNotContain("idle.example.com");
        assertThat(rest).contains("idle.example.com").doesNotContain("busy.example.com", "fresh.example.com");
    }

    // --- helpers ---

    private DomainConfig due(String host, Instant nextScanDueAt) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(true);
        d.setNextScanDueAt(nextScanDueAt);
        return d;
    }

    /** 즉시 due(nextScanDueAt=null) + discovery 관측시각(lastSeenAt) 지정 — 무접속 제외 게이트 테스트용(D59, D57 재설계). */
    private DomainConfig seen(String host, Instant lastSeenAt) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(true);
        d.setNextScanDueAt(null);
        d.setLastSeenAt(lastSeenAt);
        return d;
    }

    /** loadDiscovered 와 동일하게 prior 맵을 제약 튜플(method+host+path_template) 키로 구성. */
    private Map<String, DiscoveredEndpointRecord> priorFor(String host) {
        Map<String, DiscoveredEndpointRecord> prior = new HashMap<>();
        for (DiscoveredEndpointRecord rec : discoveredRepo.findByHost(host)) {
            prior.put(rec.getMethod() + " " + rec.getHost() + " " + rec.getPathTemplate(), rec);
        }
        return prior;
    }

    private static DiscoveredEndpoint endpoint(String host, String method, String template, long hits) {
        DiscoveredEndpoint.Metrics m = new DiscoveredEndpoint.Metrics(
                hits, Instant.EPOCH, Instant.EPOCH, Map.of("2xx", hits), 1, 5, 10);
        return new DiscoveredEndpoint(method + " " + host + " " + template, method, host, template,
                TemplateSource.INFERRED, EndpointKind.UNKNOWN, 0.0, false, false, m, ParamCandidates.EMPTY);
    }

    private void registerDomain(String host) {
        DomainConfig dc = new DomainConfig();
        dc.setHost(host);
        dc.setEnabled(true);
        dc.setCreatedAt(Instant.EPOCH);
        dc.setUpdatedAt(Instant.EPOCH);
        domainRepo.save(dc);
    }

    // --- ★D56: 정적 분류 규칙 DB 외부화 — 목록/추가/삭제/reload (실 PG) ---

    @Test
    void staticClassifyRulesCrudAndReloadOnRealPg() throws Exception {
        // 기동 시 기본값 seed — 확장자/토큰 목록 조회(link 기본 토큰·css 확장자 포함)
        mvc.perform(get("/api/v1/config/static-classify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensions", hasItem(".css")))
                .andExpect(jsonPath("$.nameTokens", hasItem("link")));

        // 신규 토큰 추가 → 201 + 즉시 재적용(목록 반영)
        mvc.perform(post("/api/v1/config/static-classify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NAME_TOKEN\",\"value\":\"zzztoken\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nameTokens", hasItem("zzztoken")));

        // 삭제 → 204, 목록에서 제거(자기정리)
        mvc.perform(delete("/api/v1/config/static-classify/NAME_TOKEN/zzztoken"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/config/static-classify"))
                .andExpect(jsonPath("$.nameTokens", not(hasItem("zzztoken"))));

        // 잘못된 kind → 400
        mvc.perform(post("/api/v1/config/static-classify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BOGUS\",\"value\":\"x\"}"))
                .andExpect(status().isBadRequest());

        // reload → 200(외부 DB 수정 반영용)
        mvc.perform(post("/api/v1/config/static-classify/reload"))
                .andExpect(status().isOk());
    }

    /** ~60KB 의사 JSON 문자열 — varchar 길이 한계 초과 + LOB 경로 강제. */
    private static String bigJson() {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 2000; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"k\":").append(i).append(",\"v\":\"payload-").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }
}
