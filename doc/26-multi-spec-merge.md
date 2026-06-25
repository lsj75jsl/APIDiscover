# 검출/업로드 데이터 모델 통합 + 멀티 스펙 병합 전략 — 설계

> 브랜치 `feature/multi-spec-merge`. doc/14(D21) 후속에서 **데이터 모델 재설계로 확장**(사용자 신규 요구). 근거 doc/03(canonical)·doc/04(분류)·doc/13(ParamCandidates)·doc/14·doc/15(매처캐시)·doc/24(EndpointHistory/recency)·doc/25(SpecSource/warnings).
> 근거 결정 doc/DECISIONS.md **D35**(멀티스펙·모드)·**D36**(검출 SoT 테이블·version 차원·host 조회·EndpointHistory 통합).
> **전체 범위 A~E. 제안·사용자 확인 대기(dev 미착수). 각 선택에 권장안 명시.** dev 항목은 TASKS 부모 아래 subitem(D26).

통합 범위: **A** 검출 전용 테이블(`discovered_endpoint`) ↔ `spec_record` 대칭 분리 · **B** 멀티 스펙 + 병합 모드 · **C** API version 차원(DB 반영) · **D** 비교·결합(불변) · **E** 마이그레이션·무회귀·ETag. **전 테이블 host(도메인) 기준 조회 필수**(신규 제약, §7).

## 0. 현 상태 / 문제

- **검출(detected) API 는 전용 영속이 없다**: 스캔이 `discovered: List<DiscoveredEndpoint>`(in-memory)→분류→`ScanResult.reportJson`(분류 결과만). 원(raw) 검출 인벤토리는 reportJson 안에 묻혀 있고, recency 일부만 `EndpointHistory`(doc/24, host당 @Lob `Map<specKey,{firstSeen,lastSeen}>`, **spec 매칭분만**)에 보관.
- **업로드(spec)**: `spec_record`(PK=id, host, specVersion, active=1, canonicalJson). **단일 active**·upload=전체 교체. 멀티 문서·버전 차원 없음.
- **version 차원 부재**: doc/16 path-version-zombie(자동 path `^v\d+$`)만, 스키마 버전 그룹 없음.
- **문제**: 검출/업로드가 비대칭(업로드만 테이블), 검출 SoT 없음(reportJson/EndpointHistory 분산·중복), 멀티 스펙·버전 그룹·병합 옵션 불가, host 단위 결합 조회 미흡.

## 1. 통합 데이터 모델 개요 (host 중심)

**두 SoT 테이블(대칭) + 파생 분류 뷰**, 전부 host 키.
```
 [업로드 SoT] spec_record(host, specName, specVersion, canonicalJson, version…)  ─┐
 [검출  SoT] discovered_endpoint(host, method, pathTemplate, version, recency…)  ─┤→ Classifier(불변)
                                                                                   │   → findings(Shadow/Active/Zombie/Unused)
 [분류 뷰]  scan_result.reportJson (파생·ETag, /result)  ←───────────────────────┘   → 결합 Discovery 목록(host, 버전그룹)
```
- **SoT 역할 분담(사용자 요구 명시)**:
  - `spec_record` = **업로드 SoT**(canonical, 멀티 문서).
  - `discovered_endpoint` = **검출 SoT**(누적 검출 인벤토리 + recency). **`EndpointHistory`(doc/24) 흡수**(firstSeen/lastSeen 이 여기로) → 중복 제거.
  - `scan_result.reportJson` = **분류 뷰(파생)** — 두 SoT ⊕ Classifier 결과. SoT 아님(재생성 가능), /result ETag 응답 유지.
- 전 테이블 host 키 → host 단위 (검출∪업로드) 결합 조회(§7).

## 2. discovered_endpoint 스키마 (A) — 검출 SoT, spec_record 대칭

```java
@Entity @Table(name="discovered_endpoint",
  uniqueConstraints=@UniqueConstraint(columnNames={"host","method","path_template"}),
  indexes={@Index(columnList="host"), @Index(columnList="host,version")})
class DiscoveredEndpointRecord {
  @Id @GeneratedValue Long id;     // (spec_record 와 동일 스타일)
  String host;                     // 도메인 조회 키 (indexed)
  String method; String pathTemplate;     // unique(host,method,pathTemplate) = upsert 키
  String templateSource;           // SPEC / INFERRED
  String endpointKind;             // WEB_PAGE/STATIC/API_CANDIDATE/UNKNOWN + double kindConfidence
  String version;                  // 버전 태그(nullable, §4)
  Instant firstSeen; Instant lastSeen; Instant lastScanAt;   // 누적 recency (EndpointHistory 흡수)
  long hits; @Lob String statusDistJson;                     // 최신 윈도우 스냅샷(카탈로그 표시)
  boolean hadQuery; boolean nonBrowserUa; @Lob String paramsJson;   // ParamCandidates(doc/13)
}
```
- **PK/조회**: 생성 id PK + **host 인덱스** + unique(host,method,pathTemplate). `findByHost(host)`(카탈로그), `findByHostAndVersion`(그룹), upsert=`findByHostAndMethodAndPathTemplate`. spec_record(id PK+host) 스타일 일치.
- **upsert(스캔마다)**: firstSeen=min(기존,현재)·lastSeen=max·lastScanAt=window end·스냅샷 메트릭(hits/status/params) 최신 윈도우값·version 재계산. 누적 카탈로그.
- **카디널리티 가드**: 인벤토리 cap(host template 5000, doc/13) upsert 전 적용 + **retention prune**(lastSeen < now−retention, 예 180d → 삭제). EndpointHistory 는 spec-only 라 bound 됐지만 검출은 Shadow/inferred 포함 → cap+prune 필수(스캐너 noise 누적 방지).
- **메트릭 분담(린)**: 카탈로그=identity+kind+version+recency+기본 활동(hits/status/params). **p50/p95·distinctClients·acrm 등 분석 상세는 reportJson(per-scan) 유지**(카탈로그 비대화 방지). severity(doc/24) 는 firstSeen 만 카탈로그서 읽고 나머지는 live Evidence.
- **EndpointHistory 흡수**: doc/24 의 (host,specKey,firstSeen,lastSeen)→discovered_endpoint 의 (host,method,pathTemplate,firstSeen,lastSeen). severity recency=matched template 의 firstSeen 조회. `endpoint_history` 테이블·엔티티 제거(§8 마이그레이션).

## 3. spec_record 멀티 문서 (B 데이터)

- `spec_record` +`String specName`(host 내 문서 식별, null→"default"). PK=id 유지(이미 멀티행). **host active set** = specName 별 최신 active. upload(host,name,content)=같은 name 이전 active 만 비활성+신규(문서 단위 upsert). 기존 데이터 specName null→default→현행.
- host 조회: `findByHostAndActiveIsTrue(host)`(active set), `findByHostAndSpecNameAndActiveIsTrue`(upsert).

## 4. API version 차원 (C) — DB 반영, 검출+업로드 양측

- **검출 측**: `discovered_endpoint.version`(컬럼, nullable) — upsert 시 도출: path `^v(\d+)$` 세그먼트(doc/16 로직) → 없으면 매칭 spec.version → 없으면 null. 인덱스 (host,version) → host 내 버전 그룹 조회.
- **업로드 측**: `CanonicalEndpoint.version`(이미 존재, per-endpoint, canonicalJson 내) + **`spec_record.specName`(컬럼)=문서/버전 그룹 라벨**(운영자가 "v1"/"v2" 문서로 분리). DB 버전-그룹 쿼리는 specName 컬럼(coarse) + canonical.version(fine, in-JSON).
- **두 그룹 입도**: (a) 문서/그룹 = specName(컬럼·queryable), (b) endpoint = version(검출 컬럼·spec canonical 내). VERSION_GROUPED 결합 뷰는 **version 라벨**(검출 path-version ∪ spec endpoint version, 정규화)로 host 내 그룹.
- doc/16 자동 path-version-zombie 와 공존(operator group=명시 specName, path=자동 version) — 상보.

## 5. 병합 전략 모드 (B) — MERGE / SEPARATE / VERSION_GROUPED

- **옵션 위치(권장)**: 도메인 영속 **`DomainConfig.specMergeStrategy`**(enum, 기본 `MERGE`). 근거: host 단위 안정 정책, `DomainController` CRUD/DTO 가산=추가 엔드포인트 0(D25), 중앙 튜닝 자동. ddl-auto(기존 null→MERGE). per-upload override 미채택(후속).
- **모드**:
  - **MERGE**(기본·현행): specName upsert(형제 유지) → 활성 spec = canonicalize(∪ active docs) flat 1집합.
  - **SEPARATE**(새 목록 관리): 업로드가 **타 문서 전부 비활성** → 새 문서가 권위 전체 spec(이전 archive). "현 API 목록을 새 문서로 갈아끼움". *(주의: '독립 목록 병존'을 원하면 그것은 VERSION_GROUPED(그룹 뷰)이거나 heavy 병렬 인벤토리(미채택)임 — 사용자 확인 포인트.)*
  - **VERSION_GROUPED**: 다 문서 active 공존, **매칭은 union(MERGE 와 동일 1매칭셋)**, 결합 뷰/리포트를 **version 그룹별 분리 노출**(§4 version 라벨). v1·v2 공존·그룹 관리.
- **case × mode 처리표**:

| mode \ case | ① 기존 API 수정 | ② API 추가/삭제 | ③ 새 버전(v2) |
|---|---|---|---|
| **MERGE**(기본) | 같은 specName 재업로드=교체→재병합; 타 specName 충돌=**latest-wins+deprecated OR**(§5 충돌) | 새 specName=추가(union↑)/같은 specName 재업로드로 누락=삭제(union↓)/타 문서 잔존 | v1+v2 flat 1집합; doc/16 자동 v1 Zombie 추정 |
| **SEPARATE** | 새 업로드=전체 교체→최신본만(기존 정의 소멸=최신 권위) | 새 문서가 곧 전체 목록(이전 전부 비활성) | v2 만 spec(v1 비활성)→v1 트래픽=Shadow. "v1 폐기·v2 클린 컷" |
| **VERSION_GROUPED** | 같은 group(specName/version) 내 교체; 그룹 간 분리 | group 단위 추가/삭제 | v1·v2 group 공존, 매칭 union·**결합 뷰 그룹 분리**; v1 group 전체 deprecated 관리 |

- **충돌 해소(case①)**: MERGE — dedupe(method,host,template); `deprecated`=**OR**(안전); 비-deprecated(version/sourceRef)=**latest-upload-wins**(최신 specVersion 권위=수정 반영), 결정적(specVersion 영속·tie sourceRef; merged SET 은 업로드 순서 무관, 승자만 latest). SEPARATE=충돌 없음(단일). VERSION_GROUPED=그룹 내 MERGE 규칙. specName priority 미채택.

## 6. 비교·결합 (D) — 불변 유지

- **Classifier 불변(불변 유지 요구)**: 검출(D)=discovered_endpoint, 업로드(S)=active spec canonical(병합/모드 적용) → 현행 Classifier 로 **Shadow(D\S)/Zombie(S_dep∩D)/Active/Unused** 산출. **두 출처 분리 유지**(Shadow=검출-only, Unused=spec-only). 분류 로직 무변경.
- **결합 Discovery 응답(host 단위)** = findings 하나의 목록(검출∪업로드). VERSION_GROUPED 시 **version 그룹 구조 포함**(그룹별 endpoint/분류). 그룹 라벨=§4 version.
- **분류 입력 범위(결정점·권장)**:
  - **권장(무회귀)**: 분류는 현행대로 **per-scan window 검출**으로 findings 산출(/result 불변). `discovered_endpoint` 는 누적 SoT 로 **별도 host 카탈로그 뷰**(검출∪spec, lastSeen recency·버전그룹) 제공 → "도메인 Discovery 결합 목록".
  - (대안·확인 후) 분류 입력을 **누적 카탈로그**로(완전 인벤토리) — 단 오래된 검출의 staleness 처리(lastSeen 기반 "stale/active" 주석) 필요 → 리포트 범위 변화라 확인 포인트.
  - 권장: per-scan 분류 유지 + 누적 카탈로그 결합 뷰 신설(둘 다, 무회귀).

## 7. host(도메인) 기준 조회 (신규 제약)

- **전 테이블 host 키 일관**: `discovered_endpoint`(host index+unique(host,method,template)), `spec_record`(host, +specName), `scan_result`(PK host), `domain_config`(PK host). `endpoint_history` 는 제거(흡수). **논리 FK** host(기존 doc/18 §3.2 패턴, DB 제약 없이 host 일치).
- **host 단위 결합 조회**: `discoveredRepo.findByHost(host)` ∪ `specStore.loadActiveCanonical(host)` → Classifier → 결합 목록(+버전그룹). 검출 카탈로그 `findByHost`/`findByHostAndVersion`, spec `findByHostAndActiveIsTrue`.
- **REST(권장 범위)**: 결합 뷰는 host 단위 응답(기존 `/api/v1/domains/{host}/...` 일관). 원 카탈로그 조회 엔드포인트(`/discovered`·`/spec` list)는 선택(자체조회 P1 최소 / 중앙 노출 P4). upsert 도 host 별(스캔 경로).

## 8. 마이그레이션 · 무회귀 · ETag (E)

- **무회귀**: 기본 `MERGE`+단일 default → §0 현행 동치(미설정 도메인=MERGE). 모드 전환만 재병합·`matcherCache.invalidate(host)`·합성버전 bump. per-scan 분류 유지(권장)라 /result findings 불변.
- **마이그레이션(ddl-auto update)**: `discovered_endpoint`·`spec_record.spec_name`·`discovered_endpoint.version` 신규(기존 데이터 무영향). **EndpointHistory→discovered_endpoint**: (a) 1회 이행 잡(historyJson→행) 또는 (b) 재구축(firstSeen 신규 누적; 콜드스타트=현행 severity, 무회귀) — **권장 (b)**(lean, doc/24 콜드스타트 폴백이 이미 현행 보장). `endpoint_history` 테이블 deprecate 후 제거.
- **합성 spec 버전**: merged canonical 콘텐츠 결정적 해시 → report.specVersion/SpecSource/matcherCache 키(per-record specVersion 은 이력). 동일 콘텐츠=동일 버전(안정).
- **ETag(결정적·시간非의존, doc/24 선례)**: 결합/findings 콘텐츠 기반, `now()` 불사용. severity→band·params→이름집합 투영(doc/24/25) 유지. discovered_endpoint recency(lastSeen)는 카탈로그 뷰에 노출하되 **ETag 엔 버킷/비포함**(시간 흐름 churn 방지) — lastSeen 자체는 ETag 입력 제외, 분류 라벨/그룹만.
- **정합**: 매처캐시(doc/15)=합성버전+invalidate, low_confidence/warnings(doc/25)=spec 측 union, EndpointHistory(doc/24)=discovered_endpoint.firstSeen 로 이관. 전 모드 공통 정합축=**매칭셋·specKey·host 키 불변**.

## 9. 권장 요약 (사용자 확인용)

| 항목 | 권장안 |
|---|---|
| 검출 SoT | **신규 `discovered_endpoint`**(host index+unique(host,method,template)), 누적 upsert, cap+retention. **EndpointHistory 흡수**(firstSeen/lastSeen 이관) |
| SoT 분담 | spec_record=업로드 / discovered_endpoint=검출 / reportJson=파생 분류 뷰 |
| 메트릭 분담 | 카탈로그=identity+kind+version+recency+hits/status/params; 분석 상세(p50/p95·distinctClients)는 reportJson |
| version 차원 | 검출=`version` 컬럼(path/spec 도출), 업로드=`specName` 컬럼(그룹)+canonical.version(endpoint). host 내 그룹 |
| 병합 모드 | **MERGE(기본)·SEPARATE(교체)·VERSION_GROUPED(공존·그룹 뷰, 매칭 union)**. 병렬 인벤토리 미채택 |
| 옵션 위치 | **`DomainConfig.specMergeStrategy`**(기본 MERGE), per-upload override 미채택 |
| 충돌 | latest-upload-wins(비-deprecated)+deprecated OR |
| 분류 범위 | per-scan 유지(무회귀)+누적 카탈로그 결합 뷰 신설(둘 다) |
| host 조회 | 전 테이블 host 키/인덱스, host 단위 결합 응답 |
| 무회귀 | 기본 MERGE=현행, EndpointHistory 재구축 이관(콜드스타트=현행) |

## 10. dev 구현 체크리스트 (TASKS subitem, D26 — staged)

**1단계 데이터 모델(A/C)** → 완료 2026-06-25 (커밋 보류·리뷰 대기)
- [x] `domain/DiscoveredEndpointRecord`(host index+unique(host,method,path_template)+version) + repository(findByHost/findByHostAndVersion/findByHostAndMethodAndPathTemplate/deleteByHostAndLastSeenBefore). 카디널리티 cap(5000)+retention prune(180d, 데이터 ts 기준).
- [x] `DiscoveryJobService` — 스캔 discovered → discovered_endpoint 누적 upsert(firstSeen min/lastSeen max/최신 윈도우 스냅샷). severity recency 를 discovered_endpoint.firstSeen 로 전환(Evidence entrenchedFirstSeen, signature 키), **EndpointHistory 엔티티/repository/observedTimes/EndpointObservation 제거**(재구축 이관=콜드스타트 현행 무회귀).
- [x] `spec_record` +`specName`(null→"default", 스키마/컬럼만 — 멀티문서 upsert 는 2단계) + `discovered_endpoint.version` 도출(path `^v\d+$` 세그먼트 → 매칭 spec.version → null).

**2단계 멀티스펙+모드(B)**
- [ ] `DomainConfig.specMergeStrategy`(MERGE/SEPARATE/VERSION_GROUPED, 기본 MERGE)+DomainController DTO. `SpecStore` 모드 분기(MERGE upsert/SEPARATE 형제 비활성/VERSION_GROUPED active 공존).
- [ ] `SpecCanonicalizer` 결정적 merge(union pre-sort→dedupe+deprecated OR, latest-wins 비-deprecated). 합성 spec 버전(해시)→matcherCache/report/SpecSource.

**3단계 결합·버전그룹(C/D)**
- [ ] host 결합 Discovery 뷰 — Classifier(discovered_endpoint ∪ active spec) 불변, 결합 목록 + VERSION_GROUPED 그룹 구조. `SpecSource`+documents(doc/25 확장).
- [ ] (선택) `/discovered`·`/spec` host 조회 엔드포인트.

**공통**
- [ ] 테스트 — discovered_endpoint upsert/recency/cap·prune / 모드 case×mode / 결정성·합성버전 / 단일=현행 무회귀 / EndpointHistory 이관(severity 콜드스타트=현행) / host 결합 조회·버전그룹 / ETag 결정적(시간非의존).
- [ ] (doc/18 sync, technical_writer) `discovered_endpoint`·`spec_record.spec_name`·`endpoint_history` 제거 반영.

## 11. 범위 밖 / 후속

- **누적 카탈로그 기반 분류**(per-scan 대신, staleness 주석) — 확인 후 별도.
- **완전 독립 병렬 인벤토리**(버전 그룹별 별도 매칭/ETag) — heavy, 경량(§5 그룹 뷰)로 충분 → 격리 필요 시 후속.
- 다중 출처 provenance(sourceRef 결합)·spec query-param 캐노니컬 확장 — 별도.
- 중앙 멀티-spec 오케스트레이션·원 카탈로그 REST 노출·auth — P4(외부연동).
- 문서 간 호환성 경고(같은 endpoint 상이 정의→spec_source.warnings) — 후속(doc/25 채널).
