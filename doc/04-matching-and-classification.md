# 매칭 엔진과 Shadow/Zombie 분류

컴포넌트 (D) Matching Engine, (E) Classifier 의 상세 설계.
**문서 기반 Shadow/Zombie 탐지의 핵심 로직.**

> **전제(doc/08)**: Classifier 앞단에 **ApiScorer 게이트**가 있다. discovered endpoint 의
> API 후보 점수가 `min_api_confidence` 미만이면 분류 대상에서 제외(`dropped not_api`)된다.
> 즉 아래 매칭/분류는 **API 후보로 통과한 endpoint** 에만 적용된다.
> 기존 endpoint_kind(static/web_page) 범주 제외는 점수 모델의 감점 신호로 흡수된다.

## 1. 매처 컴파일 (문서 템플릿 → 매처)

집합 S(CanonicalEndpoint)의 각 템플릿을 빠른 매칭용 구조로 변환.

### 1.1 템플릿 → 정규식
- `/users/{id}` → `^/users/(?<id>[^/]+)$`
- `/v1/orders/{orderId}/items` → `^/v1/orders/(?<orderId>[^/]+)/items$`
- 와일드카드/캐치올(OpenAPI엔 없음, 일부 도구 `{proxy+}`) → `(?<proxy>.+)$` 로 처리(옵션).

### 1.2 인덱싱(성능)
concrete path 마다 전체 정규식을 순회하면 O(D×S). 다음으로 가지치기.
- **(method, host, 세그먼트 개수)** 3중 키로 매처 버킷팅.
- host=null(host-agnostic) 매처는 모든 host 버킷에 공유 등록.
- 매칭 시 같은 버킷 후보만 정규식 평가 → 실제 비교량 대폭 축소.
- 정적 prefix 가 같은 템플릿은 trie 로 추가 가지치기 가능(선택 최적화).

## 2. 매칭 규칙

concrete request (method, host, raw_path) 를 매처에 질의.

1. method 일치 필수.
2. host 일치 또는 매처가 host-agnostic.
3. path 정규식 일치.
4. **다중 매칭 시 우선순위(specificity)** — 라우팅 관례와 동일.
   - 정적 세그먼트 > 변수 세그먼트. 앞쪽 세그먼트부터 비교.
   - 예: `/users/me` 는 `/users/me`(정적)와 `/users/{id}`(변수) 둘 다 매칭되지만
     **정적 템플릿이 승리**. 즉 `/users/me` 는 별도 엔드포인트로 인식.
   - 동률이면 변수 개수 적은 쪽 > 더 긴 정적 prefix 쪽 우선.
5. 부가 정책(설정):
   - 후행 슬래시 무시 여부.
   - 대소문자 민감도(path 는 기본 case-sensitive).
   - base path prefix strip(03 문서 §2.2와 연동).

## 3. 분류 매트릭스 (Classifier)

매칭 결과를 두 방향에서 종합한다.

### 3.1 문서 측(S) 순회 — Zombie/Unused/Active
각 문서 엔드포인트 s 에 대해 "트래픽에서 매칭됐는가(observed)" 판정.

| s.deprecated | observed(트래픽 매칭) | 분류 | 의미/조치 |
|---|---|---|---|
| true | **true** | **Zombie** | 폐기 예정인데 여전히 사용 중 → 마이그레이션/차단 검토 |
| true | false | Deprecated-clean | 정상적으로 안 쓰임 → 문서에서 제거 가능 |
| false | true | **Active** | 정상 |
| false | false | **Unused** | 문서엔 있으나 트래픽 없음 → 미배포/오문서 검토 |

### 3.2 관찰 측(D) 순회 — Shadow
각 관찰 시그니처 d 에 대해 "문서에 매칭되는가" 판정.

| 문서 매칭 | 실재성(§4) | endpoint_kind | 분류 |
|---|---|---|---|
| 매칭됨 | — | — | (3.1에서 이미 Active/Zombie로 분류됨) |
| 매칭 안 됨 | 실재 신호 있음 | `web_page` (신호 active) | **undocumented_web_page** (비 API) |
| **매칭 안 됨** | 실재 신호 있음 | `unknown`/`api_candidate`/dormant | **Shadow** (신뢰도 높음) |
| 매칭 안 됨 | 404-only 등 비실재 | — | 제외 또는 Shadow(신뢰도 낮음) |

### 3.3 한눈에
```
                 observed in traffic?
                 ┌────────────┬────────────┐
                 │    YES     │     NO     │
 ┌───────────────┼────────────┼────────────┤
 │ in spec,      │  Active    │  Unused    │
 │  not deprecated│           │            │
 ├───────────────┼────────────┼────────────┤
 │ in spec,      │  ZOMBIE    │ Deprecated │
 │  deprecated    │  ⚠         │  -clean    │
 ├───────────────┼────────────┼────────────┤
 │ not in spec   │  SHADOW ⚠  │   (N/A)    │
 └───────────────┴────────────┴────────────┘
```

## 4. 신뢰도 점수 (false positive 억제)

Shadow/Zombie 는 오탐 비용이 크므로 0~1 신뢰도를 부여한다.

### 4.1 Shadow 신뢰도
정규화 추론 오류와 스캐너 노이즈를 흡수.
- 기본 1.0 에서 감산:
  - `status_dist` 가 거의 전부 404/4xx → **-0.7** (실재 엔드포인트 아닐 가능성).
  - hits 매우 낮음(임계치 미만, 예 <5) → **-0.2**.
  - 단일 클라이언트 + 스캐너성 UA(예: `sqlmap`, `nikto`, 빈 UA) → **-0.2**.
  - 템플릿이 통계 보정(`inferred`, 과병합 위험)으로 생성됨 → **-0.1**.
- 가산:
  - 2xx 비율 높음 + 다수 distinct 클라이언트 + 지속 트래픽 → 1.0 유지.
- 임계치(예 < 0.5)는 리포트에 `low_confidence` 로 분리.

### 4.1.1 endpoint_kind 반영 (web_page 분리) — 비대칭 적용
스펙(OpenAPI/Postman/CSV)은 **API** 를 기술한다. 따라서 스펙에 없는 발견 경로가 사실은
HTML 페이지(로그인 화면, 어드민 UI 등)면 "API Shadow" 가 아니라 별도로 다뤄야 한다.
근거 신호는 02 문서 §5의 `endpoint_kind`(`$http_referer` 부모-자식 관계 기반, best-effort).

**적용 규칙(부재는 절대 감점하지 않음 — 02 §5.1 비대칭 원칙):**
- `endpoint_kind = web_page` (kind_confidence 충분) → API-Shadow 가 아니라
  **별도 분류 `undocumented_web_page` (비 API)** 로 이동(또는 정책에 따라 Shadow 신뢰도 대폭 감산).
- `endpoint_kind = unknown` → **가감 없음.** 신호 부재를 API 증거로도, 페널티로도 쓰지 않는다.
- `endpoint_kind = api_candidate` (약한 양성) → Shadow 신뢰도 소폭 가산(예 +0.05) 정도로만.
- **신호가 dormant(02 §5.4 커버리지 게이트 미통과)면 이 항목 자체를 적용하지 않는다.**

> 효과는 **Shadow 정밀도 개선**에 한정된다. **Zombie 는 문서의 `deprecated` 기반**이라 endpoint_kind 와 무관(영향 없음).

### 4.2 Zombie 신뢰도
- 문서에 deprecated 명시 + 트래픽 매칭이면 기본 **1.0**(명확).
- 보조 신호: `last_seen` 이 최근일수록, hits 가 많을수록 우선순위(심각도) 상향.
- 심각도(severity)는 신뢰도와 별개로 "조치 시급성" 으로 제공:
  `severity = f(hits, recency, 2xx비율)`.

## 5. 버전 기반 Zombie 보강 (선택 확장)

문서에 deprecated 표기가 없어도, 버전 prefix 로 Zombie 를 추정.
- 같은 리소스의 상이한 버전 감지: `/v1/orders/{id}` 와 `/v2/orders/{id}` 가 모두 S에 존재.
- 신버전(v2)이 active 인데 구버전(v1)에도 트래픽 → v1 을 **Zombie 후보(추정)** 로 표시.
- 단, deprecated 명시 기반보다 신뢰도를 낮춰(예 0.6) 보고하고 `reason` 에 근거 명시.

## 6. 처리 흐름 (의사코드)

```text
S = load_spec(uploaded_doc)                 # 03 문서
matchers = compile_matchers(S)              # §1

D = build_inventory(parse_logs(nginx_log))  # 02 문서
                                            #  - 정규화는 matchers 우선 사용(스펙 매칭)
findings = []

# 관찰 측: Shadow
for d in D:
    s = match(d, matchers)                  # §2 우선순위 규칙
    if s is None:
        conf = shadow_confidence(d)         # §4.1
        findings.add(Shadow(d, conf))
    else:
        d.matched_spec = s                  # Active/Zombie는 아래에서

# 문서 측: Zombie / Unused / Active
for s in S:
    observed = any(d.matched_spec == s for d in D)
    if s.deprecated and observed:
        findings.add(Zombie(s, evidence=traffic_of(s)))   # §4.2
    elif s.deprecated and not observed:
        findings.add(DeprecatedClean(s))
    elif not s.deprecated and observed:
        findings.add(Active(s))
    else:
        findings.add(Unused(s))

# (선택) 버전 기반 Zombie 보강 §5
findings += version_zombie_inference(S, D)

report = summarize(findings)                # 01 문서 §4 스키마
```

## 7. 엣지 케이스

| 케이스 | 처리 |
|---|---|
| host-agnostic 문서(host 미지정) | host 무시하고 method+path 로만 매칭 |
| 프록시가 base path 를 strip | base path strip 옵션으로 템플릿 조정(03 §2.2) |
| 동일 path, 다른 method | 별개 엔드포인트(method 포함 시그니처) |
| `/users/me` vs `/users/{id}` | specificity 우선순위로 정적 승리(§2.4) |
| 통계 보정 과병합 | 신뢰도 감산 + `inferred` 표기, 검토 대상 |
| 404-only 탐침 경로 | 실재성 필터로 Shadow 제외/저신뢰(§4.1) |
| 구버전 트래픽(deprecated 미표기) | 버전 기반 Zombie 추정(§5, 저신뢰) |
| 미문서 경로가 사실 HTML 페이지 | endpoint_kind=web_page 면 `undocumented_web_page` 로 분리(§4.1.1) |
| 정적 자원이 프록시를 안 탐(CDN 오프로드) | endpoint_kind 신호 dormant → 적용 안 함, Shadow는 부재로 감점 안 함(02 §5.4) |

### 7.1 회귀 테스트 매핑 (DECISIONS D37)

§7 케이스별 **잠금 불변식 ↔ 잠그는 테스트 ↔ 상태**. 신규 테스트는 기존 클래스에 `// doc/04 §7 case N` 태그로 추가(중복 회피).

| §7 케이스 | 계층 | 잠금 불변식 | 잠그는 테스트 | 상태 |
|---|---|---|---|---|
| host-agnostic | matcher | host=null 템플릿=모든 host 매칭, host-specific=자기 host 만 | `EndpointMatcherTest.hostAgnosticMatchesAnyHost/hostSpecificMatchesOnlyItsHost` | ✅ |
| base path strip | (미구현) | 프록시 strip 시 템플릿/관측 정합 | — | ⚠️ **F1 미구현(플래그)** |
| 동일 path 다른 method | matcher | method 포함 시그니처 → 별개 매칭 | `EndpointMatcherTest.methodMustMatch`(mismatch) + `sameTemplateDistinctMethodsMatchSeparately`(case3) | ✅ |
| `/users/me` vs `{id}` | matcher | 정적 > 변수, **앞 세그먼트 우선** | `EndpointMatcherTest.staticSegmentWinsOverVariable` + `specificityFrontSegmentPriorityAndTie`(case4 앞세그·동률) | ✅ |
| 통계 과병합 inferred | classify | `INFERRED` → shadowConfidence −0.1 + `inferred` 표기 | `ClassifierTest.shadowConfidenceDropsForFourxxOnly`(번들) + `inferredOnlyShadowLosesExactlyPointOneConfidence`(case5 −0.1 격리) | ✅ |
| 404-only 탐침 | inventory/classify | 100%-404 INFERRED hard-drop / mostly-4xx soft −0.7 | `InventoryBuilderTest`·`ClassifierTest`(doc/19) | ✅ |
| 구버전 트래픽 | classify | 신버전 active + 구버전 트래픽 → 추정 Zombie 0.6 | `VersionZombieInferenceTest`(doc/16) | ✅ |
| 미문서 HTML | classify | endpoint_kind=WEB_PAGE → `undocumented_web_page`(Shadow 아님) | `ClassifierTest`(§4.1.1) | ✅ |
| CDN dormant | normalize/classify | referer 신호 dormant → 미적용 · Shadow 부재 무감점 | `RefererSignalExtractorTest`(doc/20) | ✅ |

**플래그(현행 미구현/버그 — 테스트로 '고정' 금지, 확인 필요)**:
- **F1 base-path-strip**: doc/03 §2.2·§7 에 옵션 명시되나 **미구현**. `OpenApiSpecParser` 가 `basePath` 를 템플릿에 join → 프록시가 base path 를 strip 한 관측은 basePath-join 템플릿과 불일치 → **false Shadow**. 회귀 테스트 아님 → 한계 문서화/옵션 구현 검토(후속).
- **F2 catch-all `{var+}`**: `EndpointMatcher` 에 `.+` 분기 존재하나 (a) 어떤 파서도 `{var+}` 미생성(**도달 불가**), (b) `segCount` 버킷팅이 다중 세그먼트 `.+` 매칭을 막음(도달 시 오동작). dead code 정리 vs 의도 확인(후속).
