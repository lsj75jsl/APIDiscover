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

이식성을 위해 **벤더 전용 JSON 타입(PostgreSQL JSONB)을 쓰지 않는다.** JSON 데이터는 모두 `@Lob String`(CLOB/TEXT)으로
저장하고 애플리케이션이 Jackson 으로 직렬화/역직렬화한다(근거 doc/10 §2·§4, DECISIONS D17). 이로써 H2/PG 가 동일하게 동작한다.

> 참고: DECISIONS **D11** 은 PostgreSQL 채택 사유로 "JSONB"를 들지만, 실제 스키마는 이식성 때문에 JSONB 컬럼 타입을 쓰지 않는다.
> JSONB 는 향후 활용 여지일 뿐 현재 매핑에는 등장하지 않는다.

사용 중인 JPA 매핑 컨벤션은 다음과 같다.

- `@Lob String` → 큰 JSON 문자열. CLOB/TEXT 로 매핑.
- `@Lob byte[]` → 원본 바이너리(BLOB).
- `@Enumerated(EnumType.STRING)` → enum 을 이름 문자열(VARCHAR)로 저장. 숫자 ordinal 미사용(순서 변경 안전).
- `@ElementCollection` + `@CollectionTable` → 컬렉션 필드를 **자식 테이블로 분리**.
- public 필드(스캐폴딩 단순화, 캡슐화는 후속 TODO — 엔티티 주석 참조).

### 1.3 JPA 타입 → SQL 타입 매핑 규칙

이 문서의 모든 테이블표는 아래 규칙을 따른다(Hibernate 6 / Spring Boot 3.3 기준).

| Java / JPA | H2 SQL | PostgreSQL SQL | nullable 기본 |
|------------|--------|----------------|---------------|
| `String` (length 미지정) | `VARCHAR(255)` | `varchar(255)` | nullable |
| `@Lob String` | `CLOB` | **기본 `oid`(large object) — 함정**, `text` 는 명시 매핑 필요(아래 주석 참조) | nullable |
| `@Lob byte[]` | `BLOB` | `bytea`/`oid` (확인 필요 — PG LOB 매핑 실검증 필요) | nullable |
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

**`@Lob String` 의 PostgreSQL 매핑 함정**: Hibernate 6 는 `@Lob String` 을 CLOB 으로 보고 **PostgreSQL 에서 기본 `oid`(large
object, `pg_largeobject` 사용)** 로 매핑한다 — 흔히 기대하는 `text` 가 아니다. `oid` 는 일반 SELECT 로 본문이 안 보이고
백업/삭제 시 LOB 정리가 별도로 필요해 운영상 불리하다. 회피하려면 컬럼에 `@Column(columnDefinition = "text")` 또는
`@JdbcTypeCode(SqlTypes.LONGVARCHAR)` 를 명시해 `text` 로 강제한다. 현재 엔티티들은 어느 쪽도 지정하지 않았으므로 PG 에서는
`oid` 가 되며, `text` 를 원하면 위 매핑을 추가해야 한다(실검증 §5).

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
| `format` | `format` | `@Enumerated(STRING) SpecFormat` | VARCHAR(255) | | nullable | 값: `OPENAPI` / `POSTMAN` / `CSV` |
| `spec_version` | `specVersion` | `long` | BIGINT | | NOT NULL | 도메인별 증가 버전 |
| `raw_doc` | `rawDoc` | `@Lob byte[]` | BLOB | | nullable | 원본 문서(감사/재파싱용) |
| `canonical_json` | `canonicalJson` | `@Lob String` | CLOB/TEXT | | nullable | Canonical 엔드포인트 집합 JSON(매칭의 진실원) |
| `warnings_json` | `warningsJson` | `@Lob String` | CLOB/TEXT | | nullable | 파싱 recoverable 경고 `List<String>` 직렬화. 스캔이 `specSource.warnings` 로 로드(doc/25 §A.2) |
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
| `report_json` | `reportJson` | `@Lob String` | CLOB/TEXT | | nullable | 전체 `DiscoveryReport` JSON(doc/01 §4, doc/12) |
| `discovered` | `discovered` | `int` | INTEGER | | NOT NULL | summary |
| `active` | `active` | `int` | INTEGER | | NOT NULL | summary |
| `shadow` | `shadow` | `int` | INTEGER | | NOT NULL | summary |
| `zombie` | `zombie` | `int` | INTEGER | | NOT NULL | summary |
| `unused` | `unused` | `int` | INTEGER | | NOT NULL | summary |
| `total_dropped` | `totalDropped` | `int` `@Column(columnDefinition = "integer default 0")` | INTEGER **DEFAULT 0** | | NOT NULL | dropped 3종 합계(non_api+byLimit+nonExistent) 비정규화. scan-status at-a-glance(doc/25 §C) |

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
| `custom_weights_json` | `customWeightsJson` | `@Lob String` | CLOB/TEXT | | nullable | `Map<String,Double>` 직렬화(CUSTOM 한정 가중치 override) |
| `matcher_json` | `matcherJson` | `@Lob String` | CLOB/TEXT | | nullable | `MatcherConfig`(전역) 직렬화 |
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
| `custom_weights_json` | `customWeightsJson` | `@Lob String` | CLOB/TEXT | | nullable | `Map<String,Double>`(CUSTOM 한정) |
| `matcher_json` | `matcherJson` | `@Lob String` | CLOB/TEXT | | nullable | `MatcherConfig`(도메인 override, `includeWebForms` nullable) |
| `updated_at` | `updatedAt` | `Instant` | TIMESTAMP(6) | | nullable | |

### 2.8 `endpoint_history` — 도메인별 spec endpoint 누적 관측 이력

엔티티 `EndpointHistory` (`@Table(name = "endpoint_history")`). 설계 출처 doc/24 §3 (cross-scan recency Zombie severity 보강, DECISIONS D33).

| 컬럼 | 필드 | JPA 타입 | SQL 타입 | PK | nullable | 비고 |
|------|------|----------|----------|----|----------|------|
| `host` | `host` | `@Id String` | VARCHAR(255) | ✔ | NOT NULL | 도메인(PK). 도메인당 1행, in-place 갱신 |
| `history_json` | `historyJson` | `@Lob String` | CLOB/TEXT | | nullable | `Map<specKey, EndpointObservation>` JSON. specKey=`"METHOD|host|template"`(host=null→`*`) |
| `updated_at` | `updatedAt` | `Instant` | TIMESTAMP(6) | | nullable | |

- `EndpointObservation`(`model/EndpointObservation.java`)은 `record(Instant firstSeen, Instant lastSeen)` 로, **테이블이 아니라** `history_json` Map 의 값으로 직렬화되는 단위다(Jackson 왕복). 별도 컬럼·테이블 없음.
- **per-host 1행 + spec 매칭 endpoint 만 기록(spec-bound)**: Zombie/severity 가 spec endpoint 한정이라 spec 크기로 자연 bound(수십~수백). Shadow/inferred·스캐너 noise 는 미기록해 무한 누적을 막는다(doc/24 §3).
- **`@Lob String` JSON 채택**(정규화 테이블 아님): JSONB 미사용·H2/PG 이식 컨벤션(§1.2, doc/10 §2·D17)·per-host 접근 패턴(`ScanResult.findById` 와 동일). 정규화(엔드포인트당 행)는 규모 확대 시 대안이다(doc/24 §6).
- **`ddl-auto: update` 로 신규 생성** — 기존 6엔티티/7테이블 및 데이터에 무영향. 콜드스타트(행 없음)=빈 이력=현행 동작(doc/24 §4).
- 리포지토리: `EndpointHistoryRepository extends JpaRepository<EndpointHistory, String>`.

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
 (host 컬럼)│   │   endpoint_history(host, PK·1:1)
            │   watermark(host, PK)
            scan_result(host, PK)
```

| 관계 | 카디널리티 | 키 | 종류 |
|------|-----------|----|------|
| `domain_config` ↔ `spec_record` | 1 : N (도메인당 다중 버전) | `host` | 논리 FK |
| `domain_config` ↔ `scan_result` | 1 : 1 (도메인당 최신 1건) | `host`(양쪽 PK) | 논리 FK |
| `domain_config` ↔ `watermark` | 1 : 1 | `host`(양쪽 PK) | 논리 FK |
| `domain_config` ↔ `domain_classification_config` | 1 : 1 (희소) | `host`(양쪽 PK) | 논리 FK |
| `domain_config` ↔ `endpoint_history` | 1 : 1 (spec-bound, 도메인당 1행) | `host`(양쪽 PK) | 논리 FK |
| `classification_config` | 전역 단일 행(싱글톤) | 고정 PK=1 | 도메인 무관, 전역 기본값. 도메인 override 와 앱 레벨 병합(doc/10 §3) |

> 역방향 조회: 한 엣지 서버(hostname)가 서빙하는 도메인은 `DomainConfigRepository.findByHostname` 이 `domain_hostnames`
> 자식 테이블을 조인해 찾는다(doc/05 §2.3).

---

## 4. 설계문서 교차참조

각 테이블이 어느 설계문서에서 유래했는지 한눈에 본다.

| 테이블 | 엔티티 | 설계 출처 |
|--------|--------|----------|
| `domain_config` | `DomainConfig` | doc/07 §3.1, doc/05 §2.3(hostnames) |
| `domain_hostnames` | `DomainConfig.hostnames` (`@ElementCollection`) | doc/05 §2.3 |
| `spec_record` | `SpecRecord` | doc/03 §7.3, `warnings_json`=doc/25 §A.2·D34 |
| `scan_result` | `ScanResult` | doc/07 §3.2·§3.3, `reportJson`=doc/01 §4·doc/12, `total_dropped`=doc/25 §C·D34 |
| `watermark` | `Watermark` | doc/05 §3.1 |
| `classification_config` | `ClassificationConfig` | doc/10 §1.1 |
| `domain_classification_config` | `DomainClassificationConfig` | doc/10 §1.2 |
| `endpoint_history` | `EndpointHistory` (값=`model/EndpointObservation`) | doc/24 §3, DECISIONS D33 |
| (전반 영속 컨벤션) | — | doc/06(스택), doc/10 §2·§4, DECISIONS D11·D17 |

---

## 5. 확인 필요 항목 (실검증 대기)

엔티티 애너테이션만으로 확정할 수 없어 실 DB 생성 DDL 확인이 필요한 항목이다.

- `@Lob String`(`canonicalJson`/`reportJson`/`customWeightsJson`/`matcherJson`)의 PostgreSQL 실제 타입 — 명시 매핑이 없으면
  기본 `oid`(§1.3 함정)이며 `text` 가 아니다. TASKS 의 "@Lob String JSON 컬럼 PostgreSQL TEXT 매핑 실검증" 미완 항목과 동일.
- `@Lob byte[]`(`spec_record.raw_doc`)의 PostgreSQL 실제 타입(`bytea` vs 대용량 객체 `oid`).
- `domain_hostnames` 의 PK/UNIQUE 제약 정확한 형태(Hibernate 버전 의존).
- prod(PostgreSQL) 프로파일 yml — 현재 리포에는 H2 `application.yml` 만 존재. PG 접속값은 운영 환경에서 주입되는 전제.

> 검증 방법: `ddl-auto: create` 또는 Hibernate `javax.persistence.schema-generation` 으로 생성 DDL 을 덤프하여 본 표와 대조.
