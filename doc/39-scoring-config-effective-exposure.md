# 39. 스코어링 정책 조회 강화 — effective 노출 + threshold 분리 + 신호 설명(ko/en)

> 관련: doc/08(점수화·프로파일)·doc/10(분류설정 저장)·doc/11(분류설정 REST)·doc/34(rationale 노출)·doc/35 A2(부분 weight 편집). 결정 = DECISIONS **D78**.

## 1. 배경 — 조회/수정은 이미 있으나 "실적용 값"이 안 보인다

API 판단(분류)의 스코어링 정책은 **DB 로 운영**되고 REST 로 즉시 편집된다(재배포 불요).

- 저장: `classification_config`(전역, id=1) + `domain_classification_config`(도메인별). profile·thresholdOverride·customWeights(JSON)·matcher(JSON).
- 편집: `PUT /classification`·`PUT /domains/{host}/classification`(전체 교체) / `PATCH .../weights`(부분, doc/35 A2). 저장 후 `EffectiveClassificationResolver` 캐시 무효화 → **다음 분류부터 즉시 적용**.
- 병합: 도메인 > 전역 > preset(HIGH/MIDDLE/LOW). `buildFrom` 이 effective(profile·14 weight·threshold·matcher)를 산출.

두 가지 공백이 있다.

1. **전역 GET(`GET /classification`)이 저장값만 반환.** profile=MIDDLE·customWeights=null 이면 **실제 적용 중인 14 weight·threshold 값이 안 보인다.** 도메인 GET 은 이미 `effective` 블록으로 실적용값을 주는데 전역만 비대칭.
2. **threshold 가 weights 안에 매몰.** 쓸 때는 최상위 `thresholdOverride` 로 따로 받는데, effective 조회에서는 `weights` 레코드 안(16번째 필드)에 묻혀 있다. threshold 는 가산 신호가 아니라 **판정 합격선**(score ≥ threshold → API 후보)이라 성격이 다르고, 한 값이 전 엔드포인트의 API/비-API 경계를 통째로 이동시키는 최대 영향 노브다.

## 2. 결정 (D78)

1. **전역 GET 에 `effective` 블록 추가** — 도메인 GET 과 동형. preset 이어도 실제 적용 중인 14 weight·threshold·matcher 를 노출.
2. **threshold 를 effective 최상위로 분리**(전역·도메인 동일). `repeatMinCount`(override 불가 상수)도 함께 최상위 노출. `weights` 는 override 가능한 **14키 맵**(PUT/PATCH 바디와 동일 형태 → 읽기/쓰기 대칭).
3. **`descriptions`(ko/en) 조회 응답에 첨부** — 14 weight + threshold + repeatMinCount 각 신호의 의미를 한글·영어로. 정적 사전이라 전역·도메인 동일. 값 맵은 순수 숫자 유지(쓰기 바디 호환), 설명은 별도 블록.
4. **CLI 는 만들지 않음(API-only).** 분류설정용 CLI 가 없고, CLI 는 서버와 별도 원샷 프로세스라 실행 중 서버 캐시(즉시적용)를 건드릴 수 없어 부적합(사용자 확정).

## 3. 설계 상세

### 3.1 effective 뷰 재정의 (`ClassificationDtos.EffectiveView`)

기존 `EffectiveView(profile, ApiScorer.Weights weights, matcher)` — weights 레코드 안에 threshold·repeatMinCount 매몰 → 다음으로 교체.

```
EffectiveView(
    ClassificationProfile profile,
    String weightsSource,          // "preset" | "custom"(CUSTOM)
    double threshold,              // ★ 최상위 분리
    int repeatMinCount,            // 읽기전용(override 불가)
    Map<String,Double> weights,    // 14키 맵 = ApiScorer.weightsAsMap
    MatcherConfig matcher)         // 정보 유지(회귀 방지)
```

전역·도메인 조회가 이 **동일한** 뷰를 공유. `EffectiveClassification`(resolver 산출)에서 빌드:
`weightsSource = profile==CUSTOM ? "custom" : "preset"`, `threshold = weights.threshold()`, `weights = weightsAsMap(weights)`.

> ★ `/discovery`·`/domains` 상세가 쓰는 별도 `model.EffectiveClassificationView`(profile·threshold·weightsSource·weights[14])는 **미터치**. 이번 변경은 `/classification` 계열에 국한.

### 3.2 응답 뷰에 effective·descriptions 추가

- `GlobalClassificationView` — 기존 저장값 5필드 + `effective`(EffectiveView) + `descriptions`(Map).
- `DomainClassificationView` — 기존 `host`·`override`·`effective`(EffectiveView 새 형태) + `descriptions`(Map).
- `descriptions` 타입 = `Map<String, ScoringWeightCatalog.Description>`, `Description(String ko, String en)`.

### 3.3 신호 설명 카탈로그 (`ScoringWeightCatalog` 신설)

정적 `Map<String,Description>`(14 weight + threshold + repeatMinCount). 단일 진실원 — 컨트롤러가 참조, 매뉴얼(한글) 설명과 문구 일치. 키 집합은 `ApiScorer.WEIGHT_KEYS ∪ {threshold, repeatMinCount}`.

### 3.4 컨트롤러 배선 (`ClassificationController`)

- `toGlobalView(config)` → `effective = toEffectiveView(resolver.resolveGlobal())`, `descriptions = ScoringWeightCatalog.ALL`. (`resolveGlobal()` 은 캐시 미사용·항상 최신 → 저장·invalidate 후 신선.)
- `toDomainView(host)` → 기존 override + `effective = toEffectiveView(resolver.resolve(host))` + descriptions.
- `toEffectiveView(EffectiveClassification)` 헬퍼 1개.
- GET/PUT/PATCH 6개 핸들러의 응답이 자동으로 새 형태(핸들러 로직·검증·캐시 무효화 불변).

## 4. API 입출력 형태

`GET /api/v1/classification` (전역, 새 형태 — MIDDLE 기준)

```json
{
  "profile": "MIDDLE", "thresholdOverride": null, "customWeights": null,
  "matcher": null, "updatedAt": null,
  "effective": {
    "profile": "MIDDLE", "weightsSource": "preset",
    "threshold": 0.70, "repeatMinCount": 3,
    "weights": {
      "hostApiSubdomain": 0.40, "corsPreflight": 0.30, "apiSegment": 0.55,
      "graphqlSegment": 0.55, "versionSegment": 0.26, "pathIdSegment": 0.15,
      "machineEndpoint": 0.20, "writeMethod": 0.34, "query": 0.12,
      "nonBrowserUa": 0.24, "staticAssetPenalty": -0.60, "repeatBonus": 0.12,
      "pathHint": 0.55, "responseTypeApi": 0.25
    },
    "matcher": null
  },
  "descriptions": { "threshold": { "ko": "...", "en": "..." }, "...": {} }
}
```

`GET /api/v1/domains/{host}/classification` — `override`(도메인 저장값, 없으면 전부 null=전역 상속) + `effective`(위 형태) + `descriptions`.

쓰기(`PUT`·`PATCH`) 바디는 **불변**. `descriptions` 는 응답 전용(입력 무시).

## 5. 무회귀·경계

- **스코어링/분류 로직 불변** — 조회 노출 형태만 변경. `ApiScorer`·`buildFrom`·게이트 미터치.
- **쓰기 계약 불변** — `ClassificationUpsert`(PUT 바디)·PATCH weight 맵·검증(400)·404·즉시적용(캐시 무효화) 그대로.
- **도메인 GET `effective` 형태 변경**(threshold 최상위·14키 맵·weightsSource·repeatMinCount 추가) — 정보성 블록이고 쓰기 미영향. 매뉴얼(api-rest-manual) 갱신 대상. 중앙/외부 소비자 영향은 additive+구조변경 혼재라 매뉴얼 명시.
- **`/discovery` effective(별도 DTO) 미터치** — 이번 범위 밖.
- `descriptions` 는 정적 상수 — 요청마다 동일, 계산비용 0.

## 6. 테스트 계획 (상세 dev 체크리스트는 TASKS)

- 전역 GET: preset(MIDDLE)에서 effective.threshold=0.70·weights 14키·weightsSource="preset"·descriptions 존재.
- 전역 PUT(CUSTOM)/PATCH 후 effective 반영(threshold override·부분 weight 유지).
- 도메인 GET: override 상속(전부 null) + effective 전역 반영, 도메인 override 시 병합 반영.
- descriptions 키 = 14 weight + threshold + repeatMinCount, ko/en 비어있지 않음.
- 무회귀: 쓰기 400/404·즉시적용 기존 테스트 유지.
