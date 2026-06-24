# $type taxonomy 샘플링 — 설계

> 브랜치 `feature/type-taxonomy-sampling`. 근거 doc/02 §5.0(매핑·document 트랩)·doc/08 §8 발견1·§9 보류·doc/17(responseTypeApi).
> 근거 결정 doc/DECISIONS.md **D30**. dev 항목은 TASKS 부모 '$type 전체 taxonomy 샘플링' 아래 subitem(D26).
> **research-gated**: §A 샘플링(research 0.4 실행)이 선행 → 증거표 산출 → §B 규칙으로 §3 코드 변경 확정.

## 0. 현 상태 / 문제

- `EndpointKindClassifier.API_TYPES = {xhr,fetch,json,api,ajax}` → `API_CANDIDATE`. `library`→STATIC, `document`→WEB_PAGE(약), 확장자→STATIC(1순위).
- **실데이터로 확인된 값은 `document`·`library` 뿐**(doc/02 §5.0, status=200 GET 슬라이스 3289/1711). **API_TYPES 5값은 관례 기반 추정 — 실관측 0.**
- 따라서 `API_CANDIDATE`(=$type 경유)가 운영 환경에서 **실제 발화하는지 미확인** → doc/17 `responseTypeApi`·Classifier `+0.05`(Classifier:176)가 dormant 일 수 있음.
- **document 트랩**(doc/08 §8 발견1): api.weble.net JSON API 가 전부 `$type=document`. document 는 web_page 신호로 신뢰 불가.
- `Acc.typeDist`(시그니처별 `Map<type,count>`)는 dominant 추출에만 쓰이고 **원분포는 어디에도 노출 안 됨**.

## A. 샘플링 프로토콜 (§1) — research 0.4 실행, 운영 부하 보호 준수

목적: 운영 로그에 실제 등장하는 `$type` 값·빈도·**status×method 교차**를 확정. 초기 관측이 status=200 GET 슬라이스에 편향돼 document/library 만 본 것을 write/4xx 포함 광범위로 교정.

- **소스/라벨**: `sample/loki_sample.py` 접속·라벨 패턴 재사용 — `{job="access_log", hostname="<엣지>"} |= \`<domain>\``.
- **$type 추출**: 서버측 `^|^` 파싱은 fragile(doc/05 §2.2) → **금지**. raw 라인을 받아 로컬에서 `^|^` split → field index **19**(=$type)·**9**(status)·**5**(request→method) 집계(LogLineParser 와 동일 인덱스).
- **부하 보호(필수, D7/운영주의)**: 창 ≤10분(`chunk-window` 동치), `limit` ≤2000·`direction=forward`·페이지 1~2개, 순차(동시발사 금지), off-peak 선호. **`limit=1e8` 절대 금지**(샘플 도구 기본값 우회 금지). $type 카디널리티가 낮아 호스트당 1~3 짧은 창이면 vocabulary 가 드러난다 — 전수 추출 불요, 총 쿼리 한 자리로.
- **대상 다양화(편향 교정)**: ① API 호스트(api.weble.net / `AORV1`), ② 웹 호스트(www.dreampark-sporex.com / `AOKD1`), ③ 가능하면 혼합 1개. status 필터 없이 받아 method(GET/POST/PUT/DELETE…)·status-class(2xx/3xx/4xx/5xx)로 **교차 집계**.
- **산출물(증거표)**: `$type 값 | count | % | status-class 분포 | method 분포 | 예시 path | host 성격`. §B 입력.

## B. taxonomy 분류 규칙 (§2) — 값 → api/page/static/ignore

각 관측 값 `v` 를 교차표로 분류.
- **api_candidate 후보**: 데이터/프로그램 응답 의미(xhr/fetch/json/api/ajax/rest/graphql/grpc 류) + **정적 확장자 path 편중 아님** + 다양한 method(특히 write) 또는 api 컨텍스트(api 호스트).
- **static**: asset 류(library/font/image/style/script/media) — 정적 확장자 path 로 교차 확인(확장자 1순위라 집합 추가는 선택).
- **web_page(약)**: GET·2xx·확장자 없는 path·web/www 호스트 편중(document 등).
- **ignore/unknown**: 모호하거나 트랩.
- **API_TYPES 편입 기준(보수적·비대칭)**: `v` 가 **명백히 API성**일 때만 추가(정적 path 편중 아님 + api 컨텍스트/혼합 method + 데이터 의미). 애매하면 **제외** — API_CANDIDATE 누락은 보너스 포기일 뿐 감점 없음(§4, D24 보수적 집합 일관).
- **document 트랩 재확인**: 알려진 API 호스트에서 `document` 의 API성 비중(api 호스트·JSON 2xx·`/res/{id}`)을 측정. 유의미하면 트랩 확정 →
  ① document→WEB_PAGE 는 **약한 비대칭 신호로 유지**(html penalty 제거됨(D15)→api_confidence 무해, responseTypeApi 보너스만 미수령·write-form drop 가능),
  ② **document 는 API_TYPES 에 절대 미편입**(트랩 자체가 미편입 사유),
  ③ 트랩이 심한 호스트는 operator 의 `api_path_*` 힌트(doc/09)로 보완. (압도적이면 document→UNKNOWN 강등은 데이터 기반 후속 판단.)

## 3. 코드 변경 범위 — 3 tier (데이터 게이트)

- **Tier 0 (1차·데이터 게이트) — API_TYPES 상수 정제**: 증거표 확정분으로 `EndpointKindClassifier.API_TYPES` 조정(api성 신규값 추가 / 확인만이면 무변경+근거 주석). **ApiScorer 무변경** — `responseTypeApi` 는 `API_CANDIDATE` 만 소비하므로 집합 정제가 자동 전파(doc/17 §1). 최소 변경.
- **Tier 1 (권장) — corpus `$type` 히스토그램 노출(self-reporting)**: `Acc.typeDist` 를 corpus 로 집계한 top-N `(type,count)` 를 `DiscoveryReport` top-level `typeDistribution`(형제 패턴 — DroppedNonApi/EndpointKindSignal)로 노출.
  - **근거**: `$type` vocabulary 는 **앱(upstream)별로 다르다** — 2~3 호스트 1회 샘플은 전 도메인에 일반화 안 됨. 워커가 매 스캔 이미 모으는 typeDist 를 노출하면 taxonomy 확정·드리프트 감지가 **수동 Loki 재조회 없이** 지속(운영 Loki 보호). document 트랩도 운영자가 자기 데이터에서 직접 확인. 비용 ≈ 0(재수집 없음).
  - **ETag(churn 회피)**: 입력엔 **distinct $type 키 집합(정렬, count 제외)** 만 포함. 신규 값 출현(=taxonomy 드리프트, 분류 영향 가능)은 version bump, **요청량에 따른 count 변동은 bump 안 함** → 304 효율 보존(doc/07 §8). count 히스토그램은 리포트 body(진단)에만.
  - top-N 상한(예 20)+`other` 버킷으로 카디널리티 가드(doc/13 철학). $type 은 민감정보 아님(콘텐츠 분류 라벨)→마스킹 불요.
- **Tier 2 (후속·미채택) — API_TYPES 설정화**: vocab 이 앱별이라 궁극적으로 `@ConfigurationProperties`/중앙 API 튜닝 대상. 단 D12(코드 상수 우선)·린 — 이번 미채택, seam 만 명시.

## 4. responseTypeApi 정합 / 배타 유지

- kind 는 단일값(STATIC⊕WEB_PAGE⊕API_CANDIDATE⊕UNKNOWN). API_TYPES 정제는 값을 버킷 간 이동시킬 뿐 한 값이 2버킷에 속하지 않음 → **WEB_PAGE⊕API_CANDIDATE 배타 불변**(doc/17 §2).
- 값 추가 → API_CANDIDATE endpoint↑ → responseTypeApi 보너스↑(의도). 값 제거는 데이터가 "비-API" 라 할 때만(보수적).

## 5. 무회귀

- API_TYPES 변경은 **데이터 게이트** — 확정 못 하면 무변경(불확실성만 문서로 종결). 기존 `EndpointKindClassifierTest` 의 xhr/json/document/library 매핑 단언은 추가가 additive 라 불변.
- 확장자 1순위 불변 → `.js`-as-document 트랩 처리 유지(doc/02 §5.0).
- document→WEB_PAGE 약신호 유지(데이터가 강등을 강제하지 않는 한, 강등해도 비대칭·무감점).
- doc/20 referer/dormant 무관(referer 는 $type-결정 후 UNKNOWN 일 때만; API_TYPES 는 그 앞 단계라 정합).
- Tier1 히스토그램: additive 비파괴 — reportJson 자동 포함, ScanResult 스키마 무변경. ETag 는 vocabulary-키만 → 기존 결과 ETag 1회 변경(신규 입력), 이후 count 변동엔 안정.

## 6. dev 구현 체크리스트 (TASKS subitem, D26)

- [x] **(research 0.4)** §A 프로토콜로 Loki $type 샘플링 실행(작은 창/limit·부하보호·`limit=1e8` 금지) → 증거표(type×status×method, API/웹/혼합 호스트) 산출. → **§A-결과** 참조(2026-06-24, 총 쿼리 3회). vocab={document,library}, API_TYPES 5값 실관측 0, document 트랩 ≈100%(api 호스트).
- [x] **(분석)** 증거표 → §B 규칙 적용 → API_TYPES **무변경 확정** + document 트랩 ≈100% 재확인 결과를 doc/21 §A-결과·DECISIONS D30 결론에 기록.
- [x] **(Tier0)** `EndpointKindClassifier.API_TYPES` **무변경 + 근거 주석**(실관측 0·관례 집합 유지·dormant·자동 전파). ApiScorer 무변경(responseTypeApi 자동 수혜) 확인.
- [x] **(Tier1·권장)** corpus `$type` 히스토그램: `InventoryBuilder` 가 Acc.typeDist 집계(top-N 20+other) → `model/TypeDistribution`(형제) → `DiscoveryReport` top-level + `ReportBuilder.build` 인자 + `DiscoveryJobService` ETag(**distinct 키 집합만**, count 제외).
- [x] 테스트 — API_TYPES 매핑 5값 불변 / 히스토그램 집계·top-N·other·노출 / ETag(신규 키→bump·count 변동→무bump) / 무회귀(확장자 1순위·document 약신호).

## A-결과. 샘플링 증거표 (research 0.4, 2026-06-24 실행)

> 도구 `sample/type_taxonomy_sample.py`(부하보호 내장: limit=2000·창=10분·`direction=forward`·페이지 1·순차). raw 라인 로컬 `^|^` split → field 19($type)·9(status)·5(request→method)·8(uri→path). **총 쿼리 3회**(한 자리, D7 준수). skip=0(필드 구조 견고).

샘플 윈도우(KST):
- W1 `AORV1` / `api.weble.net` (API 호스트) @ 09:00–09:10 — 2000줄(윈도우 포화, limit 도달).
- W2 `AOKD1` / `www.dreampark-sporex.com` (웹 호스트) @ 09:00–09:10 — 381줄(윈도우 전량).
- W3 `AORV1` / `api.weble.net` (API 호스트, off-peak 교차검증) @ 03:00–03:10 — 2000줄(포화).

증거표 (`$type | count | % | status-class | method | 예시 path | host 성격`):

| $type | host(윈도우) | count | % | status-class | method 분포 | 예시 path | host 성격 |
|---|---|---|---|---|---|---|---|
| `document` | api.weble.net (W1+W3) | 4000 | 100% | 2xx=3998, 4xx=2 | OPTIONS=2066, GET=1798, POST=136 | `/users/{id}`, `/campaigns/{id}/identify`, `/messages`, `/users/{id}/invitations-confirm` | **API**(JSON REST) |
| `library` | dreampark-sporex.com (W2) | 330 | 86.6% | 2xx=253, 3xx=34, 4xx=43 | GET=330 | `*.gif`, `/favicon.ico`, `*.woff` | 웹(정적 asset) |
| `document` | dreampark-sporex.com (W2) | 51 | 13.4% | 2xx=50, 3xx=1 | GET=47, POST=4 | `/m03/4/`, `/`, `/m02/4/` | 웹(페이지) |

**관측 vocabulary = {`document`, `library`} 뿐.** `API_TYPES{xhr,fetch,json,api,ajax}` = **실관측 0**(3윈도우·2호스트·peak/off-peak 교차, write·4xx·OPTIONS 포함 — status=200 GET 편향 제거 후에도 0).

### A-결과 분석 (§B 규칙 적용)

- **document 트랩 = 심각·확정**(§B③ 측정): 알려진 API 호스트 api.weble.net 에서 document **API성 비중 ≈ 100%** — OPTIONS=2066(CORS preflight, 순수 API 신호)·POST write·RESTful path(`/users/{id}` 등)가 전부 `document` 로 발화. document 는 web_page 신호로 **신뢰 불가** 재확인. 동일 호스트 peak/off-peak 모두 100% → 시간 artifact 아님.
- **API_TYPES 확정안 = 무변경(Tier0)**. 신규 api성 값 0관측 → §B "API_TYPES 편입 기준(보수적·비대칭, 명백히 API성일 때만)"에 부합하는 추가 후보 없음. 기존 5값은 **실관측 0이나 관례 기반으로 유지**(제거도 데이터가 "비-API"라 할 때만; 부재는 dormant·무감점 §4·§7). → ApiScorer 무변경, `responseTypeApi` 자동 정합.
- **library → STATIC, document → WEB_PAGE(약신호) 유지**: library 전부 GET·정적 확장자, document 웹호스트분은 확장자 없는 page path. 확장자 1순위 규칙이 `.gif/.woff` 를 STATIC 으로 선분류하므로 library 집합 추가는 선택(무영향).
- **dormant 확정**: `$type` 경유 `API_CANDIDATE`(Classifier:176 `+0.05`, doc/17 responseTypeApi)는 현 운영 vocab 에서 **발화 안 함**. 무해하나 가치 재평가는 §7 후속.
- **Tier1(corpus 히스토그램 노출) 가치 상향**: vocab 이 앱별이고 1회 3윈도우 샘플로는 일반화 불가 + document 트랩이 호스트별로 심함 → 운영자가 자기 도메인 분포·트랩을 매 스캔 확인할 self-reporting 이 강하게 정당화됨(운영 Loki 재조회 없이). 권장 유지.

## 7. 범위 밖 / 후속

- API_TYPES `@ConfigurationProperties`/중앙 API 튜닝(앱별 vocab) — Tier2, seam.
- 샘플 결과 api성 $type 이 사실상 부재면 responseTypeApi/$type-API_CANDIDATE 가 dormant — 무해(비대칭·무감점)하나 가치 재평가 후속(현재 조치 없음).
- document 압도적 트랩 시 document→UNKNOWN 강등 — 데이터 기반 후속.
- 분위수/distinct 대용량 근사(HLL/t-digest)는 별도 TASKS 항목.
