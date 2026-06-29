# doc/37 — 영속 API 인벤토리 + 업로드 reconcile (M7 재설계)

> **supersede doc/36**(M7a 문서버전 diff 모델 폐기). 근거 결정 **DECISIONS D53**(D52 supersede).
> 근거 코드 확인(정적·운영 Loki 미호출): `SpecStore`·`SpecRecord`·`CanonicalEndpoint`·3 파서·`Classifier`/`VersionZombieInference`·`DiscoveredEndpointRecord`·`SpecDiffService`(롤백 대상)·영속 규약(ddl-auto=update·Flyway 없음).
> ★설계만 — 구현 금지(롤백+재구현은 dev, 매니저가 사용자 확인 후 착수).

## 0. 배경 — 의도 불일치와 재설계 확정

현 구현 M7a(PR #44/#45, doc/36)는 **compute-on-read 문서버전 diff** 다. 같은 specName 의 현 active `spec_record` vs 직전 inactive `spec_record` 의 `canonicalJson` 을 **조회 시점 비교**한다(`SpecDiffService`·`GET /spec/changes`). **영속 저장 0·파라미터 저장 0**(`CanonicalEndpoint` 에 param 필드 없음)·**UPDATED=deprecated/version 만**. 사용자 의도와 근본적으로 다르다 → **롤백 + 영속 인벤토리 모델로 재구현 확정**.

**사용자 확정 의도(그대로)**.
> 시간순.
> 1. 문서 A 업로드.
> 2. 문서 A 파싱 → DB 에 해당 도메인의 사용자 등록 API 목록과 **API 파라미터** 관리.
> 3. 문서 B 추가 업로드.
> 4. 문서 B 파싱 → 이미 등록된 API 인지 확인.
>    4-1. 등록돼 있으면 **파라미터 변경 여부 확인 후 변경 시 업데이트**.
>    4-2. 신규면 API 목록·파라미터 **추가**.
>    4-3. DB 엔 있는데 신규 문서에 없으면 **삭제된 API 로 관리**.
> 문서 자체 diff·history 관리 불필요.

**삭제 판정 범위(4-3, 사용자 확정) = 문서(specName)별 추적**. 각 문서가 자기 API 집합을 소유한다. **다른 문서(B) 업로드는 A 의 API 를 절대 삭제하지 않는다(union 병합)**. 삭제는 **같은 문서(specName)의 새 버전 재업로드** 시 그 문서에서 빠진 API 에만 적용한다. 도메인 API 인벤토리 = 문서별 집합의 union.

핵심 전환 — M7a 는 상태를 **버전 이력에서 재계산**(직전 inactive canonicalJson)했다. 재설계는 상태를 **영속 인벤토리에 보유**하고 업로드마다 **reconcile** 한다. 이력 비교가 아니라 현재 상태 속성이다(사용자 "history 불필요" 와 정합).

## 1. 영속 API 인벤토리 데이터 모델 (신규 테이블)

- **엔티티 `DocumentedApiRecord` / 테이블 `documented_api`**. (대안 명칭 `domain_api`·`tracked_api` 도 가능 — dev 확정. 본 문서는 `documented_api` 로 통일. "documented" 는 `discovered_endpoint`[관측]·`spec_record`[원본]과 대비되는 축이고 Zombie 의미[문서화→제거]와 직결.)
- **자연 식별 키 = (host, specName, method, path_template)** — `@UniqueConstraint`. PK 는 관례대로 `@GeneratedValue(IDENTITY) Long id`.
- **status = 현재 상태 속성**(직전 reconcile 결과), **history 아님**(사용자 명시). enum `{ACTIVE, DELETED}` 소프트마크(하드삭제 아님 — "삭제된 API 로 관리"). 최근 변경은 `lastChange {ADDED, UPDATED, UNCHANGED}` 로 보유.

| 컬럼(필드) | 타입 | 의미 |
|---|---|---|
| `id` | `Long`(IDENTITY) | PK |
| `host` | String | 도메인 |
| `specName` | String | 문서 식별(filename 도출, 미전달=`default`) |
| `method` | String | HTTP 메서드(대문자) |
| `pathTemplate` | `@Column(columnDefinition="text")` | 경로 템플릿(`/users/{id}`) |
| `paramsJson` | `@Column(columnDefinition="text")` | 구조화 파라미터 직렬화(`List<SpecParam>`·§2). **@Lob 금지(oid 함정)** |
| `status` | `@Enumerated(STRING)` `ApiStatus` | `ACTIVE` \| `DELETED` |
| `lastChange` | `@Enumerated(STRING)` `ApiChangeKind` | `ADDED` \| `UPDATED` \| `UNCHANGED`(직전 reconcile 결과) |
| `deprecated` | boolean | 스펙 deprecated 표기(Zombie 입력, §6) |
| `version` | String | 스펙 version(nullable) |
| `sourceSpecVersion` | long | 이 행을 마지막으로 갱신한 `spec_record.specVersion` |
| `firstDocumentedAt` | Instant | 이 문서에 처음 등장(추가)한 시각 |
| `lastDocumentedAt` | Instant | 이 문서의 마지막 업로드에서 **문서에 존재**한 시각 |
| `changedAt` | Instant | status/param 마지막 변경 시각 |

- **시각 의미 주의(혼동 금지)**. `firstDocumentedAt`/`lastDocumentedAt` 은 **스펙 문서에 존재한** 시각이지 **트래픽** 이 아니다. 트래픽 firstSeen/lastSeen 은 `discovered_endpoint` 에 있다(§6 Zombie 결합에서 교차).
- **DDL / ddl-auto=update 무손실**. Flyway/Liquibase 없음 — 스키마는 ddl-auto=update 단독. **신규 테이블 = ADD TABLE**(기존 테이블·데이터 영향 0·무손실). 컬럼명은 명시 `@Column(name=)` 없이 Hibernate 기본 camelCase→snake_case(예 `spec_name`·`path_template`·`source_spec_version`). 인덱스 권장 — `host` / `host,spec_name`(문서별 조회) / `host,status`(★Zombie: host 의 DELETED 집합 조회, §6). 동시 갱신 대비 `@DynamicUpdate`(reconcile 의 부분 UPDATE).

```java
@Entity
@DynamicUpdate
@Table(name = "documented_api",
    uniqueConstraints = @UniqueConstraint(columnNames = {"host", "spec_name", "method", "path_template"}),
    indexes = { @Index(columnList = "host"),
                @Index(columnList = "host,spec_name"),
                @Index(columnList = "host,status") })
public class DocumentedApiRecord { /* 위 필드 + 접근자(엔티티 캡슐화 D41) */ }
```

## 2. 파라미터 추출·표현

- **표준 표현(신규 record)**.

```java
public record SpecParam(String name, ParamIn in, boolean required, String type) {}
public enum ParamIn { QUERY, PATH, HEADER, COOKIE, BODY }
```
  - `type` = **스키마 요약 문자열**(`string`·`integer`·`array<string>`·`object` 등). 전체 JSON Schema 보유는 범위 밖(과설계 방지·M7b 핵심 비용 억제). body 는 `in=BODY`·`name`=프로퍼티 경로 또는 `body`·`type`=요약.
- **`CanonicalEndpoint` 에 `List<SpecParam> params` 가산**(현 `method·pathTemplate·host·deprecated·version·sourceRef` + `params`). 매칭/dedupe 키는 여전히 `method+host+pathTemplate` → **EndpointMatcher·loadActiveCanonical·merge 매칭 거동 불변(무회귀)**. 파서가 단일 모델을 산출하고, 인벤토리 reconcile 은 파싱된 `CanonicalEndpoint.params()` 를 읽는다.
  - **구 canonicalJson 역직렬화 안전**. 기존 inactive/active `spec_record.canonicalJson` 은 params 필드가 없다 → `CanonicalEndpoint` compact 생성자에서 **params null→빈 리스트** 기본값 보장(누락 필드 = 빈 params). 스캔 경로는 P1 에서 params 미사용이라 무영향.
- **3 파서 추출(현재 전부 미추출 — 확인)**.

| 파서 | 라이브러리 | 추출 지점(현재 미사용 → M7b 도입) |
|---|---|---|
| `OpenApiSpecParser` | swagger-parser v3 | `Operation.getParameters()`(query/path/header/cookie·`required`·`schema.type`) + `Operation.getRequestBody()`(content schema 요약→BODY) |
| `PostmanSpecParser` | Jackson JsonNode | `request.url.query[]`(현재 `pathFromRaw` 가 query strip — 추출로 전환)·`url.path` 변수 segment·`request.body`(raw/urlencoded/formdata 키) |
| `CsvSpecParser` | univocity | 컬럼 규약 확장(예 `params` 컬럼에 `name:in:required:type` 세미콜론 구분, 또는 별도 보조 컬럼). 하위호환=컬럼 없으면 빈 params |

- **"파라미터 변경" 판정**(UPDATED 의 핵심) = 두 `Set<SpecParam>` 의 차이. **추가·제거·`required` flip·`type` 변경** 중 하나라도 있으면 변경. 비교는 (name, in) 키로 매칭 후 (required, type) 비교 + 집합 대칭차. 결정적(정렬·정규화).
- ★**path param 변경 주의**. `/users/{id}`→`/users/{userId}` 는 **다른 pathTemplate = 다른 식별 키 = ADDED+DELETED**(UPDATED 아님). query/header/cookie/body param 변경이 UPDATED 의 본질(사용자 "파라미터 변경" 의 주 대상).

## 3. reconcile-on-upload 로직

업로드 파싱 후 **specName 단위**로 인벤토리를 reconcile 한다(`upload` 의 기존 `@Transactional` 경계 안, in-memory 파싱 결과 사용).

```
reconcile(host, specName, parsed: List<CanonicalEndpoint>, newSpecVersion, now):
  existing = documentedApiRepo.findByHostAndSpecName(host, specName)   // 인벤토리 현재 상태
  parsedByKey = index parsed by (method, pathTemplate)
  for p in parsed:
    row = existing[(p.method, p.pathTemplate)]
    if row == null:                       # 4-2 신규
       INSERT(status=ACTIVE, lastChange=ADDED, params=p.params, deprecated, version,
              sourceSpecVersion=newSpecVersion, firstDocumentedAt=now, lastDocumentedAt=now, changedAt=now)
    else:                                 # 이미 등록
       if row.status == DELETED:          # 재등장(resurrect) → 추가로 취급
          row ← status=ACTIVE, lastChange=ADDED, params=p.params, changedAt=now
       elif paramsChanged(row.params, p.params) or row.deprecated != p.deprecated or row.version != p.version:  # 4-1 변경
          row ← lastChange=UPDATED, params=p.params, deprecated, version, changedAt=now
       else:
          row ← lastChange=UNCHANGED      # 변경 없음
       row.status=ACTIVE; row.sourceSpecVersion=newSpecVersion; row.lastDocumentedAt=now
  for row in existing where status==ACTIVE and key not in parsedByKey:   # 4-3 삭제
       row ← status=DELETED, changedAt=now   # 그 specName 한정. 다른 specName 불변(union)
```

- **트랜잭션·oid 안전**. reconcile 은 `upload` core(이미 `@Transactional`) **내부 인라인** 또는 주입 빈 호출(self-invocation 회피, D51). **`SpecRecord` 엔티티/rawDoc 미접근**(파싱 결과는 메모리, 인벤토리는 `documented_api` repo[text paramsJson]) → **oid materialize 원천 없음**(§7 과 결합 시 oid 함정 클래스 자체 소멸).
- **삭제 격리(사용자 4-3 핵심)**. 삭제 마킹은 `WHERE host=? AND spec_name=?` 범위로 **그 문서에만**. 문서 B 업로드 시 reconcile(host, "B", …) 는 specName="A" 행을 **건드리지 않는다** → A 의 API 는 절대 삭제 안 됨(union). §9 시나리오 ④ 회귀가드.
- **모드(specMergeStrategy) 상호작용**. 사용자의 union+문서별-삭제 모델은 **MERGE(기본)·VERSION_GROUPED** 에 자연 매핑. reconcile 은 **모드 무관하게 (host, specName) 단위**로 동작(모드는 `spec_record.active`[스캔 경로]에만 영향, 인벤토리 키잉 불변). SEPARATE(host 전체 단일스펙 교체)는 union 개념이 약함 — SEPARATE 다문서는 비일반 조합으로 명시(dev 가 SEPARATE 에서 타 specName 행 처리[보존 권장]를 결정).

## 4. 노출 인터페이스

- **신규 `GET /api/v1/domains/{host}/apis`**(인벤토리 목록). 필터 `?specName=`·`?status=ACTIVE|DELETED`·`?method=`. host 정규화 null→400. **oid 무접근**(documented_api projection/엔티티는 oid 없음).
- **`GET /spec/changes` 제거**(M7a 모델 폐기 — §5 롤백).
- 응답 샘플.

```jsonc
{
  "host": "api.example.com",
  "apis": [
    { "specName": "users", "method": "POST", "pathTemplate": "/v2/users",
      "status": "ACTIVE", "lastChange": "ADDED", "deprecated": false, "version": "v2",
      "params": [ { "name": "dryRun", "in": "QUERY", "required": false, "type": "boolean" },
                  { "name": "body",   "in": "BODY",  "required": true,  "type": "object" } ],
      "sourceSpecVersion": 7,
      "firstDocumentedAt": "2026-06-20T09:00:00Z", "lastDocumentedAt": "2026-06-29T10:00:00Z",
      "changedAt": "2026-06-20T09:00:00Z" },
    { "specName": "users", "method": "GET", "pathTemplate": "/v1/legacy",
      "status": "DELETED", "lastChange": "UNCHANGED", "deprecated": false, "version": "v1",
      "params": [], "sourceSpecVersion": 6,
      "firstDocumentedAt": "2026-06-01T00:00:00Z", "lastDocumentedAt": "2026-06-20T09:00:00Z",
      "changedAt": "2026-06-29T10:00:00Z" }     // ← DELETED: 최신 문서에서 빠짐(Zombie 입력, §6)
  ]
}
```

## 5. 롤백 경계 (M7a PR #44/#45)

| 파일 | 조치 | 비고 |
|---|---|---|
| `spec/SpecDiffService.java` | **순수 제거(삭제)** | 잘못된 compute-on-read diff 모델 |
| `spec/SpecChanges.java` | **순수 제거(삭제)** | /spec/changes 응답 DTO |
| `domain/SpecCanonicalProjection.java` | **순수 제거(삭제)** | diff 입력 projection(인벤토리 모델은 불필요) |
| `domain/SpecRecordRepository.java` | **수정** — `findCanonicalVersions` 제거 | `findActiveSpecMetas`(M6 목록)는 **유지** |
| `api/SpecController.java` | **수정** — `specDiffService` 필드·생성자 주입·`GET /changes` 핸들러 제거 | PUT /spec(filename) 유지 |
| `api/SpecControllerTest.java` | **수정** — diff 단위테스트 2건 제거 | 메타/업로드 테스트 유지 |
| `integration/PostgresIntegrationTest.java` | **수정** — `specChanges*` 4건 제거 | `reuploadSameFilenameViaHttpDoesNotHit500` 의 /spec/changes 단언을 **`GET /apis` 단언으로 이관**(재업로드 200·reconcile 결과 검증 유지) |
| `doc/36` | **supersede 표기**(본 문서가 대체) | 이력 보존(삭제 아님) |
| 매뉴얼 `api-rest-manual.html §2.4` | **수정**(TW) — /spec/changes 절 제거 → `/apis` 절로 교체 | dev 머지 후 TW 동기 |

**유지(재설계에 필수·#44/#45 자산)**.
- **filename→specName 도출**(`SpecStore.specNameFromFilename` + `upload(host,content,filename)`·`upload(host,specName,content)` 오버로드) — **문서별 추적의 전제**(specName=문서 식별).
- **upload 오버로드 `@Transactional`**(#45) — reconcile 원자성에 여전히 필요. 단 oid-구동 긴급성은 §7(a) 로 소멸(rawDoc 제거 시 비활성화 루프가 oid 미materialize). `reuploadSameFilenameViaHttpDoesNotHit500` 은 **재업로드 멱등·reconcile 정확성** 회귀가드로 잔존(단언 이관).
- `SpecMetaProjection`(M6)·`spec_record.filename` 컬럼·inactive 보존(M6 목록용 — 단 **diff 기준 역할은 소멸**, 인벤토리가 SoT).

## 6. scan-path 영향 + Zombie 탐지 연결 (★사용자 핵심)

### 6.1 scan-path — 인벤토리는 **보완**(대체 아님)
현 매칭 진실원 = `loadActiveCanonical(host)`(active spec 병합 canonical). 신규 인벤토리는 **보완** — 스캔/매칭 경로 **불변(무회귀)**, 인벤토리는 사용자 노출·param·status 추적 전용. 인벤토리를 매칭 source 로 **대체**하는 것은 별도 phase(P2).

### 6.2 현 Zombie 기준(확인)
`Classifier` 2nd pass 가 active canonical `s` 순회.
- `s.deprecated() && observed` → **MANIFEST Zombie**(confidence 1.0·estimated=false, "문서에 deprecated 표기, 그러나 트래픽 발생").
- `!deprecated && observed && estimatedZombies.contains(s)` → **ESTIMATED Zombie**(0.6·estimated=true, `VersionZombieInference`: 신버전 active+구버전 트래픽).
- enum `Classification{ACTIVE,SHADOW,ZOMBIE,UNUSED,DEPRECATED_CLEAN,UNDOCUMENTED_WEB_PAGE}`. `Finding.Zombie(host,method,pathTemplate,confidence,severity,estimated,sourceRef,reason,specParams)`.

### 6.3 ★핵심 통찰 — DELETED 는 현 분류기로 못 잡는다
DELETED API 는 **active canonical 에서 빠진다**(문서에서 제거됐으니). 그래서 현 분류기 루프 `for s in spec`(spec=active canonical)는 **그 엔드포인트를 만나지 못한다** → 관측되면 1st pass 에서 **SHADOW(미문서화)로 오분류**. 인벤토리가 있어야 "**한때 문서화 → 제거됨 → 여전히 호출됨**" 을 구분해 **강한 Zombie** 로 식별한다(단순 SHADOW 보다 강한 신호 — 계약에서 의도적으로 뺐는데 클라이언트가 계속 호출).

### 6.4 결합 — DELETED ∩ 관측 트래픽 = 강한 Zombie 후보
- **`documented_api.status==DELETED` ∩ `discovered_endpoint`(관측, lastSeen 최근) 매칭** = Zombie 후보. confidence **0.8**(manifest 1.0 과 estimated 0.6 사이) 권장. reason "**문서에서 제거됐으나 트래픽 지속 — 차단/마이그레이션 검토**". estimated=false.
- **신호 구분(둘 다 Zombie 계열)**. `deprecated`(문서엔 **있으나** 폐기예정, MANIFEST 1.0) vs **DELETED**(문서서 **빠짐**, 0.8). 별개 reason·confidence 로 Finding.Zombie 에 반영.

### 6.5 결합 위치 — Classifier 1st pass SHADOW 분기 정제
관측 엔드포인트가 **active spec 미매칭** 일 때 현재 SHADOW. 신규 — 그 키가 **host 의 DELETED 인벤토리 집합** 에 있으면 **Zombie(deleted-from-spec)** 로(아니면 SHADOW). 추가 입력 = `documentedApiRepo.findKeysByHostAndStatus(host, DELETED)` 1쿼리(method+pathTemplate 집합)를 분류 파이프라인 호출부에서 조회해 분류기에 전달(순수함수 시그니처에 인자 추가·가산). active spec 미포함이라 2nd pass(deprecated-zombie)와 **중복 없음**. **무회귀**(loadActiveCanonical·matcher 불변).

### 6.6 P1 vs P2 권고
- **데이터 모델 + reconcile + `GET /apis`(DELETED 노출 포함) = P1**(필수). DELETED 가 즉시 조회 가능(노출).
- **분류 결합(DELETED→Zombie Finding) = P1 권고**. 사용자가 명시한 핵심 동기("이 정보로 Zombie 를 찾을 수 있다")이고, 1쿼리+1분기 가산·무회귀(스캔 source 불변)라 저위험. 일정상 분리해도 **데이터 모델이 P1 부터 DELETED 를 Zombie 입력으로 보유**(status·sourceSpecVersion·키)하므로 P2 로 미뤄도 모델 변경 0.
- **인벤토리로 매칭 source 대체 = P2**(큰 scan-path 리팩터).

## 7. rawDoc(oid) 운명 결정 (★보강 — 사용자 질문 반영)

### 7.1 발견(코드 확인)
`getRawDoc()` **프로덕션 호출처 0**(`SpecRecord` 게터 정의 + 테스트 round-trip 1건만). `setRawDoc()` 프로덕션 1건(`SpecStore` 업로드 저장)뿐. → **rawDoc 는 저장 전용·읽히지 않는 dead 컬럼**. 3건 oid 버그(D51·doc/28 §10)는 전부 **다른 이유의 엔티티 materialize 에 rawDoc 가 딸려온 것**(content 수요 아님).

### 7.2 옵션·권고
| 옵션 | 내용 | 평가 |
|---|---|---|
| **(a) rawDoc 컬럼 삭제** | 원본 재파싱 불필요 → 컬럼·@Lob 제거 | **★권고**. oid 함정 클래스 **구조적 소멸**(필드 제거만으로 엔티티 로드가 oid 미materialize). bytea 보다 단순 |
| (b) bytea 유지 | oid→bytea 수동 마이그레이션, 원본 보관 | **원본 보관·대규모 백필** 수요 시에만 가치. 현재 불요 |
| (c) oid 유지 | 현행 | **비권장** — 함정 잔존 |

**★권고 = (a) 삭제**. 근거. ① 저장 전용(프로덕션 read 0). ② 재설계는 go-forward(reconcile=in-memory 파싱, 저장 rawDoc 미접근). ③ 필드 제거만으로 **D51 류 버그 클래스 전체 소멸**. ④ **prod 사용자 spec ~0**(데모 정리) → 백필 코퍼스 없음. ⑤ bytea 의 유일 가치(프로그램 백필)는 ~0 에서 무의미·재업로드로 대체.

### 7.3 백필 정정(브리프 point 7)
**param 백필은 canonicalJson 으로 불가**(구 파서 산출 = param 없음) → **원본 바이트를 신규 param-aware 파서로 재파싱해야** 가능. 따라서.
- **(a) 삭제 채택 시** — 원본 없음 → 기존 spec 의 param 채움 = **재업로드(go-forward only)**. prod ~0 이라 무해(운영자가 문서 재업로드 시 reconcile 이 param 채움).
- (b) bytea 채택 시 — 저장 원본 재파싱으로 프로그램 백필 가능.

이로써 doc/36/브리프의 "rawDoc→bytea 마이그레이션 후 백필" 후속 항목은 **(a) 채택 시 재업로드로 대체**(독립 고정작업 불요).

### 7.4 (a) 삭제 절차 — 무손실·oid 정리
ddl-auto=update 는 **DROP COLUMN 안 함** → 코드/DDL 분리.
1. **코드(P1)** — `SpecRecord` 의 `rawDoc` 필드·getter/setter 제거 + `SpecStore` 의 `record.setRawDoc(content)` 제거 + rawDoc 전용 테스트 은퇴(§7.5). **필드 제거 시점에 엔티티 로드가 즉시 oid-free**(컬럼이 물리적으로 남아도 매핑 안 되면 ddl-auto 무시·무해).
2. **ops DDL(P1 배포 런북, 차기 점검창 — 코드 머지와 디커플)**. 매핑 해제된 잔존 컬럼·LO 는 무해하나 정리 권장.
   - oid Large Object 정리(컬럼 DROP **전**, 안 그러면 LO orphan). `SELECT lo_unlink(raw_doc) FROM spec_record WHERE raw_doc IS NOT NULL;`
   - `ALTER TABLE spec_record DROP COLUMN raw_doc;`
   - 또는 DROP 후 `vacuumlo` 로 orphan LO 일괄 정리. prod ~0 이라 즉시 수준.

### 7.5 부수효과(정직)
- **은퇴 테스트**(rawDoc 제거 시 대상 소멸) — `rawDocActualTypeRecorded`·§6.2 round-trip(`setRawDoc`/`getRawDoc` 단언)·`specMetaEndpointsDoNotMaterializeRawDocOidInAutoCommit`·`forHostEndpointsTolerateRawDocOidSpecOnRealPg`. oid 함정 가드들이 **가드할 대상이 없어짐**.
- `SpecMetaProjection`/`CombinedDiscoveryService.forHost` 의 oid-회피 정당성 약화(유지 무해 — 대형 text 컬럼 미로드 이점은 잔존). 강제 제거는 dev 판단.
- (b) 가 미래에 필요해지면(원본 보관·재다운로드 수요) **bytea ADD COLUMN(가산·ddl-auto 친화·go-forward 저장)** 으로 재도입 — oid 아님. 지금 삭제로 잃는 것 없음.

### 7.6 단계
rawDoc **코드 제거 = P1**(스펙 서브시스템 전면 개편과 함께·prod ~0=최저위험 창). **DDL 런북 = P1 배포 ops 절차**(별도 P 불요).

## 8. 중앙 영향

- `GET /apis` 신규 = **additive**(영향 0).
- `GET /spec/changes` 제거 — M7a 가 막 머지돼 **중앙 미통합 전제**(의도 불일치를 조기 정정). 중앙팀에 의존 부재 확인 후 제거(있으면 `/apis` 로 전환 조율).
- PUT /spec filename→specName = **유지**(중앙 업로드 계약 불변).
- 신규 테이블·rawDoc DROP = **워커 로컬 DB**(중앙은 REST 소비자) → 중앙 DB 무관.

## 9. 실 PG 테스트 계획 (Testcontainers podman)

- ① **문서 A 업로드** → `documented_api` 에 A 의 API + params(status ACTIVE·lastChange ADDED). `GET /apis` 단언.
- ② **문서 B(다른 specName) 업로드** → union: A 불변(ACTIVE)·B 추가. 
- ③ **문서 A v2 재업로드**(1 param 변경 + 1 drop + 1 신규) → 변경=UPDATED(params 갱신)·drop=DELETED·신규=ADDED. `lastChange` 단언.
- ④ ★**문서 B 업로드가 A 의 API 를 삭제 안 함**(문서별 격리) — 명시 회귀가드.
- ⑤ **oid 안전** — reconcile + `GET /apis` 가 rawDoc(엔티티) 미접근으로 비-tx auto-commit 200(§7(a) 적용 시 rawDoc 부재라 구조적 안전·H2 미재현→실 PG). 회귀 시 RED.
- ⑥ **h2-pg null 정렬 가드(D48)** — `/apis` ORDER BY(specName nullable·레거시 null) `nulls first`/`coalesce(spec_name,'default')` 미명시 회귀 시 실 PG 순서 발산.
- ⑦ **재업로드 200(#45 유지)** — 동일 filename 재업로드 → reconcile 후 200(`reuploadSameFilenameViaHttpDoesNotHit500` 이관·RED-원복 가드).
- ⑧ **param 변경 판정** — 동일 EP 의 required flip/param 추가/type 변경 → UPDATED. param 동일 재업로드 → UNCHANGED.
- ⑨ **DELETED→Zombie 결합**(P1 결합 채택 시) — `discovered_endpoint` 관측 seed + 인벤토리 DELETED 마크 → 분류 실행 → `Finding.Zombie`(deleted-from-spec·confidence 0.8) 단언. deprecated(MANIFEST)와 구분.

## 10. 단계 분할

> **구현 상태(2026-06-29)**: P1-1~P1-8 구현 완료(브랜치 `feat/documented-api-inventory`, build green 493·실패0·skip2·PostgresIntegrationTest 27/27·커밋 보류·머지 시 Done, DECISIONS D53). 1~9 항목 = TASKS P1-1~P1-8. **9 (실 PG 테스트)**=Zombie 결합·reconcile DELETED 실 PG RED-확인. **10 (매뉴얼)**=TW 후속(범위 밖·미완). **8 의 ops DDL 런북(§7.4)**=차기 점검창 ops 절차(dev 코드 범위 밖·미실행).

**P1 (재설계 본체 — 롤백 + 영속 인벤토리)**. dev 체크리스트 → TASKS subitem(D26·부모 P 버킷=doc/35 2단계).
1. M7a diff 롤백(§5 표).
2. 신규 `DocumentedApiRecord` + repository(`documented_api`·ddl-auto ADD·인덱스·unique).
3. `SpecParam`/`ParamIn` + `CanonicalEndpoint` params 가산(null-safe 기본 빈).
4. 3 파서 param 추출(OpenAPI getParameters/getRequestBody·Postman url.query/path/body·CSV 컬럼 규약).
5. reconcile-on-upload(upload `@Transactional` 내, §3, 삭제 격리).
6. `GET /api/v1/domains/{host}/apis`(+필터, status DELETED 노출).
7. **DELETED→Zombie 결합**(§6.5·6.6 권고 P1 — 1쿼리+1분기 가산·무회귀).
8. **rawDoc 삭제**(§7 — 코드 P1 + DDL 런북).
9. 실 PG 테스트(§9 ①~⑨).
10. 매뉴얼(TW) — `/apis`·status 의미·deprecated vs DELETED Zombie·/spec/changes 제거.

**P2 (후속)**.
- 인벤토리로 매칭 source 대체(loadActiveCanonical 통합·scan-path 리팩터).
- 기존 spec param 백필 = **재업로드**(go-forward, §7.3 — (a) 채택 시 코드 작업 0·운영자 행위) / (b) 채택 시 bytea 백필.
- 풍부한 param diff(type 상세·breaking 판정)·도메인-merged 뷰·inactive prune.

## 11. 무회귀 / 리스크 (정직)

- **무회귀** — 신규 테이블 ADD(무손실)·`/apis` additive·`CanonicalEndpoint` params 가산(매칭키 불변)·Zombie 결합 가산(스캔 source 불변)·rawDoc 삭제는 dead 컬럼 제거(read 0). 스캔/분류 매칭 경로 불변.
- **리스크① (롤백 범위)** — M7a 3파일 삭제 + 부분 수정. 빌드/테스트 green 확인(§5·§9). reupload 테스트 단언 이관 필수.
- **리스크② (파서 param 추출 정확도)** — 3 포맷 스키마 다양성. `type` 요약 수준으로 범위 한정(전체 schema 아님)·포맷별 테스트.
- **리스크③ (rawDoc DROP ops)** — ddl-auto 미DROP → 수동 DDL + LO 정리(lo_unlink/vacuumlo). 코드 머지와 디커플(잔존 무해), 차기 점검창. prod ~0=저위험.
- **리스크④ (SEPARATE 모드)** — union 모델은 MERGE/VERSION_GROUPED 전제. SEPARATE 다문서는 비일반 — dev 처리 명시(§3).
- **리스크⑤ (백필 한계)** — (a) 삭제 시 기존 spec param = 재업로드로만. prod ~0 이라 수용. 대규모 코퍼스 발생 시 (b) 재검토.
