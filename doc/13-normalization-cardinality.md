# 정규화 고카디널리티 방지 (T1 통계 승격+상한 / T2 param 후보 / T3 sensitive) — 통합 설계

> 브랜치 `feature/normalization-cardinality-control`. 세 항목 모두 정규화/인벤토리 계층 → 1 PR.
> 내부 커밋 순서 권장: 공유모델/설정 → T1 → T2 → T3 → 보고/배선. 근거 결정은 doc/DECISIONS.md **D20**.
> 연계: doc/02 §1.2(query 키만 보존)·§3.3(통계 보정)·§3.4(false merge), doc/12(DroppedNonApi 노출 패턴), doc/07 §8(ETag).

## 0. 현 상태 / 가용 신호

- 정규화: `PathNormalizer`(세그먼트 휴리스틱 `{uuid}/{id}/{token}/{date}`), `InventoryBuilder`(시그니처 Acc 집계).
  **통계 보정(doc/02 §3.3) 미구현.**
- query: 파서가 **키만 보존, 값 폐기**(doc/02 §1.2). `ParsedRequest.queryKeys: List<String>`. 값 길이 미수집.
- 노출 패턴: `DroppedNonApi`(record + `@JsonProperty total()`) → `DiscoveryReport` top-level + ETag 포함(doc/12). 재사용.
- **참고**: T1 통계 `{var}` 승격 = doc/02 §3.3 "통계적 정규화 보정 3단계"와 동일 알고리즘 → TASKS 별도 항목도 사실상 커버(팀 표시 권고).

## 1. (T1) 통계적 {var} 승격 + 상한 + dropped_limit

### 1.1 승격 (2차 패스, 집계 후)
휴리스틱(1차)이 못 잡은 고카디널리티 정적 세그먼트를 `{var}` 로 수렴. 입력은 1차 산출 템플릿 집합
(원본 path 저장 불필요 — 형제 템플릿의 위치별 distinct 정적값으로 카디널리티 산출).

- 클러스터: (method, host, 세그먼트수, 위치 i 제외 나머지 세그먼트) 동일군.
- 승격 조건(전부 충족):
  - `distinct_at_i / cluster_requests ≥ statVarRatio(0.3)` (doc/02 §3.3)
  - `distinct_at_i ≥ statVarMinDistinct(20)` (소표본 오승격 방지)
  - 승격 후 수렴 `merged_hits / cluster_hits ≥ statVarMinConvergence(0.7)` (false merge 방지, doc/02 §3.4)
- 동작: 위치 i → `{var}`, 형제 Acc 재병합(hits/metrics 합산), `templateSource=INFERRED`(Shadow 신뢰도 -0.1 기존 적용).
- **무회귀**: 조건 보수적(≥20 distinct)이라 소규모/기존 테스트 입력은 미발동 → 템플릿 동일.

### 1.2 상한 (최후 안전망, 승격 후에도 폭발 시)

| 상한 | 기본값 | 초과 처리 |
|---|---|---|
| host 당 distinct template | 5000 | hits 낮은 순 초과분 drop → `dropped_limit.templates++` |
| endpoint 당 distinct query param | 50 | 초과 param drop → `dropped_limit.params++` |

- 근거: 실 API 는 수백~저수천 endpoint, param 수십개 → 넉넉한 헤드룸. 초과 = 비정상 카디널리티(랜덤 경로/스캐너).
- **노출**: `model/DroppedByLimit(int templates, int params)` + `@JsonProperty total()` → `DiscoveryReport` top-level
  (`droppedNonApi` 와 형제, DroppedNonApi 패턴 재사용). ETag 입력 포함(콘텐츠 일관).
- **조용한 누락 금지** — 카운트로 항상 노출(운영자가 카디널리티 폭발 인지).

## 2. (T2) 파라미터 후보 (body 없음 → query/path)

### 2.1 query param (파스 확장, privacy-preserving)
파서가 `key=value` 분리 시 **값 길이만 버킷화하고 값은 폐기**.
- `ParsedRequest.queryKeys`(List<String>) → **`queryParams: List<QueryParamObs(name, ValueLenBucket)>`** 교체
  (내부 필드, 외부 노출 없음 → 무회귀). `hadQuery = !queryParams.isEmpty()`(기존 신호 보존).
- `ValueLenBucket {NONE, S(1-8), M(9-32), L(33-128), XL(129+)}` — 값 자체 미저장, 길이 버킷만.
- 인벤토리 집계: endpoint 별 param name → presence count + 관측된 버킷 집합(≤5, 카디널리티 안전).

### 2.2 path param 후보
템플릿 변수 세그먼트(`{id}/{uuid}/{token}/{date}/{var}`) 열거 → `PathParam(position, token)`.
T1 의 `{var}` 가 곧 path param 후보(저신뢰). 세그먼트수로 자연 상한.

### 2.3 저장 구조
`DiscoveredEndpoint.params: ParamCandidates(query: List<QueryParam>, path: List<PathParam>)`.
`QueryParam(name, count, lenBuckets, sensitive)`. per-endpoint query param 상한(50, §1.2)·sensitive(§3) 적용 후 저장.

## 3. (T3) sensitive key matcher

### 3.1 매칭
`SensitiveKeyMatcher`(@Component) — 기본 키 목록 + 정규식, 대소문자 무시.
- 기본 키(예): `password/passwd/pwd, token/access_token/refresh_token, secret, apikey/api_key, session/sid,
  authorization/auth, otp/pin/cvv, ssn, card/cardno`.
- 기본 정규식(예): `.*(passw|secret|token|apikey).*`.

### 3.2 정책 (보안도구 관점 — 린 결정)
**키 이름 보존 + `sensitive=true` 플래그 + value-len 버킷 억제(REDACTED).**
- 근거: WAAP 보안 디스커버리다 — "endpoint 가 `token`/`password`/`ssn` 쿼리 파라미터를 받는다"는 **고가치 보안 신호**
  (쿼리스트링 내 시크릿 = finding 후보)라 **숨기지 않고 노출**. 값은 이미 파스 폐기, 추가로 **길이 버킷도 억제**(길이가 값 특성 누출 가능).
- 더 엄격한 **완전 제외(이름+버킷 drop)** 는 옵션 — v1 기본은 flag+버킷억제.

### 3.3 설정 저장 연계 (린 판단)
**이번 범위는 `@ConfigurationProperties`(application.yml) 만. DB 설정 저장/REST 제외.**
- 근거: sensitive 목록·상한은 대체로 **정적 인프라 정책**(D12: 정적→yml, 동적→DB). yml 기본값 → ConfigMap override 가능.
  도메인별 override·중앙 API 는 분류설정(doc/10/11) 패턴으로 **후속**. 이번 PR 범위 폭주 방지.
- `apidiscover.normalization.*`(상한·승격 임계·버킷 경계) + `apidiscover.sensitive-keys.{names,patterns}` 기본값 내장.

## 4. 상호작용 / 순서 / 하위호환

### 4.1 파이프라인 (InventoryBuilder 오케스트레이션)
```text
1. 파스: ParsedRequest.queryParams(name+lenBucket)              [T2 파스]
2. 1차 템플릿(spec/휴리스틱) + Acc 집계(query param obs 누적)    [기존 + T2]
3. T1 통계 {var} 승격 → 형제 Acc 재병합                         [T1]
4. T1 상한: host template cap / endpoint param cap → DroppedByLimit  [T1]
5. T2 param 후보 생성(query presence+버킷, path 변수 세그먼트)   [T2]
6. T3 sensitive: query 후보에 flag + 버킷 억제                   [T3]
7. DiscoveredEndpoint(+params) 방출 + DroppedByLimit 집계
```
순서 근거: 승격(3)이 템플릿을 **줄이므로** 상한(4) 앞에(병합될 것을 미리 drop 방지). sensitive(6)는 후보 생성 직후·방출 전.

### 4.2 하위호환 / 무회귀
- `queryKeys→queryParams`: 내부 필드만(외부 리포트/REST 미노출) → 외부 무영향. hadQuery 보존.
  (알려진 사용처: LogLineParser 채움, InventoryBuilder hadQuery — dev grep 확인.)
- `DiscoveredEndpoint.params`·`Finding.Shadow.params`·`DiscoveryReport.droppedByLimit`: **가산적**. 기존 소비자 무시 가능.
- 승격 보수적(≥20 distinct) + 상한 높음(5000/50) → 기존 테스트 입력 미발동 = 템플릿/카운트 동일, `DroppedByLimit=(0,0)`.
- ETag 입력에 `droppedByLimit` 추가(콘텐츠 일관, doc/07 §8). param 후보는 Shadow finding 에 실려 findings(이미 ETag)에 포함.
- **노출**: param 후보 = `Finding.Shadow.params`(미문서 endpoint param = 최고가치 보안신호). Active/Zombie 는 스펙에 param 정의 → v1 제외(후속). `droppedByLimit` top-level.

## 5. dev 구현 체크리스트 (22건)

### 공유/설정
- [ ] `config/NormalizationProperties`(@ConfigurationProperties `apidiscover.normalization`: maxTemplatesPerHost=5000,
      maxQueryParamsPerEndpoint=50, statVarRatio=0.3, statVarMinDistinct=20, statVarMinConvergence=0.7, valueLenBucketBounds=[8,32,128]) + application.yml 기본값.
- [ ] `model/DroppedByLimit(int templates, int params)` + `@JsonProperty total()`.

### T1
- [ ] `normalize/CardinalityNormalizer` — 통계 {var} 승격(클러스터·ratio≥0.3·distinct≥20·수렴≥0.7) + 상한(host/param)
      → 승격·캡 적용 결과 + `DroppedByLimit` 반환.
- [ ] `InventoryBuilder` 에 패스 3·4 통합(승격 후 상한).

### T2
- [ ] `model/ParsedRequest`: `queryKeys`→`queryParams: List<QueryParamObs>` (+`QueryParamObs(name, ValueLenBucket)`, `enum ValueLenBucket`).
- [ ] `parse/LogLineParser`: query 값 길이 버킷화(값 폐기) → queryParams 채움. 기존 queryKeys 사용처/테스트 갱신(grep 확인).
- [ ] `model/ParamCandidates(query: List<QueryParam>, path: List<PathParam>)` + `QueryParam(name,count,lenBuckets,sensitive)` + `PathParam(position,token)`.
- [ ] `model/DiscoveredEndpoint`: `ParamCandidates params` 필드 추가(가산).
- [ ] `normalize/ParamCandidateExtractor` — Acc query obs + 템플릿 변수세그먼트 → ParamCandidates, per-endpoint param 상한(→DroppedByLimit.params), sensitive 적용.

### T3
- [ ] `config/SensitiveKeyProperties`(`apidiscover.sensitive-keys.{names,patterns}` 기본값 내장).
- [ ] `normalize/SensitiveKeyMatcher`(@Component) — names+regex 대소문자 무시 `isSensitive(key)`. 기본 정책 이름 보존+flag+버킷 억제.

### 보고/배선
- [ ] `model/Finding.Shadow` 에 `ParamCandidates params` 추가(Classifier 가 d.params() 전달).
- [ ] `model/DiscoveryReport` 에 `DroppedByLimit droppedByLimit` top-level 추가, `report/ReportBuilder.build` 파라미터 추가.
- [ ] `normalize/InventoryBuilder`: `InventoryResult(endpoints, DroppedByLimit) buildWithLimits(...)` 추가, `build→List` 위임(하위호환).
- [ ] `batch/DiscoveryJobService.analyze`: buildWithLimits 전환 + droppedByLimit 를 ReportBuilder 전달 + ETag 입력에 추가.

### 테스트
- [ ] `CardinalityNormalizer` — 고카디널리티(≥20 distinct slug) 승격·재병합, 소표본/저비율 미승격(무회귀), 수렴 가드(소형 enum 미승격).
- [ ] 상한 — >5000 템플릿/>50 param 초과분 drop(hits 낮은 순) + DroppedByLimit 카운트.
- [ ] `ParamCandidateExtractor` — query name+presence+버킷, path 변수세그먼트 후보.
- [ ] `SensitiveKeyMatcher` — 기본 키/정규식 flag, 버킷 억제, 비민감 무영향.
- [ ] `LogLineParser` — queryParams name+버킷 정확, **값 미저장** 검증.
- [ ] 하위호환/무회귀 — 기존 inventory 템플릿 동일(미승격), DroppedByLimit=(0,0), 리포트 가산적, ETag 가 droppedByLimit 반영.

## 6. 범위 밖 / 후속

- sensitive 목록·상한의 **도메인별 override·중앙 REST/대시보드**(분류설정 doc/10·11 패턴 재사용).
- Active/Zombie 의 param 노출(스펙 정의 활용).
- distinct/분위수 대용량 근사(HLL/t-digest) — 별도 TASKS 항목.
