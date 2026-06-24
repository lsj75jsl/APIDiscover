# TASKS — 작업 목록 (세션 메모리)

> 새 세션 시작 시 **반드시 이 파일을 먼저 읽고** todo/done 을 파악한 뒤 작업을 이어간다.
> 완료한 항목은 `[x]` 로 표기하고 **Done** 섹션으로 옮긴다.
> 설계 도출 개발 항목은 해당 작업의 **subitem(들여쓴 하위 체크박스)** 으로 추가하고, **모든 subitem 완료 시 부모를 `[x]` 로 바꿔 Done 으로 옮긴다**(D26).
> 의사결정은 `doc/DECISIONS.md`, 진행 로그는 `doc/PROJECT_LOG.md`, 설계 상세는 `doc/00~17`.

---

## 설계문서 ↔ TASKS 매핑 (dev 항목 일원화)

> 설계문서(09~17)의 'dev 구현 체크리스트'는 해당 PR 머지로 완료됐고, '범위 밖/후속'은 아래 TODO 로 흡수했다.
> 다음 세션은 **이 TASKS 를 단일 기준**으로 보면 된다(설계 상세만 doc 참조).

| doc | 다룬 범위(구현 완료) | 잔여 후속(→ TODO 위치) |
|---|---|---|
| 08 (D15) | API 점수화 코어(ApiScorer·신호·게이트·프로파일) | 가중치 실데이터 보정 → 보류 |
| 09 (D16) | explicit-hint 매처(MatcherConfig·ApiHintMatcher) | — |
| 10 (D17) | 분류설정 DB 저장(ClassificationConfig·resolver) | repeatMinCount override → P4(파라미터 중앙 API) |
| 11 (D18) | 분류설정 중앙 REST + effective 캐시 | 서비스간 인증 → P4 / HA cross-instance 무효화 → P3(HA) |
| 12 (D19) | non_api dropped 메트릭(DroppedNonApi) | Actuator 노출 → P3 / scan-status total → P1(리포트, 선택) |
| 13 (D20) | 정규화 고카디널리티(T1 상한·T2 param·T3 sensitive) | Active/Zombie param 노출 → P1 / sensitive·상한 중앙 API → P4 / HLL·t-digest → P1 |
| 14 (D21) | Postman/CSV 파서 + 3종 Canonical 동일성 | 멀티 스펙 병합 → P1 / spec_source.warnings 채널 → P1(리포트) |
| 15 (D22) | 매처 캐시 무효화(EndpointMatcherCache) | HA cross-instance 무효화 → P3(HA) |
| 16 (D23) | 버전 Zombie 추정 + Zombie severity | cross-scan recency → P1(분류) / 추정 임계 중앙 API → P4 |
| 17 (D24) | response_type_api 양성 가중치 | $type taxonomy 샘플링 → P1 / 실데이터 보정 → 보류 |

> **의도 배제(후속 아님)**: doc/11(D18) §5 의 `@Version` 낙관락은 **last-writer-wins 채택으로 미채택**(설정 쓰기 희소 — 충돌 비용 < 복잡도). TASKS 항목 없음이 정상. 동시 쓰기 충돌이 잦아지면 재검토.

---

## TODO

> **우선순위 방침(사용자 결정, D25)**: 기본/자체 기능 먼저, **외부(중앙) 연동은 나중**. 순서 = P1 자체 분석기능 → P2 품질/테스트 → P3 운영 → P4 외부연동 → 보류.
> 자체 기능이 충분히 갖춰진 뒤 외부연동 API 를 진행한다. `→ 의존:` 메모는 선행 조건.
> 단, 이 우선순위는 **카테고리(P1~P4) 단위**다 — 같은 P 버킷 안의 개별 항목 긴급도/규모는 별개이며, `(선택·낮음)` 라벨 항목(예: scan-status `total_dropped`)은 P1 안에서도 후순위다.

### P1. 자체 분석 기능 (먼저)

#### 분류 (04/16 문서)
- [ ] (한계) preflight vs 진짜 OPTIONS 구분 불가로 스펙 OPTIONS operation Unused 오판 가능 **(분석 완료 → doc/23, DECISIONS D32 — B: 로그 신호 부재로 한계 확정·문서화)**
  - [x] (판정) 로그 신호 분석 → B 확정(Origin/ACRM 미로깅·약신호 비결정) + 한계 범위·영향 문서화 (doc/23 §1·§2)
  - [x] (M1·권장·선택) Unused(OPTIONS) inconclusive 주석 — `corsKeys` 재사용, `Finding.Unused`+`preflightAmbiguous`(4-arg 편의 ctor 하위호환), 2차 패스 분기(host-agnostic spec 은 template 매칭) + 테스트 3
  - [x] (M2) operator genuine-OPTIONS 힌트 (설계 → doc/23 §8, DECISIONS D32) — documented OPTIONS 의 false-Unused 회복(spec-match 한정)
    - [x] (M2-a) `MatcherConfig`+`optionsOperationPrefixes`(List)+5-arg 편의 ctor+`merge` union+NONE/NONE_EMPTY (matcherJson 마이그레이션 0). 리졸버 정규화 6-arg 보존(누락 버그 방지)
    - [x] (M2-b) `ApiHintMatcher` `genuineOptions(template)` (validatePrefixes 재사용·세그먼트경계 prefixMatch)
    - [x] (M2-c) `Classifier` 1차 OPTIONS 분기 한정 — `genuineOptions && matcher.match(OPTIONS)→observedSpec(→Active)`, else skip; corsKeys/cors 보너스 무변경
    - [x] (M2-d) 테스트 — 선언+spec+트래픽→Active(M1 ambiguous 아님)/미선언→M1 유지/선언+미스펙·과declare→skip(Shadow 무)/merge 전역∪도메인/중앙 PUT 수용·검증(400)/기존 호출부 무변경
  - [x] (M3) acrm 결정적 preflight 해소 (설계 → doc/23 §9, DECISIONS D32) — **dormant 구현 완료**(기본 idx=-1→DORMANT=현행 100%, org 로그포맷 커밋 시 활성)
    - [x] (M3-a) `ParsedRequest`+`acrm`(nullable, 14-arg 편의 ctor) + `LogLineParser` 설정 인덱스(`apidiscover.parse.acrm-field-index` 기본 -1=미사용, 있으면 읽는·20/24필드 호환)
    - [x] (M3-b) `Acc`+`acrmPresentCount`(add/mergeFrom) → `DiscoveredEndpoint.Metrics`+`acrmPresentCount`(7-arg 편의 ctor 하위호환)
    - [x] (M3-c) `Classifier` 가용성 게이트(`Σ acrmPresentCount(OPTIONS)>0`=ACTIVE) — ACTIVE: acrm-absent=genuine→정상 매칭(Active/Shadow)·corsKeys=preflight(acrm>0)만; DORMANT: 현행 skip+M2
    - [x] (M3-d) M1 정합 — `preflightAmbiguous` 조건에 `&& !preflightActive`(ACTIVE→확실 판정 자동 승급); M2 와 게이트 배타
    - [x] (M3-e) `model/PreflightSignal(status, acrmPresentOptions)` DiscoveryReport 노출 + ETag(status 만) + 테스트(dormant 무회귀·active genuine→Active·pure preflight→plain Unused·mixed·게이트 경계·인덱스 파싱·ETag bump)
- [ ] **(신규, doc/16 후속)** 절대 cross-scan recency 로 Zombie severity 보강 — 현재 severity 는 단일 스캔 내 span 대용. `→ 의존:` 스캔 이력(과거 lastSeen) 영속(현재 ScanResult 최신 1건만)

#### 리포트/출력 (01/12/14 문서)
- [ ] `low_confidence` 분리 노출 + `spec_source.warnings` 리포트 반영 — `→ 의존:` doc/14 seam(`SpecParser.parse→SpecParseResult(endpoints, warnings)`), 현재 파서 경고는 log 만
- [ ] **(신규, doc/13 후속)** Active/Zombie finding 에 param 후보 노출 (현재 Shadow 만 `params`) — `→ 의존:` doc/13 `ParamCandidates`(완료), 스펙 param 정의 활용
- [ ] **(신규, doc/12 후속, 선택·낮음)** `scan-status` 요약에 `total_dropped` 비정규화 컬럼(at-a-glance) — 현재 사유별 상세는 `/result` 만

#### 스펙 파서 / Spec Store (03 문서)
- [ ] 멀티 스펙 업로드(여러 문서 병합)

### P2. 품질/테스트
- [ ] 엔티티 캡슐화 (현재 스캐폴딩상 public 필드)
- [ ] `@Lob String` JSON 컬럼 PostgreSQL TEXT 매핑 실검증(canonical/report/classification 공통)
- [ ] 통합 테스트 (Testcontainers: 실제 PostgreSQL/JPA, REST API e2e, 조건부 GET 304) — `→ 의존:` 위 PostgreSQL 매핑 검증과 함께
- [ ] 매칭 엣지 케이스(04 §7) 회귀 테스트

### P3. 운영/인프라 (자체 운영)
- [ ] off-peak 시간대 제한
- [ ] 부하/운영 메트릭 (쿼리수·바이트·429) Actuator/Micrometer 노출 + 알람 — doc/12 `DroppedNonApi`·doc/13 `DroppedByLimit` 카운트 재사용 가능
- [ ] Spring Batch JobRepository 실연결 (현재 `@Scheduled`만, `batch.job.enabled=false`)
- [ ] 도메인별 `intervalOverride` 스케줄 반영 (도메인 설정은 이미 영속, 스케줄러 반영만)
- [ ] HA 단일 실행 보장 (ShedLock 또는 Quartz 클러스터) — 도입 시 **cross-instance 무효화**(effective 설정 캐시·매처 캐시 TTL/pub-sub, doc/11 §3·doc/15 후속) 함께

### P4. 외부 연동 (자체 기능 완료 후)
> 중앙 서버 연동·인증. 자체 분석기능(P1)이 안정된 뒤 진행.
- [ ] 서비스 간 인증 실구현 (mTLS 또는 OAuth2 client-credentials) — 현재 `SecurityConfig` permitAll
- [ ] 완료 웹훅 (Worker→중앙 scan-events push) 실구현
- [ ] **(신규 묶음, doc/10/11/13/16 후속)** 분석 파라미터 중앙 API 확장 — 기존 분류설정 중앙 API(doc/11) 패턴·`@ConfigurationProperties` 재사용:
      `repeatMinCount` override(doc/10) / sensitive 키 목록·normalization 상한 도메인 override(doc/13) / version-zombie·severity 추정 임계(doc/16). `→ 의존:` doc/11 분류설정 중앙 API(완료)

### 보류 (08 §9 — 현 시점 미채택)
- [ ] (보류) endpoint decision cache — 배치 재집계 구조라 이득 작음, 필요 시 재검토
- [ ] (보류) 가중치 **실데이터 보정** — 참고 설계 정확값 + `responseTypeApi`(doc/17)·Zombie `severity`(doc/16) 1차값(보정 전 임의값)을 실 Loki 데이터로 보정 후 확정

---

## Done

### distinct/분위수 대용량 근사 — Acc HLL+KLL sketch (2026-06-24, doc/22 / DECISIONS D31, PR #12) — tests=267 green
- [x] `Acc` 필드 교체 — `HashSet clients`→`HllSketch`(lgK=12), `ArrayList respTimes`→`KllDoublesSketch`(k=200) (DataSketches 6.1.1 기확보, 신규 의존성 0)
- [x] `Acc.add` — `hll.update(ip)`(null skip)·`kll.update((double)ms)`; `mergeFrom` — HLL `Union`·KLL `merge`
- [x] `Acc.toEndpoint` — distinctClients=`round(hll.getEstimate())`·p50/p95=`round(kll.getQuantile, INCLUSIVE)`(빈 sketch→0), `Metrics`(long) shape·소비처 불변
- [x] 테스트 — 정확도(HLL±3% 결정적·KLL rank 허용)·경계(distinct 0/1/2 HLL-exact→shadowConfidence)·병합(분할 union/merge≈단일)·회귀(기존 normalizer·percentile exact 단언 green 유지)
- [x] (확인) ETag 무변경(distinctClients/percentile 비입력)·CardinalityNormalizer 임계(statics.size, 근사 무관) 무변경·sketch 비영속
> 변경 Acc.java 한정(surgical). 본질=per-signature 고정크기 메모리 가드(무한 성장 ArrayList 제거). distinctClients '<=1' 경계는 HLL 소-N exact(coupon)·getQuantile INCLUSIVE=nearest-rank 동치라 분류/단언 무파손. 신규 의존성 0(DataSketches 기확보 D8). 리뷰 P1/P2/P3=0.

### $type taxonomy 샘플링 확정 + corpus $type 히스토그램 (2026-06-24, doc/21 / DECISIONS D30, PR #11) — tests=261 green
- [x] (research 0.4) doc/21 §A 프로토콜로 Loki $type 샘플링(작은 창/limit·부하보호·`limit=1e8` 금지, 총 3쿼리) → 증거표. **vocab={document,library}, API_TYPES 5값 실관측 0, document 트랩 ≈100%(api 호스트)**
- [x] (분석) 증거표 → §B 규칙 → API_TYPES 무변경 확정 + document 트랩 재확인, doc/21·DECISIONS D30 결론 기록
- [x] (Tier0) `EndpointKindClassifier.API_TYPES` 무변경 + 근거 주석(실관측 0·관례 유지·responseTypeApi dormant·자동 전파), ApiScorer 무변경 확인
- [x] (Tier1) corpus `$type` 히스토그램 — `InventoryBuilder` 집계(top-N 20+other)→`model/TypeDistribution`→`DiscoveryReport`+`ReportBuilder`+`DiscoveryJobService` ETag(distinct 키집합만, count 제외)
- [x] 테스트 — API_TYPES 매핑 5값 불변/히스토그램 집계·top-N·other·노출/ETag(신규 키 bump·count 무bump)/무회귀(확장자 1순위·document 약신호)
> 데이터 게이트: 실관측이 추가/제거 근거를 안 줘 API_TYPES 무변경 확정, responseTypeApi/$type-API_CANDIDATE dormant 확정(무감점·무해). Tier1 히스토그램은 앱별 vocab self-reporting(매 스캔, 수동 Loki 재조회 불요). ETag 는 키집합만→드리프트 bump·count 무bump.

### endpoint_kind referer 보조 신호 (2026-06-24, doc/20 / DECISIONS D29, PR #10) — tests=256 green
- [x] `model/RefererSignal`(SignalStatus·pageUrls·ratios·`dormant()`) + `model/EndpointKindSignal`(노출 status·ratios·`NONE`) + `enum SignalStatus{ACTIVE,DORMANT}`
- [x] `normalize/RefererSignalExtractor`(@Component) — static referer path 정규화→PAGE_URLS freq + static_ratio/referer_present_ratio + 게이트(원시 ratio 비교, static≥0.05 AND referer≥0.20, else dormant)
- [x] `EndpointKindClassifier` — 3-arg classify(+RefererSignal) UNKNOWN+active+PAGE_URLS≥2→WEB_PAGE(conf 0.6, 비대칭 양성), 2-arg 오버로드(dormant 위임) 하위호환, `isStaticPath()` public
- [x] `InventoryBuilder.buildWithLimits` — corpus pre-pass(RefererSignalExtractor)→classify 3-arg→`InventoryResult` 에 EndpointKindSignal
- [x] `model/DiscoveryReport` top-level `endpointKindSignal`(non-null) + `ReportBuilder.build` 인자 + `DiscoveryJobService.analyze` ETag(ratios round3, 게이트는 원시값)
- [x] 테스트 — PAGE_URLS 구축 / web_page 가점(UNKNOWN+PAGE_URLS≥2) / 비대칭(부재→UNKNOWN·무감점) / dormant 게이트 / 게이트 경계 포함성(`>=`) / $type 우선 / 2-arg 오버로드 하위호환 / 노출·ETag
> 비대칭 양성(referer 부재=무증거, 감점 없음) + dormant 게이트(실 Loki 정적 미경유 환경 무회귀). $type 우선·WEB_PAGE⊕API_CANDIDATE 배타로 doc/17 responseTypeApi 무충돌. 리뷰 P1/P2/P3=0(P3 2건 수정 후 재리뷰 클린).

### 실재성 404-only 필터 (인벤토리 단계) (2026-06-24, doc/19 / DECISIONS D27) — tests=243 green
- [x] `Acc` — `status404` 카운터(add/mergeFrom) + `isNonExistent()`(hits>0 && status404==hits) — 404 100% 만, 401/403 보존
- [x] `InventoryBuilder.buildWithLimits` — Acc 집계 후·승격/상한 전 `source==INFERRED && isNonExistent()` 제외+카운트(SPEC 보호) + `InventoryResult` 에 droppedNonExistent 추가
- [x] `model/DroppedNonExistent(int notFound)` + `DiscoveryReport` top-level(항상 non-null) + `ReportBuilder.build` 인자
- [x] `DiscoveryJobService.analyze` — droppedNonExistent → ReportBuilder 전달 + ETag 입력 추가
- [x] 테스트 — 404-only INFERRED drop·카운트 / 401·403-only 보존 / 2xx·3xx·5xx 혼재 보존 / mostly-4xx(≠100%) 보존→Classifier soft -0.7 / spec 매칭(SPEC) 보존 / reportJson·ETag 반영
> hard-drop(100%-404, INFERRED) ⊂ soft(4xx≥90%, Classifier -0.7) — 역할 분리. 통합 4xx 아닌 404 전용으로 401/403 보존. SPEC 매칭 보존.

### 문서 정합화 + 우선순위 재정렬 (2026-06-23, DECISIONS D25)
- [x] doc/09~17 의 dev 항목·후속 전수 추출 → TASKS 교차대조(누락 0). 미반영 후속 4건 추가(cross-scan recency·Active/Zombie param 노출·scan-status total·파라미터 중앙 API 확장 묶음) + HA cross-instance·warnings 채널 seam 메모
- [x] 완료된 "API 점수화 모델" 섹션 Done 이동, 보류 섹션 response_type_api 중복 제거
- [x] TODO 우선순위 재배열(P1 자체기능→P2 품질→P3 운영→P4 외부연동→보류) + 항목 간 의존 메모 + 상단 '설계문서↔TASKS 매핑' 표

### API 점수화 코어 — 점수 모델·게이트·프로파일 (2026-06-22, doc/08 / DECISIONS D15) — 린 채택
- [x] 가중치 보정: api.weble.net(API) vs dreampark(웹) 실데이터 → html penalty 제거, host_api_subdomain+cors_preflight 추가, static 강화. 분리 마진 0.82 vs 0.27 (08 §8)
- [x] `ApiScorer` — 가용 신호 가산식 점수(clamp 0..1), Classifier 앞단 게이트 (보정 weight)
- [x] 신호 추출: path shape(api/version/id/graphql/machine), write_method, query, non_browser_ua, host_api_subdomain, cors_preflight(OPTIONS sibling), static penalty(확장자/library), repeat_bonus (html penalty 미사용)
- [x] api_confidence(후보성) vs shadow/zombie confidence(실재성) 역할 분리 — Classifier 게이트 후 매칭/분류
- [x] `min_api_confidence` 게이트 → 미달 unmatched 는 보고 안 함, OPTIONS 는 CORS 신호로만(미보고)
- [x] 기존 `EndpointKindClassifier`(static/web_page)를 점수 penalty 입력으로 흡수
- [x] 프로파일 HIGH/MIDDLE/LOW preset (threshold+weights)

### response_type_api 양성 가중치 — $type API성 신호 채택 (2026-06-23, doc/17 / DECISIONS D24) — tests=237 green
- [x] `ApiScorer.Weights` 14번째 가중치 `responseTypeApi`(MIDDLE 0.25/HIGH 0.18/LOW 0.32, §9 보정전 1차값) + `WEIGHT_KEYS` 14 + `applyOverrides` 반영
- [x] `score()` 공통 섹션 `endpointKind==API_CANDIDATE → += responseTypeApi`(양성-only 비대칭, document/UNKNOWN/STATIC/부재 무가산·무감점)
- [x] 기존 `EndpointKind.API_CANDIDATE`($type∈{xhr,fetch,json,api,ajax} dominant) 재사용 — 신규 필드/Acc 불요. customWeights 자동 수용(resolver/DTO/controller 무변경)
> 08 §9 보류 사유($type taxonomy 불확실·document 트랩) 를 양성-only + 보수적 집합으로 해소(보류→채택). 무회귀: 비-API endpoint 무변경, API_CANDIDATE만 상승(보류 해제 목적).

### 설계 (2026-06-22)
- [x] 설계 문서 00~07 (개요/아키텍처/파싱·정규화/스펙·Canonical/매칭·분류/Loki수집/구현스택/MSA연동)
- [x] WAAP API Discovery 개념 정리, nginx 로그 가용/불가 항목 구분
- [x] Shadow/Zombie 정의 (문서 업로드 기반), 3종 포맷 선정(OpenAPI/Postman/CSV)
- [x] endpoint_kind 설계 (web_page vs API, 비대칭 증거 → 이후 `$type` 기반으로 강화)
- [x] Loki 주기 배치 수집 + 운영 부하 보호 설계
- [x] Java+Spring 확정, 상주 서비스 + Spring Batch 배포 모델
- [x] MSA: Worker 서비스 + 중앙 서버 연동(Pull+조건부GET), Spec Store(업로드 시 파싱·영속)

### 구현 (2026-06-22) — 58 tests green, 실 Loki e2e 검증 완료
- [x] Spring Boot 3.3 + Java 21 스캐폴딩 (Gradle, 모듈 구조)
- [x] `LogLineParser` — `^|^` 실로그 24필드, type/referer/request_id 수집
- [x] `PathNormalizer` — 휴리스틱 템플릿 추론
- [x] `EndpointKindClassifier` — `$type` 기반(확장자 1순위), web_page/static/api_candidate
- [x] `InventoryBuilder` — 시그니처 집계(hits/status/분위수/distinct), endpoint_kind 부여
- [x] `SpecFormatDetector` + `OpenApiSpecParser`(swagger-parser) + `SpecStore`(업로드 파싱·버전·Canonical 영속)
- [x] `EndpointMatcher` — 템플릿→정규식, 버킷 인덱스, specificity 우선순위, host-agnostic
- [x] `Classifier` — Shadow/Zombie/Active/Unused/WebPage 2-pass + Shadow 신뢰도, STATIC은 Shadow 제외
- [x] `ReportBuilder` + `EtagUtil` — 요약 집계 + 내용 기반 ETag(generatedAt 제외)
- [x] `DiscoveryJobService` — analyze 파이프라인 + runScan(watermark 증분) + request_id dedup
- [x] `LokiClient` — query_range 윈도우분할·페이지네이션·동시성·스로틀·429 백오프
- [x] REST API — DomainController(CRUD), SpecController(업로드/조회), ScanController(scan-status/result 조건부GET/scan), HostQueryController(hostname→domains, 온디맨드 query)
- [x] 내부 DB — H2(dev)/PostgreSQL(prod), 엔티티 DomainConfig/SpecRecord/ScanResult/Watermark
- [x] watermark 증분 + request_id dedup
- [x] 실 Loki(192.168.8.100:3200) end-to-end 호출 검증 (+분류 버그 1건 수정)

### explicit-hint 매처 + 매처 설정 (2026-06-22, doc/09 / DECISIONS D16) — tests=110 green
- [x] explicit hint 모드(`api_path_prefixes`/`api_path_regexes`) — `ApiScorer` explicit-hint 분기(pathHint weight, 내장 path-shape 비활성)
- [x] 매처 설정: `MatcherConfig`(prefixes/regexes/exclude + `include_web_forms` + NONE + merge 전역∪도메인) + `ApiHintMatcher`(세그먼트경계 prefix·full-match regex·컴파일 캐시·개수/길이 상한·비공백/'/'시작 검증·ReDoS deadline 50ms)
- [x] 게이트 `ApiScorer.evaluate→Gate`(exclude→hint admit→web-form→score), Classifier ADMIT만 Shadow, 하위호환(2-arg score/3-arg classify→NONE 위임)

### 분류 설정 DB 저장 + effective 병합 (2026-06-23, doc/10 / DECISIONS D17) — tests=147 green
- [x] 설정 저장: 전역 `ClassificationConfig`(단일 PK=1L) + 도메인 `DomainClassificationConfig`(host PK) 엔티티/리포지토리. `@Lob String` JSON(매처/custom weights)+`Double`(threshold), JSONB 미사용(H2/PG 이식)
- [x] `ClassificationProfile`(HIGH/MIDDLE/LOW/CUSTOM) + `ApiScorer`(Weights ctor/weights/presetWeights/applyOverrides·값검증) + `EffectiveClassificationResolver`(host→weights+matcher+scorer+hints 병합)
- [x] 병합(threshold 도메인>전역>preset, CUSTOM weights merge, matcher 전역∪도메인) + 무회귀(부재/seed=MIDDLE+NONE, 억제 opt-in) + fail-fast(손상 JSON·unknown 키·범위/비유한 reject) + Classifier 5-arg + DiscoveryJobService 배선

### 분류 설정 중앙 REST API + effective 캐시 (2026-06-23, doc/11 / DECISIONS D18) — tests=164 green
- [x] 중앙 API: `GET/PUT /api/v1/classification`(전역) + `GET/PUT /api/v1/domains/{host}/classification`(도메인 override+effective). `ClassificationController` + `ClassificationDtos`(5 record, MatcherConfig/Weights 재사용)
- [x] 쓰기 검증→400(저장 전 validateThreshold/validateWeightOverrides/ApiHintMatcher, 컨트롤러-로컬 `@ExceptionHandler`) + 저장 손상→500(resolver IAE→ISE 래핑 + `@ExceptionHandler(ISE)`) + 부재(전역→default/도메인→effective)/미등록 404
- [x] effective 캐시 활성화: `EffectiveClassificationResolver` `ConcurrentHashMap`+`computeIfAbsent`, PUT 시 `invalidate(host)`/`invalidateAll()`, poisoning 없음. 스캔경로 무변경(resolve 캐시 자동 경유)

### non_api dropped observation 메트릭 (2026-06-23, doc/12 / DECISIONS D19) — tests=167 green
- [x] `Classifier.classifyWithMetrics→ClassificationResult`: 게이트 DROP_* 사유별 집계(excluded/webForm/lowScore), default→fail-fast. 기존 `classify→List` 오버로드 위임 보존(하위호환)
- [x] `model/DroppedNonApi`(excluded/webForm/lowScore + `@JsonProperty total` 파생) + `DiscoveryReport` top-level `droppedNonApi`(가산적·항상 non-null) + `ReportBuilder` 전달
- [x] `DiscoveryJobService` classifyWithMetrics 전환 + ETag 입력에 droppedNonApi 포함(분포 변화 반영, 304 버그 방지). 카운트=non-OPTIONS·spec 미매칭·DROP_*. ScanResult 스키마 무변경

### 정규화 고카디널리티 방지 — T1 통계승격+상한 / T2 param 후보 / T3 sensitive (2026-06-23, doc/13 / DECISIONS D20) — tests=184 green
- [x] T1 통계 `{var}` 승격(`CardinalityNormalizer`: distinct≥20·ratio≥0.3·수렴≥0.7+형제 재병합) + 상한(host template 5000 / endpoint query param 50, 초과 drop) → `DroppedByLimit`
- [x] T1 = doc/02 §3.3 "통계적 정규화 보정 3단계"와 동일 알고리즘 → 그 항목도 함께 커버(클러스터링+카디널리티→slug 변수 추론)
- [x] T2 param 후보: `queryKeys→queryParams`(값 폐기·`ValueLenBucket` 길이버킷만) + `ParamCandidates(query/path)` → `Finding.Shadow.params` 노출, `ParamCandidateExtractor`(per-endpoint 상한)
- [x] T3 sensitive: `SensitiveKeyMatcher`(@ConfigurationProperties yml, 대소문자무시) — 키이름+flag 보존·값/버킷 억제(REDACTED, 보안신호)
- [x] 배선: `DiscoveryReport` top-level `droppedByLimit`+ETag 포함, `InventoryBuilder.buildWithLimits`(build→위임 하위호환), `Normalization/SensitiveKeyProperties`

### 스펙 파서 Postman/CSV 실구현 + 공유 정규화 (2026-06-23, doc/14 / DECISIONS D21) — tests=205 green
- [x] `PostmanSpecParser` 실구현 — Jackson 트리 item DFS(폴더 deprecated 전파), url object/string, `:var`/`{{var}}`→`{var}`, host 변수→null, `[DEPRECATED]`/`(deprecated)`/description, sourceRef
- [x] `CsvSpecParser` 실구현 — univocity 헤더검증(method/path 필수→fatal), deprecated 토큰(true/false/1/0/y/n/yes/no), BOM/따옴표, 불량행 skip+warn, `:var`→`{var}`
- [x] 공유 `SpecNormalize`(template/host)·`SpecCanonicalizer`(dedupe+deprecated OR+안정정렬, SpecStore.upload 전 포맷 균일) + SpecFormatDetector `schema.postman.com`. 신규 의존성 0, 시그니처 무변경
- [x] 3종 포맷 Canonical 동일성 테스트(`ThreeFormatEquivalenceTest`) — (method,host,template,deprecated,version) 동일(sourceRef 제외). 품질 항목 충족

### 매처 캐시 무효화 (2026-06-23, doc/15 / DECISIONS D22) — tests=212 green
- [x] `match/EndpointMatcherCache`(@Component) — `ConcurrentHashMap<host,VersionedMatcher(specVersion,matcher)>`, (host,specVersion) 키·host당 1슬롯 → stale 구조적 불가·무누수·poisoning-free(`compute` per-host 직렬화)
- [x] `SpecStore.upload` save 후 `invalidate(host)`, `DiscoveryJobService.analyze` `new EndpointMatcher`→`matcherCache.get(host,specVersion,supplier)`(specVersion=0 균일)
- [x] 순환 회피(캐시 무의존·build supplier 호출측 공급), 불변 매처 공유 안전. 무회귀(동일 spec→동일 matcher→findings/ETag 불변)

### 버전 기반 Zombie 추정 + Zombie severity (2026-06-23, doc/16 / DECISIONS D23) — tests=230 green
- [x] 버전 Zombie 추정: `VersionZombieInference`(첫 `^v(\d+)$` 버전·resourceKey {V} 페어링, 그룹 active Vmax 미만 active→추정 Zombie confidence 0.6·estimated, parseInt 오버플로 비버전 폴백)
- [x] Zombie severity: `ZombieSeverity`(결정적 score=0.5·hits(log)+0.3·2xx비율+0.2·span(log)→HIGH/MED/LOW, 외부 시계 미사용, 모든 Zombie 적용), `model/Severity`(+@JsonProperty band 파생)·`SeverityBand`
- [x] `Finding.Zombie` severity+estimated 가산, `Classifier` observedSpecKeys Set→Map<Evidence>(host-agnostic 합산), 명시 1.0/추정 0.6. confidence↔severity 직교. 무회귀(명시 Zombie 1.0 보존·비버전 spec 현행)
