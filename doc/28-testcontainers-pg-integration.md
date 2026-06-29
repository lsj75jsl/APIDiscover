# Testcontainers(PostgreSQL) 통합 테스트 — @Lob→TEXT 실검증 + JPA/REST/304 e2e

> 브랜치 `feature/testcontainers-pg-integration`. TASKS P2 L52(`@Lob String`→PG TEXT 실검증)+L53(통합 테스트) **묶음 단일 PR**.
> 근거: doc/07 §3(REST·조건부GET)·doc/11 §6(MockMvc e2e 선례)·doc/18(DB 스키마)·doc/26(discovered_endpoint). 근거 결정 **DECISIONS D40**.
> **설계만. 코드는 dev.** dev 항목은 TASKS 부모 아래 subitem(D26).

## 0. 목적 / 범위

운영 DB 는 PostgreSQL 이나 단위테스트 319건은 전부 H2(in-mem)로 돈다 → **H2/PG 방언 차이로 가려진 실매핑·실동작이 미검증**. Docker 부재로 보류했던 2건을 podman 가용 확인 후 진행한다.

- **L52** — `@Lob String` JSON 컬럼이 PG 에서 실제 `text` 로 매핑되는지 **실검증**(엔티티 NOTE 주석의 "@Lob String(H2/PG 이식)" 가정의 경험적 확인). 단순 저장/조회뿐 아니라 **`information_schema` 실 컬럼 타입 단언** 포함.
- **L53** — 실 PG 백엔드로 ① 전체 Spring 컨텍스트 부팅 ② REST 핵심 조회 e2e ③ 조건부 GET 304.

두 항목은 **하나의 통합 테스트 하니스**로 충족(L53 의 304 경로가 `scan_result.report_json` = L52 의 @Lob TEXT 컬럼을 읽음 → 자연 결합). **기본 빌드 무회귀가 최우선 제약**(§4·§8).

## 1. 의존성 추가 (build.gradle.kts)

```kotlin
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:junit-jupiter")
testImplementation("org.testcontainers:postgresql")
```
- **버전 미명시(권장)** — `io.spring.dependency-management` 가 import 하는 spring-boot-dependencies BOM 이 `testcontainers.version` 을 이미 핀(Spring Boot 3.3.5 관리값). 별도 `testcontainers-bom` 플랫폼 선언 불요(중복). 명시 BOM 은 대안일 뿐.
- `spring-boot-testcontainers` = `@ServiceConnection`(§5) 제공(Spring Boot 3.1+). 신규 런타임 의존성 0(전부 testImplementation).

## 2. podman 연결 전략 (D40 — 권장: gradle test 태스크 env 주입)

rootless podman 은 docker CLI·`/var/run/docker.sock` 가 없어 Testcontainers 가 소켓을 **명시**해야 붙는다(검증된 환경: `unix://$XDG_RUNTIME_DIR/podman/podman.sock`).

**권장 — `tasks.withType<Test>` 에서 `DOCKER_HOST` 주입(XDG_RUNTIME_DIR 기반, 가드)**:
```kotlin
// rootless podman 소켓 자동 연결 — uid 하드코딩 없이 XDG_RUNTIME_DIR 파생, 가드.
if (System.getenv("DOCKER_HOST") == null) {
    val xdg = System.getenv("XDG_RUNTIME_DIR")
    if (xdg != null && file("$xdg/podman/podman.sock").exists()) {
        environment("DOCKER_HOST", "unix://$xdg/podman/podman.sock")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")   // §3
    }
}
```
- **근거**: ① **리포 커밋·재현 가능**(per-dev 파일 아님) ② **uid 하드코딩 없음**(`XDG_RUNTIME_DIR` 런타임 파생) ③ **가드** — `DOCKER_HOST` 기설정 시 미개입(CI/실docker override 존중), podman 소켓 부재 시 미개입(실docker 기본 소켓·無docker 환경 무영향) ④ 소스 결합 없음(빌드 스크립트 한정).
- **대안(미채택)**: `~/.testcontainers.properties`(`docker.host=...`) — uid 하드코딩·per-dev 머신 파일이라 리포 메커니즘 부적합(개인 로컬 폴백으론 가능). 테스트 베이스클래스 static initializer 로 `DOCKER_HOST` set — Testcontainers `DockerClientFactory` 로드 전 실행 보장이 깨지기 쉬움(클래스 로딩 순서 위험).
- 매핑 정합: 위 가드가 곧 게이팅(§4)과 일관 — 소켓 있으면 주입→`isDockerAvailable()`=true→실행, 없으면 미주입→false→skip.

## 3. Ryuk(resource reaper) (D40 — 비활성)

- **결정: `TESTCONTAINERS_RYUK_DISABLED=true`**(§2 가드 안에서 함께 주입).
- **근거/트레이드오프**: Ryuk 은 docker 소켓 bind-mount + 별도 reaper 컨테이너로 동작 — **rootless podman 과 호환 마찰**(소켓 override 필요·실패 빈번). 비활성 시 **정상 JVM 종료 정리는 Testcontainers 자체 shutdown hook 이 그대로 수행**(컨테이너 stop). Ryuk 은 *비정상* 종료(강제 kill) 백스톱일 뿐 → 누수는 강제 kill 시에만, **ephemeral CI 러너 + 가끔 `podman container prune`** 로 무해. 단순성·신뢰성 ↑.

## 4. 게이팅 (D40 — 권장: `@Testcontainers(disabledWithoutDocker = true)`)

**무회귀 최우선**: docker/podman 없는 환경의 `./gradlew build` 를 깨면 안 된다. 기존 319 단위테스트는 H2 로 그대로 돌고, PG 통합테스트만 docker 가용 시 **추가**로 돈다.

- **권장 — JUnit5 `@Testcontainers(disabledWithoutDocker = true)`**: 목적 빌트인 플래그. docker/podman 미가용 시 **클래스 전체 자동 skip(disabled)** → 빌드 green. gradle/sourceSet 변경 0, 단일 `./gradlew build`/`test` 유지, 319건 무영향.
- **대안(미채택/후속)**:
  - `DockerClientFactory.instance().isDockerAvailable()` + `assumeTrue` — 동작하나 `@Testcontainers` 컨테이너 자동기동 콜백과 실행순서 신경써야 함. 빌트인 플래그가 더 깔끔.
  - `@Tag("integration")` + gradle 태그 필터 — docker 가용성 자동판단 아님(수동 opt-out). 단, **빠른 로컬 런 분리용으로 병용 가능**(선택·후속): `@Tag` 만 추가해두면 후일 `test`/`integrationTest` 분리 여지. 이번엔 필수 아님.
  - 별도 sourceSet/gradle task — 통합테스트 다수·고비용 시 가치. 현재 1 클래스엔 과함 → 스위트 성장 시 후속.
- **결론**: `disabledWithoutDocker = true` 단독으로 "docker 없으면 auto-skip(green)" 충족. `@Tag("integration")` 병기는 선택.

## 5. 컨테이너 패턴 + ddl-auto (D40)

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {
    @Container
    @ServiceConnection                                   // datasource 자동 배선(Spring Boot 3.1+)
    static PostgreSQLContainer<?> pg =
        new PostgreSQLContainer<>("postgres:16-alpine"); // 로컬 pull 확인됨

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");  // §5 결정
    }

    @MockBean LokiClient lokiClient;                     // 운영 Loki 보호(부팅 시 스케줄러 차단, doc/11 선례)
    // @Autowired MockMvc / JdbcTemplate / repositories ...
}
```
- **`@ServiceConnection`(권장)** — 컨테이너 jdbc url/user/pw 를 datasource 로 자동 배선. 수동 `@DynamicPropertySource`(url/username/password 3줄)보다 린. (수동 방식은 동등 대안.)
- **`static @Container`(싱글톤)** — 컨테이너 1회 기동·컨텍스트 캐시 재사용. 컨테이너·context 곱셈 회피.
- **`@MockBean LokiClient`** — 컨텍스트 부팅 시 `DiscoveryScheduler` 1회 실행이라도 **운영 Loki(192.168.8.100:3200) 실호출 차단**(doc/11 ClassificationControllerTest 확립 패턴). **운영 주의 필수 준수**.
- **ddl-auto = `create-drop`(권장)**:
  - **`validate` 불가** — Flyway/Liquibase 마이그레이션이 없어 검증할 사전 스키마가 없음.
  - **`create-drop` 선택** — ephemeral 컨테이너에 **Hibernate 가 엔티티→PG DDL 을 직접 생성** → 이게 곧 L52 검증 대상(엔티티 `@Lob String`→`text` 매핑을 실제 PG 방언으로 발생시킴). 잔여 없음. (`update` 도 되나 ephemeral 엔 create-drop 이 정석.)
  - **단일 컨텍스트·단일 컨테이너**에 create-drop → 스키마 1회 생성, 클래스 내 메서드 공유. (다중 통합 클래스로 성장 시 공용 base + 싱글톤 컨테이너로 확장 — §10.)

## 6. 검증 항목

### 6.1 `@Lob String` → PG `text` 실검증 (L52) — 핵심

대상 **9개 `@Lob String` 컬럼**(canonical/report/classification + 검출 SoT). Spring 기본 네이밍(camelCase→snake_case):

| 그룹 | 엔티티 | 테이블 | 필드 | 컬럼 |
|---|---|---|---|---|
| classification | `ClassificationConfig` | `classification_config` | `customWeightsJson` | `custom_weights_json` |
| classification | `ClassificationConfig` | `classification_config` | `matcherJson` | `matcher_json` |
| classification | `DomainClassificationConfig` | `domain_classification_config` | `customWeightsJson` | `custom_weights_json` |
| classification | `DomainClassificationConfig` | `domain_classification_config` | `matcherJson` | `matcher_json` |
| report | `ScanResult` | `scan_result` | `reportJson` | `report_json` |
| canonical/spec | `SpecRecord` | `spec_record` | `canonicalJson` | `canonical_json` |
| canonical/spec | `SpecRecord` | `spec_record` | `warningsJson` | `warnings_json` |
| 검출 SoT | `DiscoveredEndpointRecord` | `discovered_endpoint` | `statusDistJson` | `status_dist_json` |
| 검출 SoT | `DiscoveredEndpointRecord` | `discovered_endpoint` | `paramsJson` | `params_json` |

검증 2단:
1. **컬럼 타입 단언** — 주입 `JdbcTemplate`(또는 EntityManager native)로
   `SELECT data_type FROM information_schema.columns WHERE table_name=? AND column_name=?`
   → **`text` 단언**(9컬럼 파라미터화).
2. **round-trip** — 대용량(>예: 수십 KB) JSON 문자열 save→load→equal. DDL 체크만으론 안 잡히는 **LOB 스트리밍/auto-commit 이슈**를 잡는다.

> **기대값 = `text`** (프로젝트 이식성 의도·엔티티 NOTE). 단, **`oid`/`bytea`/길이제한 `varchar` 로 나오면 그것이 실결함**(과거 Hibernate `@Lob String`→PG `oid` 대형객체 매핑 → auto-commit 에러 전례 때문에 이 검증이 존재) → **테스트를 느슨하게 고치지 말고**(현행 버그 고정 금지, D37 원칙) 엔티티 레벨 수정(`@Column(columnDefinition="text")` 또는 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`)으로 해소. 테스트의 가치 = 이 분기를 드러내는 것.

### 6.2 `@Lob byte[]` rawDoc — 별도(범위 명시)

- `SpecRecord.rawDoc`(`@Lob byte[]`, 컬럼 `raw_doc`) 는 **String 과 매핑이 다름** — Hibernate 6/PG 에서 통상 `oid`(대형객체) 매핑(byte[] LOB gotcha). 매니저 지정 범위는 "@Lob **String** JSON" 이라 `text` 단언 대상 아님.
- 권장: **round-trip 1건만**(bytes save→load equal)로 실동작 확인 + **실 타입 기록(정보성, oid/bytea)** + **auto-commit 한계 플래그**(§10). `text` 단언 금지(오기대).

### 6.3 REST e2e (L53) — 1~2 엔드포인트

실 PG 백엔드·전체 컨텍스트(MockMvc)로:
- **`GET /api/v1/domains/{host}/discovery`** — 결합 뷰(검출∪스펙, `CombinedDiscoveryController`→`CombinedDiscoveryService.forHost`). `discovered_endpoint`(@Lob 2컬럼)+`spec_record`(@Lob 2컬럼) 실 PG 적재→조회 경로.
- 시드: `DomainConfig` + `DiscoveredEndpointRecord`/`SpecRecord`(또는 `SpecStore.upload`) 저장 후 200·핵심 필드 단언.

### 6.4 조건부 GET 304 (L53)

`ScanController.result`(`GET /result`, ETag=`"<version>"`):
1. `ScanResult`(reportJson=@Lob TEXT, version) 시드.
2. **1차 GET** → 200 + `ETag` 헤더 캡처(+ body=reportJson, **@Lob TEXT read 경로 동시 검증**).
3. **2차 GET `If-None-Match: <etag>`** → **304** + body 없음.
4. (선택) version 변경 후 재요청 → 200(ETag 갱신) 대조.
> L52(@Lob TEXT read)와 L53(304)이 이 경로에서 결합.

## 7. H2/PG 방언 차이 주의점 (ddl-auto 관련)

- **타입 매핑이 방언별로 다름**이 본 작업의 동기 — H2Dialect 와 PostgreSQLDialect 가 `@Lob String` 을 다르게 낼 수 있어(H2: CLOB/CHAR LARGE OBJECT 류, PG: text 기대) **H2 테스트론 검증 불가**. 그래서 실 PG 필요.
- `@Column(columnDefinition = "integer default 0")`(`ScanResult.totalDropped`) — raw SQL 패스스루. `integer`/`default 0` 은 PG·H2 모두 유효 → create-drop OK. 단 columnDefinition 은 방언 타입매핑을 우회하므로 PG 적용 확인(유효).
- `GenerationType.IDENTITY`(다수) — PG identity/serial 로 정상. `@Enumerated(STRING)`→varchar, `Instant`→timestamp 정상(단 PG `timestamp` vs `timestamptz` UTC 처리 — round-trip 동등 확인, 한계 §10).
- create-drop 은 **단일 컨텍스트** 전제(다중 컨텍스트가 같은 컨테이너 공유 시 스키마 drop 레이스) → §5 단일 클래스/싱글톤 유지.

## 8. 무회귀

- **빌드 green 보장**: `disabledWithoutDocker=true`(§4) → docker/podman 부재 시 클래스 skip. 기존 319 H2 단위테스트 무영향(별도 datasource·gating).
- **운영 영향 0**: 신규 의존성 전부 testImplementation. `@MockBean LokiClient` 로 운영 Loki 실호출 차단. 컨테이너는 로컬 pull 된 `postgres:16-alpine` 사용(외부 네트워크 의존 최소).
- **소스 무변경**: 순수 테스트 추가 + build.gradle.kts 테스트 설정. (단 §6.1 에서 `text` 아닌 실결함 발견 시 엔티티 수정은 **별도 보고 후** 진행 — 테스트로 현행 버그 고정 금지.)

## 9. dev 구현 체크리스트 (TASKS subitem, D26)

- [x] build.gradle.kts — Testcontainers 3종 의존(§1, 버전 미명시) + `tasks.withType<Test>` `DOCKER_HOST`/`RYUK_DISABLED` 가드 주입(§2·§3).
- [x] `PostgresIntegrationTest`(`@SpringBootTest @AutoConfigureMockMvc @Testcontainers(disabledWithoutDocker=true)`, `@Container @ServiceConnection postgres:16-alpine`, `ddl-auto=create-drop`, `@MockBean LokiClient`) — §5.
- [x] (L52) `@Lob String` 9컬럼 `information_schema.data_type='text'` 단언 + 대용량 round-trip(§6.1). **실결함 발견·수정**: 9컬럼 전부 `oid` 매핑(text 아님) → 5엔티티 `@Lob`→`@Column(columnDefinition="text")` 수정(D37 원칙, §6.1·D40 갱신) → text 통과.
- [x] (L52 별도) `raw_doc`(byte[]) round-trip + 실타입 기록(실측 `oid`, text 단언 안 함, §6.2).
- [x] (L53) `GET /discovery` e2e(실 PG, 시드→200·필드, §6.3).
- [x] (L53) `GET /result` 조건부 GET — 1차 200+ETag → 2차 If-None-Match 304(§6.4).
- [x] 회귀 — `./gradlew build`(podman): PG 통합테스트 13건 실행 green(skip 0) / bogus `DOCKER_HOST` 로 auto-skip 확인(클래스 skipped, build green) / 기존 319 무영향(총 332·실패 0·skip 1=LokiLive).

## 10. 범위 밖 / 후속 / 한계

- **CI 파이프라인 docker 제공** — 현 설계는 "가용 시 실행, 부재 시 skip". CI 에서 항상 돌리려면 러너에 docker/podman + `DOCKER_HOST` 제공(P3 운영, 별도).
- **다중 통합 테스트 클래스** — 늘면 공용 `AbstractPgIntegrationTest`(싱글톤 컨테이너 보유) + `@Tag("integration")` 분리 sourceSet/task 로 확장(§4·§5). 현재 1 클래스로 충분.
- **`raw_doc` byte[] LOB auto-commit 한계 — ★실배포 발현·수정(2026-06-29, fix/spec-meta-oid-autocommit)**: "현재 트랜잭션 내 접근이라 무해" 가정이 **틀림**. REST 메타 조회(M2 GET /domains/{host}·M4 GET /scan-status·M6 GET /spec)가 `SpecRecord` **엔티티**를 `@Transactional` 없이(OSIV=false→auto-commit) 로드 → Hibernate 가 `rawDoc` oid 를 materialize → `PSQLException: Large Objects may not be used in auto-commit mode`/`JpaSystemException: Unable to access lob stream` → **500**. prod 는 업로드 스펙 0 이라 잠복, 테스트 spec 업로드 후 메타 GET 으로 발현. **수정 = 메타 조회를 `SpecMetaProjection`(rawDoc 미선택 JPQL 생성자식)으로 전환**(스키마·oid 마이그레이션 없음). 스캔 경로(`@Transactional analyze`)는 트랜잭션 내라 안전(엔티티 유지). 회귀가드=`PostgresIntegrationTest.specMetaEndpointsDoNotMaterializeRawDocOidInAutoCommit`(실 PG, fix 원복 시 위 정확한 oid 에러로 RED 확인). bytea 매핑 정밀화는 후속(마이그레이션 위험).
- **forHost 기반 경로(/discovery·/result M5) 동일 버그 — ★같은 PR 에서 수정**: `CombinedDiscoveryService.forHost` 가 `loadActiveCanonical`/`activeRecords` 로 `SpecRecord` 엔티티(rawDoc oid)를 로드하는데 비-@Transactional·OSIV off → spec 보유 도메인에서 동일 500. 수정 = `forHost` 에 **`@Transactional(readOnly=true)`**(진입점 1곳에 tx → 내부 엔티티 로드 안전, 읽기+분류만이라 readOnly 적합). 공유 스캔 메서드 `loadActiveCanonical` 자체는 미터치(analyze 무영향). 회귀가드=`PostgresIntegrationTest.forHostEndpointsTolerateRawDocOidSpecOnRealPg`(실 PG GET /discovery·/result 200, fix(@Transactional) 임시 제거 시 같은 oid 에러로 RED 확인).
- **M6 projection 정렬 nulls-first(P3 보완)** — `findActiveSpecMetas` ORDER BY `specName asc nulls first`(specName 레거시 null 가능). H2 ASC=NULLS FIRST 가 PG ASC=NULLS LAST 발산을 가리는 [h2-pg-null-ordering-trap](D48 findDueForScan 동형)·기존 인메모리 `Comparator.nullsFirst` 동작 일치. 가드=`specListNullSpecNameOrdersFirstDeterministicallyOnRealPg`(specName=null 행 영속→GET /spec 첫 원소=null 행, nulls first 제거 시 실 PG 에서 순서 발산 RED).
- **PUT /spec 동일 filename 재업로드 oid 500 — ★self-invocation 한계(2026-06-29, fix/spec-reupload-tx-self-invocation)**: `SpecStore.upload` 의 **4-arg core 에만** `@Transactional` 이고 진입 오버로드(2-arg·3-arg filename·3-arg specName)는 비-@Transactional. 컨트롤러가 3-arg(filename) 호출 → core 를 **self-invocation**(같은 빈) 으로 부름 → Spring 프록시 미적용 → `@Transactional` 무력 → 동일 specName 재업로드의 비활성화 루프(`findByHostAndActiveIsTrue`→`prev.setActive` = rawDoc oid 엔티티 로드)가 auto-commit → 500. 첫 업로드(비활성 대상 0)는 통과, **재업로드부터** 발현(테스트 메서드 tx 가 가렸음). **수정 = 진입 오버로드 3개에 `@Transactional`**(컨트롤러 호출이 프록시 경유 → tx 시작 → 내부 self-call core 가 그 tx 안). 회귀가드=`PostgresIntegrationTest.reuploadSameFilenameViaHttpDoesNotHit500`(★MockMvc PUT — 테스트 tx 없이 실 HTTP 경로 모사, 단순 repo.save/서비스 직접호출은 tx 가 가림). fix 제거 시 같은 oid 에러 RED 확인.
- **`Instant`→`timestamp` 시간대** — PG `timestamp`(no tz) round-trip 동등 확인까지. timestamptz 정책은 범위 밖.
- **자동 podman 소켓 탐지 다양성** — 비표준 `XDG_RUNTIME_DIR`·다른 소켓 경로는 `DOCKER_HOST` 수동 지정으로 대응(가드가 존중). rootless 외 구성은 범위 밖.
- **oid→text 기존 PG 마이그레이션 캐비엇** — 현재는 moot(커밋된 PG 스키마·Flyway 없음, 신규 배포는 ddl-auto=update 가 `text` 컬럼 생성). 단 **구 `@Lob String` 으로 이미 생성된 PG**(oid 컬럼)에 이 변경을 배포하면 `ddl-auto=update` 는 기존 컬럼 타입을 변경하지 않아 `oid` 잔존·LOB 결함 잔류 → 수동 `ALTER TABLE ... ALTER COLUMN ... TYPE text USING ...` 필요(9컬럼). 마이그레이션 도구 도입 시 해당 변환을 첫 스크립트에 포함.
