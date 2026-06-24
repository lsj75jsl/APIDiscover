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
- **M2 (선택) — operator OPTIONS 힌트**: 분류설정(doc/10·11 패턴)에 "genuine OPTIONS operation 경로" 선언 → 해당 경로 OPTIONS 를 관찰 패스에서 매칭 허용(Active 가능). operator 단언이라 신뢰. 설정 비용.
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
- [ ] **(M2, 선택)** operator genuine-OPTIONS 힌트(분류설정 경로 선언→관찰 패스 OPTIONS 매칭 허용).
- [ ] **(M3, 후속·로그포맷 의존)** `$http_origin`/`$http_access_control_request_method` 로깅 확보 시 parser+`isPreflight`+1차 패스 한정 → 한계 해소. (nginx log_format 변경 = org 결정.)

## 7. 결론

**B — 로그에 preflight↔진짜 OPTIONS 구분 신호 부재(결정적 헤더 미로깅). 한계 확정·문서화.** 완화 M1(권장·린, `corsKeys` 재사용·비대칭)·M2(operator 힌트)는 선택적, 정의적 해결은 로그 포맷 확장(M3 seam).
