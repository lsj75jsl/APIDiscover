# 내부 DB 테이블 스키마 (엔티티 역설계)

> 범위: `domain` 패키지의 JPA `@Entity` 7종에서 역설계한 **실제 DB 테이블 스키마** 참고 문서.
> 코드(엔티티 애너테이션)가 단일 진실원이며, 이 문서는 그것을 1:1 로 기술한다. 마이그레이션 스크립트는 없다(아래 §1).
> 근거 설계: doc/06(스택)·doc/10(분류 설정 저장)·DECISIONS **D11/D17**. 작성 기준 브랜치: `docs/tasks-sync-and-db-schema`.

---

## 1. 개요 — 스키마 생성 방식과 이식성 컨벤션

### 1.1 스키마는 Hibernate 가 자동 생성한다 (마이그레이션 미사용)

- `application.yml`: `spring.jpa.hibernate.ddl-auto: update`. 애플리케이션 기동 시 Hibernate 가 엔티티 매핑을 보고 테이블을
  생성/추가한다. **Flyway/Liquibase 등 마이그레이션 도구는 사용하지 않는다.** 따라서 이 문서가 곧 스키마의 사람이 읽는 형태다.
- `ddl-auto: update` 는 컬럼 **추가**만 하고 삭제/타입 변경은 하지 않는다. 컬럼 삭제·rename·타입 축소는 자동 반영되지 않으므로
  운영 스키마 변경 시 별도 수동 처리가 필요하다(확인 필요 — 운영 배포 절차는 본 문서 범위 밖).

### 1.2 H2(dev) / PostgreSQL(prod) 양쪽 호환 컨벤션

| 항목 | dev | prod | 비고 |
|------|-----|------|------|
| DBMS | H2 in-memory | PostgreSQL | `build.gradle.kts` 41행 `org.postgresql:postgresql` runtimeOnly |
| 접속 | `jdbc:h2:mem:apidiscover;DB_CLOSE_DELAY=-1` (application.yml) | 운영 프로파일에서 주입(확인 필요 — prod 프로파일 yml 은 현재 리포에 없음, 런타임 주입 전제) | — |

이식성을 위해 **벤더 전용 JSON 타입(PostgreSQL JSONB)을 쓰지 않는다.** JSON 데이터는 모두 `String` 필드에 `@Column(columnDefinition = "text")` 를
명시해 저장하고(H2/PG 모두 `text`) 애플리케이션이 Jackson 으로 직렬화/역직렬화한다(근거 doc/10 §2·§4, DECISIONS D17). 이로써 H2/PG 가 동일하게 동작한다.
(초기에는 `@Lob String` 이었으나 PG 에서 `oid` 로 매핑돼 실결함이 발생했고, PR #20/D40(doc/28)에서 9컬럼을 `text` 명시 매핑으로 전환해 해소했다 — §1.3 함정·§5 참조.)

> 참고: DECISIONS **D11** 은 PostgreSQL 채택 사유로 "JSONB"를 들지만, 실제 스키마는 이식성 때문에 JSONB 컬럼 타입을 쓰지 않는다.
> JSONB 는 향후 활용 여지일 뿐 현재 매핑에는 등장하지 않는다.

사용 중인 JPA 매핑 컨벤션은 다음과 같다.

- `String` + `@Column(columnDefinition = "text")` → 큰 JSON 문자열. H2/PG 모두 `text`(과거 `@Lob String` 은 PG `oid` 결함 → D40 에서 전환, §1.3).
- `@Lob byte[]` → 원본 바이너리(BLOB / PG `oid`). `spec_record.raw_doc` 한정.
- `@Enumerated(EnumType.STRING)` → enum 을 이름 문자열(VARCHAR)로 저장. 숫자 ordinal 미사용(순서 변경 안전).
- `@ElementCollection` + `@CollectionTable` → 컬렉션 필드를 **자식 테이블로 분리**.
- public 필드(스캐폴딩 단순화, 캡슐화는 후속 TODO — 엔티티 주석 참조).

### 1.3 JPA 타입 → SQL 타입 매핑 규칙

이 문서의 모든 테이블표는 아래 규칙을 따른다(Hibernate 6 / Spring Boot 3.3 기준).

| Java / JPA | H2 SQL | PostgreSQL SQL | nullable 기본 |
|------------|--------|----------------|---------------|
| `String` (length 미지정) | `VARCHAR(255)` | `varchar(255)` | nullable |
| `String` + `@Column(columnDefinition="text")` (JSON) | `text` | `text` (PR #20/D40 실측 확정) | nullable |
| `@Lob byte[]` | `BLOB` | `oid` (PR #20/D40 실측 확정 — `spec_record.raw_doc` 한정, 범위 밖·그대로 유지) | nullable |
| `Instant` | `TIMESTAMP(6)` | `timestamp(6)` | nullable (UTC 저장) |
| `long` (primitive) | `BIGINT` | `bigint` | **NOT NULL** |
| `Long` (wrapper) | `BIGINT` | `bigint` | nullable |
| `int` (primitive) | `INTEGER` | `integer` | **NOT NULL** |
| `Double` (wrapper) | `DOUBLE` | `double precision` | nullable |
| `boolean` (primitive) | `BOOLEAN` | `boolean` | **NOT NULL** |
| `@Enumerated(STRING)` enum | `VARCHAR(255)` | `varchar(255)` | nullable(필드 기준) |

**nullable 규칙**: 어떤 필드에도 `@Column(nullable=false)` 가 없다. 따라서 nullable 은 전적으로 타입으로 결정된다 —
원시 타입(`long`/`int`/`boolean`)은 NOT NULL, 래퍼/객체 타입(`String`/`Long`/`Double`/`Instant`/enum/`byte[]`)은 nullable.
`@Id` 컬럼은 PK 이므로 묵시적 NOT NULL.

**`@Lob String` 의 PostgreSQL 매핑 함정 (확인·해소됨)**: Hibernate 6 는 `@Lob String` 을 CLOB 으로 보고 **PostgreSQL 에서 기본 `oid`(large
object, `pg_largeobject` 사용)** 로 매핑한다 — 흔히 기대하는 `text` 가 아니다. `oid` 는 일반 SELECT 로 본문이 안 보이고
백업/삭제 시 LOB 정리가 별도로 필요해 운영상 불리하다. 회피하려면 컬럼에 `@Column(columnDefinition = "text")` 또는
`@JdbcTypeCode(SqlTypes.LONGVARCHAR)` 를 명시해 `text` 로 강제한다. **PR #20(D40, doc/28)에서 실 PostgreSQL(Testcontainers)로 실측한 결과
이 함정이 사실로 확인됐다** — 9개 `@Lob String` 컬럼이 전부 `oid` 로 매핑됐고, 비트랜잭션 경로(`CombinedDiscoveryService.forHost`→`/discovery`)에서
`Unable to access lob stream`(auto-commit LOB) 실 운영결함까지 발생했다. 이에 5엔티티 9개 String 필드를 `@Column(columnDefinition = "text")` 로 변경해
PG 실제 타입 `text`(`information_schema.data_type='text'` 단언 통과)·정상 `setString` 바인딩으로 **해소**했다(H2 호환). 따라서 현재 9컬럼은 모두 `text` 다(§5).

**필드 기본값 주의**: `enabled = true`, `state = "idle"`, `profile = MIDDLE`, `id = 1L` 등은 **Java 필드 초기화자**이지
SQL `DEFAULT` 절이 아니다(`columnDefinition` 미지정). JPA 로 영속할 때 객체에 채워진 값이 INSERT 되며, JPA 를 우회한 직접 SQL
INSERT 에는 이 기본값이 적용되지 않는다.

> Spring Batch 메타데이터 테이블(`BATCH_JOB_INSTANCE` 등)도 같은 스키마에 생성된다(`spring.batch.jdbc.initialize-schema: always`).
> 이는 Spring Batch 가 제공하는 스크립트로 관리되며 본 문서(도메인 엔티티) 범위 밖이다.

---

## 2. 테이블별 스키마

총 8개 테이블 — 엔티티 7종이 직접 매핑하는 7개 + `DomainConfig.hostnames` 의 `@ElementCollection` 자식 테이블 1개(`domain_hostnames`).

### 2.1 `domain_config` — 분석 대상 도메인 설정

엔티티 `DomainConfig` (`@Table(name = "domain_config")`). 설계 출처 doc/07 §3.1, doc/05 §2.3.

| 컬럼 | 필드 | JPA 타입 | SQL 타입 | PK | nullable | 비고 |
|------|------|----------|----------|----|----------|------|
| `host` | `host` | `@Id String` | VARCHAR(255) | ✔ | NOT NULL | 도메인 식별자(PK) |
| `enabled` | `enabled` | `boolean` | BOOLEAN | | NOT NULL | 필드 기본값 `true`(SQL DEFAULT 아님) |
| `interval_override` | `intervalOverride` | `String` | VARCHAR(255) | | nullable | ISO-8601 Duration 문자열(예 `PT1H`) 또는 null=전역 기본 |
| `spec_merge_strategy` | `specMergeStrategy` | `@Enumerated(STRING) SpecMergeStrategy` | VARCHAR(255) | | nullable | 값: `MERGE`/`SEPARATE`/`VERSION_GROUPED`. 필드 기본값 `MERGE`. 기존 행 null → 읽을 때 `MERGE` 로 해석(`SpecStore`, doc/26 §5) |
| `base_path_strip` | `basePathStrip` | `String` | VARCHAR(255) | | nullable | 프록시가 관측 경로에서 제거한 base prefix(예 `/v2`·`/api`)의 operator 명시값. null=off(기본). spec 매칭 라우팅 정합용. `specMergeStrategy` 와 동형 per-domain 설정(doc/27 §, D38) |
| `discovered_at` | `discoveredAt` | `Instant` | TIMESTAMP(6) | | nullable | 자동 디스커버리 최초 발견 시각(doc/30 §5). **수동 등록 도메인은 null**(자연 구분). `ddl-auto` 가산 |
| `last_seen_at` | `lastSeenAt` | `Instant` | TIMESTAMP(6) | | nullable | 자동 디스커버리 최근 관측(집계 윈도우 끝). staleness 가시화용 — **삭제 트리거 아님**(doc/30 §5). `ddl-auto` 가산 |
| `last_scan_attempt_at` | `lastScanAttemptAt` | `Instant` | TIMESTAMP(6) | | nullable | 스캔 라운드로빈 커서(least-recently-scanned asc nulls-first, doc/33 §1 B). attempt 마다 전진(skip 포함). `ddl-auto` 가산 |
| `next_scan_due_at` | `nextScanDueAt` | `Instant` | TIMESTAMP(6) **(@Index)** | | nullable | 다음 스캔 due 시각(doc/33 §4, D48) — 스캔 시 `now + effectiveInterval` 갱신, **null=즉시 due**. `@Table(indexes=@Index(columnList="next_scan_due_at"))` 로 인덱스 생성. `ddl-auto` 가산 |
| `created_at` | `createdAt` | `Instant` | TIMESTAMP(6) | | nullable | |
| `updated_at` | `updatedAt` | `Instant` | TIMESTAMP(6) | | nullable | |

> 컬럼명: 명시 `@Column(name=...)` 이 없으므로 Hibernate 기본 네이밍 전략(camelCase → snake_case)으로 `intervalOverride` →
> `interval_override`, `createdAt` → `created_at` 등으로 매핑된다.

`hostnames` 필드는 `@ElementCollection` 이므로 본 테이블에 컬럼이 없고 아래 자식 테이블로 분리된다.

### 2.2 `domain_hostnames` — DomainConfig 의 hostnames 컬렉션 (자식 테이블)

`DomainConfig.hostnames` 의 `@ElementCollection(fetch = EAGER)` +
`@CollectionTable(name = "domain_hostnames", joinColumns = @JoinColumn(name = "host"))` + `@Column(name = "hostname")`.
한 도메인을 서빙하는 엣지 서버(Loki hostname 라벨) 목록(doc/05 §2.3).

| 컬럼 | JPA | SQL 타입 | nullable | 비고 |
|------|-----|----------|----------|------|
| `host` | `@JoinColumn(name="host")` | VARCHAR(255) | NOT NULL | `domain_config.host` 로의 **실 FK**(아래 §3 참조) |
| `hostname` | `@Column(name="hostname")` | VARCHAR(255) | nullable | 컬렉션 원소값(엣지 서버 hostname) |

- 별도 PK 컬럼 없음. `@ElementCollection` 기본 매핑으로 (host, hostname) 조합이 행을 이룬다(Hibernate 는 컬렉션 테이블에 단일
  컬럼 PK 를 두지 않음 — 확인 필요: 정확한 UNIQUE/PK 제약은 Hibernate 버전 동작에 의존).
- `fetch = EAGER` 는 ORM 로딩 전략일 뿐 스키마에 영향 없음.

### 2.3 `spec_record` — 도메인×버전 API 스펙 저장

엔티티 `SpecRecord` (`@Table(name = "spec_record")`). 설계 출처 doc/03 §7.3.

| 컬럼 | 필드 | JPA 타입 | SQL 타입 | PK | nullable | 비고 |
|------|------|----------|----------|----|----------|------|
| `id` | `id` | `@Id @GeneratedValue(IDENTITY) Long` | BIGINT (auto-increment / IDENTITY) | ✔ | NOT NULL | DB 자동 채번 |
| `host` | `host` | `String` | VARCHAR(255) | | nullable | 논리 FK → `domain_config.host` |
| `spec_name` | `specName` | `String` | VARCHAR(255) | | nullable | host 내 문서 식별(멀티 스펙). null → `"default"` 로 해석. `specName` 별 최신 active = host active set (doc/26 §3) |
| `format` | `format` | `@Enumerated(STRING) SpecFormat` | VARCHAR(255) | | nullable | 값: `OPENAPI` / `POSTMAN` / `CSV` |
| `spec_version` | `specVersion` | `long` | BIGINT | | NOT NULL | 도메인별 증가 버전 |
| `raw_doc` | `rawDoc` | `@Lob byte[]` | BLOB (PG `oid`) | | nullable | 원본 문서(감사/재파싱용). PG 실측 타입 `oid` 확정(범위 밖, 그대로 유지·round-trip 검증, PR #20/D40) |
| `canonical_json` | `canonicalJson` | `@Column(columnDefinition="text") String` | text | | nullable | Canonical 엔드포인트 집합 JSON(매칭의 진실원). PG `text`(D40) |
| `warnings_json` | `warningsJson` | `@Column(columnDefinition="text") String` | text | | nullable | 파싱 recoverable 경고 `List<String>` 직렬화. 스캔이 `specSource.warnings` 로 로드(doc/25 §A.2). PG `text`(D40) |
| `endpoint_count` | `endpointCount` | `int` | INTEGER | | NOT NULL | |
| `uploaded_at` | `uploadedAt` | `Instant` | TIMESTAMP(6) | | nullable | |
| `active` | `active` | `boolean` | BOOLEAN | | NOT NULL | 활성 버전 여부 |

> 본 엔티티만 `@GeneratedValue(IDENTITY)` 합성 키를 쓴다(나머지는 자연키 `host` 또는 고정 PK). 같은 host 의 여러 버전을 행으로
> 보관하므로 host 는 PK 가 아니다. 활성 1건 조회는 `findFirstByHostAndActiveIsTrueOrderBySpecVersionDesc`(리포지토리).

### 2.4 `scan_result` — 도메인별 최신 스캔 결과

엔티티 `ScanResult` (`@Table(name = "scan_result")`). 설계 출처 doc/07 §3.2·§3.3, `reportJson`=doc/01 §4·doc/12.

| 컬럼 | 필드 | JPA 타입 | SQL 타입 | PK | nullable | 비고 |
|------|------|----------|----------|----|----------|------|
| `host` | `host` | `@Id String` | VARCHAR(255) | ✔ | NOT NULL | 도메인별 최신 결과 1건(PK) |
| `state` | `state` | `String` | VARCHAR(255) | | nullable | `idle`/`running`/`failed`. 필드 기본값 `idle` |
| `last_scan_at` | `lastScanAt` | `Instant` | TIMESTAMP(6) | | nullable | |
| `version` | `version` | `String` | VARCHAR(255) | | nullable | 결과 ETag(중앙 조건부 GET 기준) |
| `spec_version` | `specVersion` | `long` | BIGINT | | NOT NULL | |
| `window_from` | `windowFrom` | `Instant` | TIMESTAMP(6) | | nullable | |
| `window_to` | `windowTo` | `Instant` | TIMESTAMP(6) | | nullable | |
| `report_json` | `reportJson` | `@Column(columnDefinition="text") String` | text | | nullable | **전체 `DiscoveryReport` JSON**(doc/01 §4, doc/12) — 엔드포인트별 상세(분류·confidence·severity·params·dropped·signal). `GET /api/v1/domains/{host}/result` 가 ETag 조건부 GET 으로 중앙에 내려주는 본체. PG `text`(D40) |
| `discovered` | `discovered` | `int` | INTEGER | | NOT NULL | summary — 이번 스캔 윈도우에서 **트래픽이 관측된 엔드포인트 수**(분류 전 인벤토리). **OPTIONS 제외**(CORS 신호 전용, 과대집계 방지. `DiscoveryJobService` 인벤토리 카운트) |
| `active` | `active` | `int` | INTEGER | | NOT NULL | summary — **스펙 문서화 ∩ 트래픽(S∩D)** = 정상 사용 중인 문서화 API 수 |
| `shadow` | `shadow` | `int` | INTEGER | | NOT NULL | summary — 트래픽엔 있는데 스펙엔 없음(**D−S**) = 그림자 API |
| `zombie` | `zombie` | `int` | INTEGER | | NOT NULL | summary — 스펙상 deprecated 인데 트래픽 지속/버전 추정 좀비 |
| `unused` | `unused` | `int` | INTEGER | | NOT NULL | summary — 스펙엔 있는데 트래픽 없음(**S−D**) = 미사용 API |
| `total_dropped` | `totalDropped` | `int` `@Column(columnDefinition = "integer default 0")` | INTEGER **DEFAULT 0** | | NOT NULL | dropped 3종 합계(non_api+byLimit+nonExistent) 비정규화. scan-status at-a-glance(doc/25 §C) |

> **카운트 의미 주의**: `discovered ≠ active + shadow + zombie + unused`. ① `discovered`=관측 총량(트래픽 있던 엔드포인트), `unused`=스펙에만 있어 트래픽 없는 쪽이라 `discovered` 에 미포함. ② `web_page`(비 API)는 4개 분류 요약에서 제외되나 `discovered` 관측량엔 포함(`ReportBuilder` WebPage skip). ③ `active`=문서∩관측 교집합. 즉 `discovered`=이번에 본 양, 분류 4종=스펙 대조 판정이라 축이 다르다.

> `total_dropped` 는 본 문서에서 **유일하게 SQL `DEFAULT` 절을 갖는 컬럼**이다(`columnDefinition` 명시). §1.3 의 "필드 기본값 ≠ SQL DEFAULT" 일반 규칙의 예외 —
> `ddl-auto: update` 가 기존 테이블에 `ALTER TABLE ... ADD COLUMN total_dropped integer default 0` 으로 추가해 **기존 행을 NULL 없이 0 으로 백필**한다(`int` primitive 안전, doc/25 §C).

### 2.5 `watermark` — 도메인별 증분 수집 watermark

엔티티 `Watermark` (`@Table(name = "watermark")`). 설계 출처 doc/05 §3.1.

| 컬럼 | 필드 | JPA 타입 | SQL 타입 | PK | nullable | 비고 |
|------|------|----------|----------|----|----------|------|
| `host` | `host` | `@Id String` | VARCHAR(255) | ✔ | NOT NULL | 도메인(PK) |
| `last_end` | `lastEnd` | `Instant` | TIMESTAMP(6) | | nullable | 마지막 성공 분석 윈도우 end(=다음 윈도우 start) |

### 2.6 `classification_config` — 전역 분류 설정 (단일 행)

엔티티 `ClassificationConfig` (`@Table(name = "classification_config")`). 설계 출처 doc/10 §1.1.

| 컬럼 | 필드 | JPA 타입 | SQL 타입 | PK | nullable | 비고 |
|------|------|----------|----------|----|----------|------|
| `id` | `id` | `@Id Long` | BIGINT | ✔ | NOT NULL | **고정 PK=1**(`@GeneratedValue` 없음, 앱이 직접 1L 할당) |
| `profile` | `profile` | `@Enumerated(STRING) ClassificationProfile` | VARCHAR(255) | | nullable | 값: `HIGH`/`MIDDLE`/`LOW`/`CUSTOM`. 필드 기본값 `MIDDLE` |
| `threshold_override` | `thresholdOverride` | `Double` | DOUBLE / double precision | | nullable | 모든 프로파일에서 임계 override 가능(doc/10 §3) |
| `custom_weights_json` | `customWeightsJson` | `@Column(columnDefinition="text") String` | text | | nullable | `Map<String,Double>` 직렬화(CUSTOM 한정 가중치 override). PG `text`(D40) |
| `matcher_json` | `matcherJson` | `@Column(columnDefinition="text") String` | text | | nullable | `MatcherConfig`(전역) 직렬화. PG `text`(D40) |
| `updated_at` | `updatedAt` | `Instant` | TIMESTAMP(6) | | nullable | |

**단일 행 보장**: PK 를 `1L` 로 고정하고 resolver/seeder 가 `findById(1L)` upsert 로 단 한 행만 유지한다. DB 레벨 CHECK 제약은
H2/PG 이식성이 떨어져 채택하지 않았다(엔티티 주석·doc/10 §1.1). `ClassificationConfigSeeder`(CommandLineRunner)가 기동 시 행이
없으면 `MIDDLE`/override 없음 기본값으로 1회 idempotent 삽입한다(doc/10 §7). 행 부재도 resolver 가 무회귀로 처리하므로 seed 는 선택.

### 2.7 `domain_classification_config` — 도메인별 분류 설정 override

엔티티 `DomainClassificationConfig` (`@Table(name = "domain_classification_config")`). 설계 출처 doc/10 §1.2.
`DomainConfig` 확장이 아니라 별도 테이블(관심사 분리·희소 행 — 행 부재 = "전역 사용").

| 컬럼 | 필드 | JPA 타입 | SQL 타입 | PK | nullable | 비고 |
|------|------|----------|----------|----|----------|------|
| `host` | `host` | `@Id String` | VARCHAR(255) | ✔ | NOT NULL | `DomainConfig.host` 와 1:1(PK), 논리 FK |
| `profile` | `profile` | `@Enumerated(STRING) ClassificationProfile` | VARCHAR(255) | | nullable | null=전역 프로파일 상속. 값: `HIGH`/`MIDDLE`/`LOW`/`CUSTOM` |
| `threshold_override` | `thresholdOverride` | `Double` | DOUBLE / double precision | | nullable | null=전역/preset 임계 상속 |
| `custom_weights_json` | `customWeightsJson` | `@Column(columnDefinition="text") String` | text | | nullable | `Map<String,Double>`(CUSTOM 한정). PG `text`(D40) |
| `matcher_json` | `matcherJson` | `@Column(columnDefinition="text") String` | text | | nullable | `MatcherConfig`(도메인 override, `includeWebForms` nullable). PG `text`(D40) |
| `updated_at` | `updatedAt` | `Instant` | TIMESTAMP(6) | | nullable | |

### 2.8 `discovered_endpoint` — 검출 SoT(누적 검출 인벤토리 + recency)

엔티티 `DiscoveredEndpointRecord` (`@Table(name = "discovered_endpoint")`). 설계 출처 doc/26 §2 (멀티스펙 통합, DECISIONS D35·D36).
도메인별 누적 검출 endpoint 카탈로그(검출 단일 진실원)이며, 과거 `endpoint_history` 의 recency(firstSeen/lastSeen)를 흡수했다(아래 흡수 주석).

| 컬럼 | 필드 | JPA 타입 | SQL 타입 | PK | nullable | 비고 |
|------|------|----------|----------|----|----------|------|
| `id` | `id` | `@Id @GeneratedValue(IDENTITY) Long` | BIGINT (auto-increment / IDENTITY) | ✔ | NOT NULL | DB 자동 채번(`spec_record` 스타일 일치) |
| `host` | `host` | `String` | VARCHAR(255) | | nullable | 도메인 조회 키(**indexed**). 논리 FK → `domain_config.host` |
| `method` | `method` | `String` | VARCHAR(255) | | nullable | unique 키 일부 |
| `path_template` | `pathTemplate` | `@Column(columnDefinition="text") String` | text | | nullable | unique 키 일부. **임의 길이 경로** → text(varchar(255) 오버플로 회피, 실배포 발견·D40) |
| `template_source` | `templateSource` | `String` | VARCHAR(255) | | nullable | `SPEC`/`INFERRED` (doc/01 `TemplateSource`, `@Enumerated` 아닌 plain String) |
| `endpoint_kind` | `endpointKind` | `String` | VARCHAR(255) | | nullable | `WEB_PAGE`/`STATIC`/`API_CANDIDATE`/`UNKNOWN` (doc/04 `EndpointKind`, plain String) |
| `kind_confidence` | `kindConfidence` | `double` | DOUBLE / double precision | | NOT NULL | endpoint_kind 신뢰도 |
| `version` | `version` | `String` | VARCHAR(255) | | nullable | 버전 태그. path `^v\d+$` 또는 매칭 spec.version 도출, 없으면 null. **indexed (host,version)** (doc/26 §4) |
| `first_seen` | `firstSeen` | `Instant` | TIMESTAMP(6) | | nullable | 누적 recency = min(기존,현재). severity entrenchment 입력(doc/24, EndpointHistory 흡수) |
| `last_seen` | `lastSeen` | `Instant` | TIMESTAMP(6) | | nullable | 누적 recency = max. retention prune 기준 |
| `last_scan_at` | `lastScanAt` | `Instant` | TIMESTAMP(6) | | nullable | 최신 스캔 윈도우 끝(데이터 ts, `now()` 미사용) |
| `hits` | `hits` | `long` | BIGINT | | NOT NULL | 최신 윈도우 스냅샷 |
| `status_dist_json` | `statusDistJson` | `@Column(columnDefinition="text") String` | text | | nullable | 최신 윈도우 status 분포 JSON 스냅샷. PG `text`(D40) |
| `had_query` | `hadQuery` | `boolean` | BOOLEAN | | NOT NULL | ApiScorer 신호 스냅샷 |
| `non_browser_ua` | `nonBrowserUa` | `boolean` | BOOLEAN | | NOT NULL | ApiScorer 신호 스냅샷 |
| `params_json` | `paramsJson` | `@Column(columnDefinition="text") String` | text | | nullable | `ParamCandidates`(doc/13) 스냅샷. PG `text`(D40) |

**제약/인덱스** (본 문서에서 **유일하게 `@UniqueConstraint` + 복합 `@Index` 를 선언하는 테이블**. 단일 컬럼 `@Index` 는 `domain_config`(`next_scan_due_at`)에도 있음 — §2.1. 그 외 테이블은 PK 또는 `@ElementCollection` 기본 매핑만).

- PK: `id`(합성, IDENTITY). 자연키 대신 합성 키 — `spec_record` 와 동일 스타일.
- **UNIQUE(`host`, `method`, `path_template`)** — 누적 upsert 키. `@UniqueConstraint(columnNames={"host","method","path_template"})`.
- **INDEX(`host`)**, **INDEX(`host`, `version`)** — host 카탈로그 조회·host 내 버전 그룹 조회.

기타.

- **누적 upsert(스캔마다)**: `firstSeen=min`·`lastSeen=max`·`lastScanAt`=window end·스냅샷 메트릭(hits/status/params) 최신 윈도우값·version 재계산(doc/26 §2).
- **카디널리티 가드**: 인벤토리 cap(host당 template 5000, doc/13) upsert 전 적용 + **retention prune**(`lastSeen < now−180d` 삭제, `deleteByHostAndLastSeenBefore`). 검출은 Shadow/inferred 포함이라 cap+prune 필수(스캐너 noise 누적 방지).
- **메트릭 분담(린)**: 카탈로그=identity+kind+version+recency+기본 활동(hits/status/params). p50/p95·distinctClients 등 **분석 상세는 `scan_result.report_json`(per-scan) 유지**(카탈로그 비대화 방지, doc/26 §2).
- **EndpointHistory 흡수(D36)**: 과거 §2.8 `endpoint_history`(doc/24 D33, `historyJson` Map)는 본 테이블로 흡수되어 **엔티티/테이블 제거**됨. `(host, specKey, firstSeen, lastSeen)` → `(host, method, path_template, first_seen, last_seen)`. severity recency 는 매칭 template 의 `first_seen` 조회. `model/EndpointObservation` 도 함께 제거. 권장 마이그레이션=재구축(firstSeen 신규 누적, 콜드스타트=현행 severity 무회귀, doc/26 §8). 단 `ddl-auto: update` 는 테이블을 자동 DROP 하지 않으므로(§1.1) 잔존 `endpoint_history` 물리 테이블은 운영에서 수동 제거.
- **`ddl-auto: update` 로 신규 생성** — 기존 테이블/데이터 무영향(doc/26 §8).
- 리포지토리: `DiscoveredEndpointRepository extends JpaRepository<DiscoveredEndpointRecord, Long>` — `findByHost`·`findByHostAndVersion`·`findByHostAndMethodAndPathTemplate`(upsert)·`deleteByHostAndLastSeenBefore`(prune).

---

## 3. 테이블 관계

엔티티 간 JPA 연관(`@ManyToOne`/`@OneToOne`/`@JoinColumn`)은 **하나도 선언되어 있지 않다.** 따라서 엔티티들끼리의 관계는 모두
`host` 문자열을 공유하는 **논리 FK(애플리케이션 레벨)** 이며, DB 외래키 제약으로 강제되지 않는다.

### 3.1 실 FK (DB 제약으로 생성됨)

| 자식 | 부모 | 제약 | 근거 |
|------|------|------|------|
| `domain_hostnames.host` | `domain_config.host` | 실 FOREIGN KEY | `@ElementCollection` + `@CollectionTable` 기본 동작으로 컬렉션 테이블이 소유 엔티티 테이블을 FK 참조 |

### 3.2 논리 FK (앱 레벨, DB 제약 없음)

`host` 를 허브로 한 방사형 구조다. 모두 `domain_config.host` 를 논리적으로 가리키지만 DB FK 는 없다.

```
                         classification_config (전역 단일 행, host 무관)
                                   │ (전역→도메인 상속, 앱 레벨 병합)
                                   ▼
   domain_config (host, PK)  ◄── 논리 FK(host) ──┐
        ▲   ▲   ▲   ▲   ▲                        │
        │   │   │   │   │                        │
spec_record │   │   │   domain_classification_config(host, PK·1:1)
 (host 컬럼)│   │   discovered_endpoint(host idx, 1:N)
            │   watermark(host, PK)
            scan_result(host, PK)
```

| 관계 | 카디널리티 | 키 | 종류 |
|------|-----------|----|------|
| `domain_config` ↔ `spec_record` | 1 : N (도메인당 다중 버전) | `host` | 논리 FK |
| `domain_config` ↔ `scan_result` | 1 : 1 (도메인당 최신 1건) | `host`(양쪽 PK) | 논리 FK |
| `domain_config` ↔ `watermark` | 1 : 1 | `host`(양쪽 PK) | 논리 FK |
| `domain_config` ↔ `domain_classification_config` | 1 : 1 (희소) | `host`(양쪽 PK) | 논리 FK |
| `domain_config` ↔ `discovered_endpoint` | 1 : N (host당 검출 endpoint 행 다수) | `host`(자식 indexed, 부모 PK) | 논리 FK |
| `classification_config` | 전역 단일 행(싱글톤) | 고정 PK=1 | 도메인 무관, 전역 기본값. 도메인 override 와 앱 레벨 병합(doc/10 §3) |

> 역방향 조회: 한 엣지 서버(hostname)가 서빙하는 도메인은 `DomainConfigRepository.findByHostname` 이 `domain_hostnames`
> 자식 테이블을 조인해 찾는다(doc/05 §2.3).

---

## 4. 설계문서 교차참조

각 테이블이 어느 설계문서에서 유래했는지 한눈에 본다.

| 테이블 | 엔티티 | 설계 출처 |
|--------|--------|----------|
| `domain_config` | `DomainConfig` | doc/07 §3.1, doc/05 §2.3(hostnames), `spec_merge_strategy`=doc/26 §5·D35, `base_path_strip`=doc/27·D38, `discovered_at`/`last_seen_at`=doc/30 §5, `last_scan_attempt_at`/`next_scan_due_at`=doc/33·D48 |
| `domain_hostnames` | `DomainConfig.hostnames` (`@ElementCollection`) | doc/05 §2.3 |
| `spec_record` | `SpecRecord` | doc/03 §7.3, `warnings_json`=doc/25 §A.2·D34, `spec_name`=doc/26 §3·D35 |
| `scan_result` | `ScanResult` | doc/07 §3.2·§3.3, `reportJson`=doc/01 §4·doc/12, `total_dropped`=doc/25 §C·D34 |
| `watermark` | `Watermark` | doc/05 §3.1 |
| `classification_config` | `ClassificationConfig` | doc/10 §1.1 |
| `domain_classification_config` | `DomainClassificationConfig` | doc/10 §1.2 |
| `discovered_endpoint` | `DiscoveredEndpointRecord` | doc/26 §2·§4·§7, DECISIONS D35·D36 (구 `endpoint_history`/doc/24 D33 흡수·제거) |
| (전반 영속 컨벤션) | — | doc/06(스택), doc/10 §2·§4, DECISIONS D11·D17 |

---

## 5. 확인 필요 항목 (실검증 대기)

엔티티 애너테이션만으로 확정할 수 없어 실 DB 생성 DDL 확인이 필요한 항목이다.

- ✅ **검증 완료(PR #20/D40, doc/28)** — 9개 JSON 컬럼(`canonical_json`/`report_json`/`custom_weights_json`/`matcher_json`/`warnings_json`/`status_dist_json`/`params_json`):
  실 PostgreSQL(Testcontainers) 실측에서 초기 `@Lob String` 이 `oid` 로 매핑돼 실결함(auto-commit LOB)이 확인됐고, `@Column(columnDefinition = "text")` 로 전환해 **PG 타입 `text` 확정**(`information_schema.data_type='text'` 단언 통과). TASKS 의 "@Lob String JSON 컬럼 PostgreSQL TEXT 매핑 실검증" 항목 Done.
- ✅ **검증 완료(PR #20/D40)** — `@Lob byte[]`(`spec_record.raw_doc`)의 PG 실제 타입은 **`oid` 확정**(범위 밖, 그대로 유지·round-trip 만 검증).
- `domain_hostnames` 의 PK/UNIQUE 제약 정확한 형태(Hibernate 버전 의존).
- prod(PostgreSQL) 프로파일 yml — 현재 리포에는 H2 `application.yml` 만 존재. PG 접속값은 운영 환경에서 주입되는 전제.

> 검증 방법: `ddl-auto: create` 또는 Hibernate `javax.persistence.schema-generation` 으로 생성 DDL 을 덤프하여 본 표와 대조.
