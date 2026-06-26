// 실 PostgreSQL(Testcontainers) 통합 테스트 — @Lob→TEXT 실검증 + JPA/REST/304 e2e (doc/28, D40)
package com.pentasecurity.apidiscover.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
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
import com.pentasecurity.apidiscover.ingest.LokiClient;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    @Autowired EffectiveClassificationResolver resolver;
    @Autowired com.pentasecurity.apidiscover.batch.DomainUpserter domainUpserter;

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
        spec.setRawDoc(big.getBytes(StandardCharsets.UTF_8)); // raw_doc(byte[]) round-trip 동시 검증(§6.2)
        SpecRecord sps = specRepo.save(spec);
        SpecRecord spl = specRepo.findById(sps.getId()).orElseThrow();
        assertThat(spl.getCanonicalJson()).isEqualTo(big);
        assertThat(spl.getWarningsJson()).isEqualTo(big);
        assertThat(spl.getRawDoc()).isEqualTo(big.getBytes(StandardCharsets.UTF_8));

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

    /** raw_doc(@Lob byte[]) 는 String 과 매핑 다름(§6.2) — round-trip 은 위에서, 실 타입은 정보성 기록(text 단언 금지). */
    @Test
    void rawDocActualTypeRecorded() {
        String dataType = jdbc.queryForObject(
                "select data_type from information_schema.columns where table_name = ? and column_name = ?",
                String.class, "spec_record", "raw_doc");
        log.info("spec_record.raw_doc PG data_type = {} (정보성 — byte[] LOB, text 단언 대상 아님)", dataType);
        assertThat(dataType).isNotBlank();
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

    // --- helpers ---

    private DomainConfig due(String host, Instant nextScanDueAt) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(true);
        d.setNextScanDueAt(nextScanDueAt);
        return d;
    }

    private void registerDomain(String host) {
        DomainConfig dc = new DomainConfig();
        dc.setHost(host);
        dc.setEnabled(true);
        dc.setCreatedAt(Instant.EPOCH);
        dc.setUpdatedAt(Instant.EPOCH);
        domainRepo.save(dc);
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
