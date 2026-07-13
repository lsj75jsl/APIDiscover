# 40. 8.3 로그변수 소비 — 응답 Content-Type + 요청측 API 신호 (개발 계획·핸드오프)

> 상태: **설계·범위·가중치 확정, 구현 미착수.** 다음 세션이 이 문서 + TASKS 서브아이템으로 이어서 구현한다.
> 리뷰 반영(2026-07-13 review): §4 무회귀 증명 스코핑(점수 신호 한정)·응답 CT 오도 완화 가드(§4.3)·§5 재계산 가능 신호 정정·§6 발화 조건 결정 포인트.
> 관련: doc/02(로그 파싱)·doc/08(점수·프로파일)·doc/17(response_type_api)·doc/23(ACRM/M3)·api-discovery-manual §8. 결정 = DECISIONS **D79**.
> 안전 선례 = ACRM(M3/D50): 설정 인덱스 기본 -1 → DORMANT → 현행 100% 무회귀.

## 1. 배경·목표

nginx access log 에 8.1/8.3 권장 변수를 **끝에 append** 하고(순서=스키마, 코어 0~23 보존), 그 값을 판정 로직이 **소비**한다. 8.3 log_format(기존 24필드 + append)에서 **`$server_protocol`·`$upstream_addr` 2개는 제외**(사용자 결정, 효과 ★ 미미). 매뉴얼 §8.3 예시에서도 이 2줄 삭제.

## 2. 범위 (사용자 확정)

| 8.3 변수 | append idx | 소비 | 처리 |
|---|---|---|---|
| `$sent_http_content_type` | 24 | ✅ | **endpoint_kind 결정**(manual 8.2, 오도 CT 완화 가드 = §4.3: 2xx 만 누적·과반·확장자 veto 우선). json/xml/grpc→API, text/html→WEB_PAGE, image/css/js→STATIC. 없으면 기존 $type·경로 폴백 |
| `$content_type`(요청) | 25 | ⬚ | **로깅만, 미소비**(향후 web-form 정밀화용 예약). 파서 인덱스 미부여 → 무시 |
| `$http_accept` | 26 | ✅ | ApiScorer 신호 `acceptJson`(양성 가산) |
| `$http_x_requested_with` | 27 | ✅ | ApiScorer 신호 `xRequestedWith`(양성 가산) |
| `$http_access_control_request_method`(ACRM) | 28 | ✅ | **이미 구현됨**(M3/D50). 코드 불필요 — `apidiscover.parse.acrm-field-index=28` 설정 + 로그 필드로 활성 |
| `$http_origin` | 29 | ✅ | ApiScorer 신호 `originHeader`(양성 가산) |
| `$auth_scheme` | 30 | ✅ | ApiScorer 신호 `authScheme`(양성 가산) |
| ~~`$server_protocol`~~ | — | ❌ | **제외**(사용자) |
| ~~`$upstream_addr`~~ | — | ❌ | **제외**(사용자) |

★ 신규 **스코어링 가중치는 4개**(accept/xhr/origin/auth). `sent_http_content_type` 은 endpoint_kind 로 소비(스칼라 가중치 아님; 원하면 기존 `responseTypeApi` 신호의 더 정확한 소스로도 재사용 — 새 가중치 없음). ACRM 은 기존 preflight 게이트(새 가중치 없음).

### 로그 미수집 상태 = 지금
accept/xhr/origin/auth/sent_ct 는 **현재 nginx 로그에 없다.** nginx log_format 변경 후에야 수집된다. 따라서 지금은 실데이터 가중치 보정 불가 → **a-priori(사전확률) 기반 기본값**을 정하되 **현행 판정을 크게 흔들지 않는 선**으로(사용자 지시).

## 3. 가중치 제안 — 현행 + 신규 (MIDDLE / HIGH / LOW)

> HIGH=엄격(낮음) < MIDDLE < LOW=느슨(높음), 기존 패턴 동일. 점수 = 발화 신호 가중치 합, clamp[0,1]. `score ≥ threshold` → API 후보.

### 현행 14 신호 + 상수 (변경 없음)
| 신호 | MIDDLE | HIGH | LOW |
|---|---|---|---|
| hostApiSubdomain | 0.40 | 0.35 | 0.45 |
| corsPreflight | 0.30 | 0.25 | 0.35 |
| apiSegment | 0.55 | 0.50 | 0.65 |
| graphqlSegment | 0.55 | 0.50 | 0.65 |
| versionSegment | 0.26 | 0.20 | 0.34 |
| pathIdSegment | 0.15 | 0.10 | 0.22 |
| machineEndpoint | 0.20 | 0.12 | 0.28 |
| writeMethod | 0.34 | 0.30 | 0.42 |
| query | 0.12 | 0.06 | 0.18 |
| nonBrowserUa | 0.24 | 0.18 | 0.30 |
| staticAssetPenalty | -0.60 | -0.70 | -0.50 |
| repeatBonus | 0.12 | 0.08 | 0.18 |
| pathHint | 0.55 | 0.50 | 0.65 |
| responseTypeApi | 0.25 | 0.18 | 0.32 |
| repeatMinCount(상수) | 3 | 5 | 2 |
| **threshold** | **0.70** | **0.85** | **0.55** |

### 신규 4 신호 (제안)
| 신호 | MIDDLE | HIGH | LOW | 근거(a-priori) |
|---|---|---|---|---|
| **acceptJson** (`Accept: application/json`) | 0.20 | 0.15 | 0.28 | 클라이언트가 JSON 기대 = API 의도. 단 브라우저도 종종 섞어 보냄 → 중간. `responseTypeApi`(0.25)와 유사 티어 |
| **xRequestedWith** (`X-Requested-With: XMLHttpRequest`) | 0.28 | 0.22 | 0.34 | 명확한 AJAX/XHR = SPA API 호출. `corsPreflight`(0.30) 급 강신호 |
| **originHeader** (`Origin` 존재) | 0.15 | 0.10 | 0.20 | cross-origin 맥락(API 보강)이나 폼 POST 에도 붙음 → 약~중. `pathIdSegment`(0.15)·`query`(0.12) 티어 |
| **authScheme** (`Authorization` 스킴 존재: bearer/basic…) | 0.28 | 0.22 | 0.34 | 토큰 인증 = 강한 API 신호(특히 bearer). `corsPreflight` 급 |

**설계 의도** — 4개 모두 **양성 가산·현재 부재(0 기여)**. 단일 신호만으로 명백한 비-API 를 API 로 넘기지 않도록 threshold(0.70) 대비 modest. 다신호 동시 발화 시에만 경계 근처를 밀어 올림. 발화 조건(presence vs 다수결)은 §6 ApiScorer 항의 결정 포인트 참조(리뷰 P2 — 다수결 권장).

## 4. 안전성 — 무회귀 증명의 범위와 완화 설계 (사용자 핵심 질문 답, 리뷰 반영)

### 4.1 점수 신호 4종(accept/xhr/origin/auth) — 격하 0% (구조 증명)

1. **즉시(로그 미수집·DORMANT)**: 신규 인덱스 기본 -1 → 파서 미독 → 전 신호 0 기여 → **점수·kind 불변 → 현행 분류 100% 동일.** (ACRM 과 동일.)
2. **활성 후에도 점수 경로 격하(API→비-API) = 0**: 신규 4신호는 **양성 가산(기여 ≥ 0)**. 점수는 이 신호들에 대해 **단조 비감소**. 현행 API 는 이미 `score ≥ threshold` → ≥0 을 더해도 여전히 `≥ threshold` → **격하 불가.** ★전제 = effective weight ≥ 0. `validateWeightOverrides` 는 부호를 검증하지 않으므로 운영자가 음수 override 하면 미성립(기존 신호들과 동일한 운영자 책임 — 별도 가드 불요, 문구만 정확히).
3. **점수 경로의 유일한 변화 방향 = 상향(비-API→API)**: 경계 아래(<0.70) 엔드포인트 일부가 신규 신호 존재 시 threshold 를 넘어 **신규 ADMIT** 될 수 있음. 이건 실제 API 신호를 가진 경계 케이스라 대체로 옳은 승격. 과승격 위험은 §5 시뮬레이션으로 상한 측정.

### 4.2 응답 CT→endpoint_kind 경로 — 위 증명 범위 밖 (리뷰 P1 + 검토항목(5))

`$sent_http_content_type` 을 kind 분기로 쓰면 **격하 경로가 생긴다**. §4.1 증명은 점수 신호만 커버하므로 "현행 API 격하 0%" 문구는 **점수 신호에 한정**해 사용한다(D79 문구도 동일 스코핑 필요).

- **격하 3경로**(ApiScorer.evaluate 게이트 기준): ① kind 가 API_CANDIDATE→WEB_PAGE 로 바뀌면 `responseTypeApi`(0.25) 발화 상실 → 경계 API 점수 탈락(DROP_LOW_SCORE). ② WEB_PAGE + write method + 무강신호 → `DROP_WEB_FORM`(점수 무관 게이트). ③ dominant image/css/js → STATIC → `DROP_STATIC` 하드 veto.
- **오도 CT 사례**(응답 CT 신뢰 불가 케이스): 실제 API 가 에러·인증실패 시 text/html 에러페이지 반환 — 특히 **401/403-only 보존 대상(doc/19)과 정면 충돌**, WAF 차단·nginx 기본 에러페이지, 3xx redirect(본문 text/html), 서버 CT 오설정, 빈/누락 CT.
- 반대 방향(비-API 의 API 승격 오탐): 페이지/정적이 2xx json 을 과반 반환하는 경우는 드물고, 404-only 시그니처는 기존 드랍(doc/19)이 차단. 위험은 격하 방향이 지배적.

### 4.3 오도 CT 완화 설계 (구현 반영 확정)

| # | 가드 | 효과 |
|---|---|---|
| ① | **2xx 응답만 CT dist 누적** — `Acc.add()` 에서 `status/100 == 2` 일 때만 sent_ct 를 분포에 반영 | 에러페이지·인증실패 html·3xx redirect CT 오염 원천 차단. 401/403-only 엔드포인트는 CT dist 가 비어 **자연 폴백** → doc/19 보존 유지 |
| ② | **dominant 과반 가드** — dominant fraction < 0.5 면 CT 분기 skip → 폴백 | 혼합 CT(부분 오설정·부분 오염) 시 결정적 분기 회피 |
| ③ | 빈 dist → skip → 기존 $type/경로 폴백 (원안 유지) | CT 미수집(DORMANT)·미발화 안전 |
| ④ | **확장자 정적 veto(D55/D56)는 CT 보다 우선 유지** — CT 분기 위치 = `isStaticPath` 다음·$type 분기 앞 | .env/.pem 등 시크릿 수확 오탐 차단 정책 보존(CT 가 json 이어도 정적 확장자면 STATIC) |
| ⑤ | **CT 정규화** — `;` 파라미터(charset 등) 제거·소문자·`+json`/`+xml` suffix 포함(application/vnd.api+json 등) | 매칭 누락 방지 |

**잔여 위험과 검증**: 2xx 로 text/html 과반을 반환하는 진짜 API(HTML-fragment 반환 XHR 등)는 WEB_PAGE 재분류될 수 있다 — kind 관점에선 타당한 교정이고, 같은 로그 변경으로 켜지는 `xRequestedWith`(0.28)가 해당 엔드포인트에서 발화해 `responseTypeApi`(0.25) 상실분을 상회 보상하는 경향(보장은 아님). CT kind-flip 은 §5 시뮬레이션으로 사전 측정 불가(CT 데이터 부재)이므로 **활성 단계에서 전/후 스냅샷 diff 로 kind 재분류·격하를 실측**(§8-5)한 뒤 확정한다.

## 5. 시뮬레이션 (다음 세션 실행) — 상향 이동 상한 정량화

**목적**: 활성 시 비-API→API 로 새로 넘어갈 수 있는 엔드포인트 수(과승격 상한) 측정. (격하는 §4 로 0% 확정.)

**데이터 상황**: 점수는 **미영속**(GET /result serve-time 계산 — `inlineBasis`, report_json 엔 basis/score 없음 — 확인함). → `discovered_endpoint`(2,937,136행) 피처로 **재계산** 필요. 가용 컬럼(리뷰 정정): `host·method·path_template·had_query·non_browser_ua·hits·status_dist_json·params_json·template_source·version` + **`endpoint_kind`·`kind_confidence`**(영속됨 — 원안 목록 누락). **정확 재계산 = 14신호 중 13**: hostApiSubdomain·apiSegment·graphqlSegment·versionSegment·pathIdSegment·machineEndpoint·writeMethod·query·nonBrowserUa·repeatBonus(hits≥repeatMinCount)·**staticAssetPenalty**(endpoint_kind=STATIC ∨ hasStaticResourceName)·**responseTypeApi**(endpoint_kind=API_CANDIDATE)·**corsPreflight**(같은 host+path_template 의 OPTIONS 행 self-join — upsertDiscovered 는 OPTIONS 포함 전 메서드 영속, DORMANT 의미론과 동일). 미재현은 **pathHint 뿐**(hint 설정, 보통 NONE=0). 원안의 "corsPreflight·responseTypeApi 부분 근사"는 오기 — 정확 재계산 가능.

**방법**:
1. 대표 샘플(예 도메인 무작위 N, 또는 전수 배치) 로드 → `ApiScorer` 로직으로 현재 점수 재계산(위 신호). 재계산 스코어러는 `ApiScorer.scoreExplain` 재사용 또는 파이썬 포팅.
2. 분포 산출: 현재 `score ≥ 0.70`(ADMIT) 비율, threshold 대비 마진 히스토그램.
3. 상향 상한: **게이트 재현 후 DROP_LOW_SCORE 만 대상**(리뷰 정정 — STATIC veto·web-form 게이트·oversize 드랍은 점수 신호로 승격 불가하므로 카운트 제외. endpoint_kind 영속이라 게이트 재현 가능) 중 `score ∈ [0.70 − Σw, 0.70)` 개수. Σw(MIDDLE 4신호 합)=0.20+0.28+0.15+0.28=**0.91**(전신호 동시=극단). 시나리오별(1신호 present ~+0.15~0.28, 2신호 ~+0.35~0.56)로 "신규 ADMIT 가능" 구간 카운트 → 과승격 상한.
4. 결론 형식: "현행 API 점수 경로 격하 0건(확정). 활성 시 신규 ADMIT ≤ X건(전체 대비 Y%), 대부분 다신호 동시 발화 필요." + 필요 시 가중치 하향 재조정.

**한계(리뷰)**: CT kind-flip 효과(±`responseTypeApi` 0.25·web-form 게이트 변화, §4.2)는 CT 데이터가 아직 없어 시뮬레이션 불가 — 활성 단계 전/후 스냅샷 diff 로 실측(§4.3·§8-5).

**주의**: 재계산은 DB-only(운영 Loki 무관). 2.9M 전수는 무거우니 도메인 층화 샘플 권장.

## 6. 구현 설계 (파이프라인)

ACRM(M3) 선례 그대로. 모든 신규 인덱스 **기본 -1(DORMANT)**.

**코어(공통)**:
- `config/ParseProperties` — `responseContentTypeFieldIndex`·`acceptFieldIndex`·`xRequestedWithFieldIndex`·`originFieldIndex`·`authSchemeFieldIndex`(각 `@DefaultValue("-1")`). `acrmFieldIndex` 는 기존. ★명명(리뷰): `contentType…` 이 아닌 `responseContentType…` — 미소비 요청측 `$content_type`(idx 25)과 혼동해 운영자가 25 를 잘못 세팅하는 사고 방지, ParsedRequest 필드명(`responseContentType`)과 일치.
- `resources/application.yml` — 위 인덱스 키(미설정=-1). 활성 배포 시 24/26/27/28/29/30 세팅.
- `parse/LogLineParser` — `f.length > idx` 가드로 각 필드 nullable 읽기(ACRM 동형). `parse()` 가 `ParsedRequest` 채움.
- `model/ParsedRequest` — 필드 추가: `responseContentType`·`accept`·`xRequestedWith`·`origin`·`authScheme`(nullable). 하위호환 생성자.
- `normalize/Acc` — 집계: `sent_ct` 는 분포 맵(typeDist 형)이되 **2xx 응답만 누적**(§4.3 가드①)·CT 정규화(§4.3 ⑤) 후 키. 요청 신호는 presence 카운트. `add()` read + `mergeFrom()` 합산 + `toEndpoint()` 방출.
- `model/DiscoveredEndpoint`(+Metrics) / `domain/DiscoveredEndpointRecord` — 집계 컬럼(ddl-auto ADD). 요청 신호 presence 카운트·응답 CT 분포/dominant.
- `normalize/InventoryBuilder` — kind classify 에 contentTypeDist 전달·toEndpoint 배선.

**소비처**:
- `normalize/EndpointKindClassifier` — `classify(...)` 에 `contentTypeDist` 분기 추가. **분기 위치 = `isStaticPath`(확장자 veto) 다음·$type 분기 앞**(§4.3 ④ — "최상위 prepend" 아님, D55/D56 정적 veto 우선 보존). 빈 dist 또는 dominant fraction < 0.5 → skip → 기존 $type/경로 폴백(§4.3 ②③). json/xml/grpc(+`+json`/`+xml` suffix)→API_CANDIDATE, text/html→WEB_PAGE, image·css·js→STATIC.
- `classify/ApiScorer` — `Weights` 에 4필드(acceptJson·xRequestedWith·originHeader·authScheme) 추가 + `WEIGHT_KEYS`(14→18)·`weightsAsMap`·`applyOverrides`·프리셋 3종에 §3 값. **양성 가산만**(부재 감점 금지 — 무회귀 핵심).
  - ★발화 조건 결정 포인트(리뷰 P2): 원안 `presence>0` 은 4신호 모두 **클라이언트 제어 요청 헤더**라 스캐너/크롤러의 단발 요청(Accept:json+XRW+Origin+Auth 동시 송신)만으로 임의 엔드포인트에 +0.91 을 영구 발화시킬 수 있음. **다수결 권장**(`count*2 ≥ hits`, `nonBrowserUa` 의 `sdkUaCount*2 >= hits` 선례) — §5 시뮬레이션·구현 세션에서 확정.
  - acceptJson 매칭 규칙: `Accept` 값에 `application/json` 또는 `+json` 포함(대소문자 무시) 시 발화. `*/*` 는 미발화(브라우저·범용 클라이언트 기본값).
- `classify/Classifier` — 변경 최소(요청 content_type web-form 정밀화는 이번 범위 밖).

**무회귀**: 인덱스 -1 → 전 신규 신호 부재 → 점수/kind 불변. 새 가중치는 발화 0 이라 기여 0. 쓰기 계약(PUT/PATCH weights)엔 4키 추가되지만 override 는 선택.

**테스트**: LogLineParser(신규 필드 인덱스 nullable·부재 안전)·파서↔LogQL 교차검증(F_HOST 불변)·EndpointKindClassifier(contentTypeDist 분기·부재 폴백·**정적 확장자 veto 가 CT 에 우선·과반 미달 폴백**)·Acc(**CT 는 2xx 만 누적 — 4xx/3xx html 미오염, 401/403-only 는 dist 빈 상태 확인**)·ApiScorer(신규 4신호 발화·부재 0·override·프리셋)·무회귀(인덱스 -1 시 기존 스냅샷 동일).

## 7. 매뉴얼 (TW, 후속)
- `api-discovery-manual §8.3` log_format 예시에서 **`$server_protocol`·`$upstream_addr` 2줄 삭제**. §8.1 표에서도 두 항목 제외 또는 "미채택" 표기. **§8.4 한계별 매핑 표의 "인벤토리 그룹핑(보조)" 행**(두 변수 참조)도 동일 처리(리뷰 — 원안 누락).
- §8.2 endpoint_kind 개선(응답 CT) 현행화. 신규 스코어링 신호 4종 §s8 표/판독 매뉴얼 §4.3 가중치표 반영.

## 8. 다음 세션 착수 순서
1. 이 문서 + TASKS 서브아이템 확인.
2. §5 시뮬레이션 먼저 실행(가중치 확정·과승격 상한 확인) → 필요 시 §3 값 조정.
3. §6 구현(코어→소비처→테스트), DORMANT 무회귀 빌드 그린 확인.
4. 매뉴얼(§7)·DECISIONS(D79 갱신)·PROJECT_LOG.
5. (운영자) nginx log_format 변경 + application.yml 인덱스 세팅은 **활성화 단계**(별도, 코드 배포 후 무회귀 확인 뒤). 활성 직후 **전/후 스냅샷 diff 로 kind 재분류·격하 실측**(§4.3 잔여 위험 검증 — CT kind-flip 은 사전 시뮬 불가).
