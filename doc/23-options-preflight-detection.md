# preflight vs 진짜 OPTIONS 구분 — 타당성 판정 + 한계 문서화 (설계)

> 브랜치 `feature/options-preflight-detection`. 근거 doc/02 §1(로그 필드)·doc/04 §3(분류)·doc/08 §4(cors_preflight 신호)·Classifier 1차 OPTIONS skip.
> 근거 결정 doc/DECISIONS.md **D32**. dev 항목은 TASKS 부모 'preflight vs 진짜 OPTIONS' 아래 subitem(D26).
> **판정 = B (로그에 구분 신호 부재 → 한계 확정).** 완화 M1(권장·린)·M2·M3 seam 문서화.

## 0. 문제 정의

- 현 `Classifier` 1차 패스: `if (OPTIONS) continue` — **모든 OPTIONS 를 관찰 집합에서 skip**(CORS 신호 `corsKeys` 로만 사용, doc/08 §4). → `observedSpec` 에 OPTIONS 키 절대 미진입.
- 2차 패스: 스펙 OPTIONS operation 은 `observed=false` 항상 → **무조건 Unused**. 진짜로 호출돼도 Unused 오판.
- 근본 원인: CORS preflight(브라우저 자동 OPTIONS)와 진짜 OPTIONS API 호출이 **로그상 구분 불가** 가정 → 그래서 전부 skip. 본 설계는 이 가정의 타당성을 로그 신호로 판정한다(설계 핵심).

## 1. 타당성 판정 (핵심) — 로그 신호 분석 → **B**

preflight 의 결정적 식별자는 **요청 헤더** `Origin` + `Access-Control-Request-Method`(+`-Headers`). 로그 포맷(doc/02 §1, LogLineParser)이 이를 캡처하는가.

| preflight 후보 신호 | 로그 필드 | 캡처? | 판별력 |
|---|---|---|---|
| `Access-Control-Request-Method` | (없음) | ❌ 미로깅 | 결정적이나 부재 |
| `Origin` | (`$http_origin` 미포함) | ❌ 미로깅 | 결정적이나 부재 |
| `Authorization`(무인증) | (보안상 미로깅) | ❌ | — |
| 응답 204 | `$status`(f9) | ✅ | 약함(진짜 OPTIONS 도 204 가능) |
| 빈 body | `$body_bytes_sent`(f10) | ✅ | 약함(앱 OPTIONS 응답도 작을 수 있음) |
| UA=브라우저 | `$http_user_agent`(f14) | ✅ | **무판별**(preflight·브라우저 fetch OPTIONS 둘 다 브라우저 UA) |
| referer 존재 | `$http_referer`(f13) | ✅ | 약함(Origin 아님; 둘 다 referer 보유 가능) |
| sibling 상관(OPTIONS X + GET/POST X) | 도출 | △ | 불안정(ts 초단위 doc/02 §5.5·이중사용 합법) |

- **결정적 신호(`Origin`/`Access-Control-Request-Method`)는 로그에 없다** — referer 만 캡처되고 Origin 은 별개 헤더로 미로깅. 캡처되는 204/body/UA/referer 는 preflight↔진짜 OPTIONS 를 **신뢰성 있게 가르지 못한다**. 특히 브라우저가 `fetch(method:'OPTIONS')` 로 보내는 진짜 OPTIONS 는 preflight 와 **로그상 동일**.
- **약신호 확률판정 미채택**: 204+body0+browserUA 로 "preflight 추정"은 가능하나 false precision(D15 §8 철학) — 진짜 OPTIONS 를 preflight 로 오인해 Active 를 놓치는 **새 오판**을 만든다. 비대칭 원칙(부재=무증거, doc/02 §5.1)과도 충돌.
- ⇒ **판정 B: 진짜 한계.** 로그만으로 preflight↔진짜 OPTIONS 결정 불가.

## 2. 한계의 정확한 범위·영향

- **오판 케이스(유일·좁음)**: 스펙이 OPTIONS operation 을 **명시 정의**한 경우 → 진짜 호출 여부와 무관히 항상 **Unused**. (스펙의 OPTIONS operation 정의는 드묾 → 영향 endpoint 소수.)
  - preflight-only 트래픽만 받는 OPTIONS operation → Unused 가 **사실상 맞음**(documented OPTIONS operation 자체는 안 쓰임).
  - 진짜 OPTIONS 호출을 받는 operation → Unused 가 **틀림**(false Unused) — operator 가 "미사용이니 제거" 로 오도될 위험.
- **왜곡 없는 케이스(정상)**: ① 비-OPTIONS(GET/POST) 매칭·분류 정상, ② OPTIONS→sibling `cors_preflight` 보너스(doc/08) 정상(매칭 시 무관), ③ undocumented 경로 preflight 는 skip 돼 Shadow 오탐 안 만듦. → 과제가 우려한 "그 반대 왜곡"은 사실상 **없음**(OPTIONS 는 보고 자체를 안 하므로 inflate 불가).

## 3. 완화 (M1 권장·린 / M2 / 약신호 미채택)

- **M1 (권장·린·선택) — inconclusive 주석**: 2차 패스에서 `s.method==OPTIONS && !observed && corsKeys.contains(host+template)` 면 plain Unused 대신 **`preflightAmbiguous` 플래그**(또는 low-confidence)를 부여 — "OPTIONS 트래픽은 관측되나 preflight/진짜 구분 불가 → Unused 저신뢰". **`corsKeys` 재사용**(신규 수집 0), **비대칭**(진짜 Active 는 절대 단정 안 함). 비용 = `Finding.Unused` +필드(가산, doc/16 Zombie severity 추가와 동형)·ETag 1회 변경.
  - 효과: 진짜 호출 OPTIONS operation 의 confident false-Unused 를 inconclusive 로 완화(operator 오도 방지). preflight-only 도 inconclusive 가 되나(약간 보수적) 로그 관점상 정직.
- **M2 (채택·설계 §8) — operator OPTIONS 힌트**: 분류설정(doc/10·11 패턴)에 "genuine OPTIONS operation 경로" 선언 → 해당 경로 OPTIONS 를 관찰 패스에서 매칭 허용(Active 가능). operator 단언이라 신뢰. **상세 설계 §8.**
- **약신호 휴리스틱 미채택**: §1 사유(false precision·비대칭 충돌).

## 4. 향후 신호 확보 seam (M3 — 로그 포맷 의존)

**정의적 해결은 로그 포맷 확장 필요**(org/ops 결정): nginx `log_format` 에 `$http_origin` 및/또는 `$http_access_control_request_method` 추가.
- seam: ① `LogLineParser`/`ParsedRequest` 에 origin/acrm 필드(nullable, 기존 20/24필드 로그 호환 — 부재 null), ② `boolean isPreflight(r)` = `OPTIONS && acrm!=null`(결정적) [또는 `OPTIONS && origin!=null && 204`(약)], ③ `Classifier` 1차 OPTIONS skip 을 **`if (isPreflight) continue;` 로 한정** → 진짜 OPTIONS 는 매칭→Active, preflight 만 cors 신호. ⇒ 한계 해소.
- 이 seam 이 박히면 M1 의 inconclusive 는 자동으로 정확 판정으로 승급.

## 5. 무회귀

- **순수 B(문서만)**: 코드 변경 0 → 무회귀.
- **M1 채택 시**: `Finding.Unused` +필드 가산(기본값=현행) → 기존 Unused 단언 영향 최소. `corsKeys` 는 기존 구축물 재사용. ETag 는 findings 변경분 1회(doc/16 선례). 비-OPTIONS·기존 Active/Shadow/Zombie 불변.

## 6. dev / 후속 체크리스트 (TASKS subitem, D26)

- [x] **(판정)** 로그 신호 분석 → **B 확정**(Origin/ACRM 미로깅·약신호 비결정) + 한계 범위·영향 문서화 (doc/23 §1·§2).
- [x] **(M1, 권장·선택)** Unused(OPTIONS) inconclusive 주석 — `corsKeys` 재사용, `Finding.Unused`+`preflightAmbiguous`(4-arg 편의 ctor 하위호환), 2차 패스 분기(host-agnostic spec=template 매칭). 테스트 3(OPTIONS spec op + OPTIONS 트래픽→ambiguous, 트래픽 무→plain Unused, 비-OPTIONS→never ambiguous). → 완료 2026-06-24.
- [x] **(M2)** operator genuine-OPTIONS 힌트 (상세 §8) — documented OPTIONS 의 false-Unused 회복(spec-match 한정). → 완료 2026-06-24:
  - [x] (M2-a) `MatcherConfig`+`optionsOperationPrefixes`(List)+5-arg 편의 ctor(하위호환)+`merge` union+NONE/NONE_EMPTY — matcherJson 마이그레이션 0. `EffectiveClassificationResolver` includeWebForms 정규화 6-arg 보존(누락 버그 방지).
  - [x] (M2-b) `ApiHintMatcher` — `validatePrefixes` 재사용 검증 + `genuineOptions(template)`(세그먼트경계 prefixMatch).
  - [x] (M2-c) `Classifier` 1차 OPTIONS 분기 한정 — `hints.genuineOptions && matcher.match(OPTIONS) → observedSpec(→Active)`, else continue. corsKeys/cors 보너스 무변경.
  - [x] (M2-d) 테스트 — 선언+spec OPTIONS+트래픽→Active(M1 ambiguous 아님)/미선언→M1 ambiguous 유지/선언+미스펙·과declare→skip(Shadow 무생성)/merge 전역∪도메인/중앙 PUT 수용·검증(400)/기존 MatcherConfig 호출부 무변경.
- [ ] **(M3, 후속·로그포맷 의존)** `$http_origin`/`$http_access_control_request_method` 로깅 확보 시 parser+`isPreflight`+1차 패스 한정 → 한계 해소. (nginx log_format 변경 = org 결정.)

## 7. 결론

**B — 로그에 preflight↔진짜 OPTIONS 구분 신호 부재(결정적 헤더 미로깅). 한계 확정·문서화.** 완화 M1(채택·린, `corsKeys` 재사용·비대칭)·M2(operator 힌트·§8), 정의적 해결은 로그 포맷 확장(M3 seam).

## 8. M2 상세 설계 — operator genuine-OPTIONS 힌트

operator 단언으로 진짜 OPTIONS operation 을 Active 로 인정하는 경로(doc/23 §3 M2). M1(false-Unused 가시화) 위에 누적(브랜치 동일).

### 8.1 설정 위치/형태 (scope #1) — MatcherConfig 전용 list

- **`MatcherConfig` 에 `optionsOperationPrefixes`(List<String>) 1개 신설** — "OPTIONS 가 진짜 operation 인 경로" 선언.
  - **`apiPathPrefixes` 재사용 불가**: 그건 "이 경로는 API" 의미라, api 경로의 OPTIONS 는 대개 **sibling preflight** → 재사용 시 거의 모든 api 경로의 preflight 를 genuine 으로 오인(역오판). **전용 선언 필수.**
  - **prefix-only(세그먼트 경계, `ApiHintMatcher.prefixMatch` 재사용)**: genuine OPTIONS operation 은 드물고 경로 특정적 → exact/subtree prefix 충분. regex 는 overkill(필요 시 후속 대칭 추가). subtree 과매치는 8.2 의 spec-match 한정으로 무해.
- **저장·병합·캐시 = 기존 인프라 재사용(신규 0)**: `ClassificationConfig.matcherJson`(@Lob JSON) — **마이그레이션 0**(MatcherConfig 직렬화에 필드 1개 추가, 기존 JSON 은 필드 부재→null→`copyOrEmpty`→empty). `MatcherConfig.merge` 가 전역∪도메인 union(타 list 동일). `EffectiveClassificationResolver`/effective 캐시(doc/11) 무변경(MatcherConfig 단위 그대로 흐름).
- **하위호환**: `MatcherConfig` 5-arg 편의 생성자 추가(→ 6-arg 위임, `optionsOperationPrefixes=List.of()`) — 기존 `new MatcherConfig(5)` 호출부·테스트 무변경(M1 의 `Finding.Unused` 4-arg 편의 ctor 패턴 동형). `NONE`/`NONE_EMPTY` 에 `List.of()` 추가.
- **검증**: `ApiHintMatcher` 생성 시 `validatePrefixes`(개수/길이/'/'시작/비공백) 재사용 → 중앙 PUT 도 기존 `new ApiHintMatcher(matcher)` 검증경로(D18)로 **자동 400**.

### 8.2 Classifier 통합 (scope #2) — spec-match 한정(안전장치)

1차 패스 OPTIONS 분기를 한정.
```text
if (OPTIONS):
    if hints.genuineOptions(template) AND matcher.match(OPTIONS, host, template).isPresent():
        observedSpec[key(matched)].add(metrics)   // → 2차에서 Active(or Zombie)
    continue                                       // 그 외 OPTIONS 는 현행대로 skip (Shadow 미생성)
```
- **spec-match 한정(핵심 안전장치)**: 선언됐고 **스펙 OPTIONS operation 에 매칭될 때만** observed 진입 → Active. 선언했으나 미스펙 OPTIONS 는 여전히 skip(Shadow 미생성). 근거: 과declare(예 `/api`)해도 preflight 홍수가 Shadow 로 **폭발하지 않음** — "OPTIONS 는 절대 Shadow 안 됨" 현행 불변식 보존. M2 목적 = **documented OPTIONS 의 false-Unused 회복만**(undocumented OPTIONS 발견은 범위 밖·M3 영역).
- **corsKeys/cors_preflight 보너스 무변경**: corsKeys 는 모든 OPTIONS 로 계속 구축(declared 포함) → sibling GET/POST 의 cors 보너스(doc/08) 그대로. 한 경로가 genuine OPTIONS + sibling preflight 를 동시에 받을 수 있고 분리 불가하나, 보너스는 **sibling 양성**이라 유지가 안전(genuine 여부와 직교).

### 8.3 M1 정합 (scope #3) — 이중표기 없음

- declared+spec-matched OPTIONS → 2차 패스 `observed=true` → **Active 분기**(or Zombie) → `else`(Unused) 미도달 → `preflightAmbiguous` **미설정**. ⇒ M1 ambiguous 와 **상호배타**(이중표기 없음).
- **미선언** OPTIONS spec op + 트래픽 → `!observed` → M1 `preflightAmbiguous=true`(현행 유지). 선언 안 하면 M1 동작 그대로.

### 8.4 비대칭/신뢰 (scope #4)

operator **단언**이라 Active 인정 정당(휴리스틱 아님 — §1 약신호 미채택과 명확히 구분). 무선언=현행(M1/plain Unused). 선언은 documented OPTIONS 를 Active 로 **올릴** 뿐 — 감점·Shadow 생성 없음(비대칭, 무선언 시 무회귀).

### 8.5 노출/ETag·무회귀·중앙 API (scope #5)

- **findings/ETag**: declared+spec-matched OPTIONS 가 Unused→Active 로 바뀜 = 결과 콘텐츠 변화 → 해당 도메인 ETag 1회 bump(정당). 미선언 도메인 무변경·ETag 안정.
- **무회귀**: `ApiHintMatcher.NONE`/빈 선언 → `genuineOptions`=false → OPTIONS 전부 skip(현행). 기존 stored matcherJson → 신필드 empty. 5-arg 편의 ctor 로 기존 호출부/테스트 무변경. 비-OPTIONS·Shadow·cors 보너스 불변.
- **중앙 API = 이번 포함(추가 코드 0)**: `MatcherConfig` 가 곧 REST DTO(doc/11 재사용) → PUT/GET `/classification` 이 `optionsOperationPrefixes` 를 **컨트롤러 변경 없이** 수용·노출·검증(자동). D25 의 "중앙 API 튜닝=P4" 는 **신규 엔드포인트** 기준 — 본 건은 기존 MatcherConfig DTO 에 올라타 비용 0이므로 **P1 설정저장·적용과 함께 자연 포함**(별도 P4 항목 불요).
