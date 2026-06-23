# 버전 기반 Zombie 추정 + Zombie severity (설계)

> 브랜치 `feature/version-based-zombie-severity`(1 PR). 근거 doc/04 §5(버전 보강)·§4.2(severity).
> 근거 결정은 doc/DECISIONS.md **D23**. 현재 Classifier 는 명시 deprecated 만 Zombie, severity 미산정.

## 0. 현 상태 / 제약

- Classifier 2nd pass: `s.deprecated() && observed → Zombie(confidence=1.0)`. `observedSpecKeys: Set<String>`(매칭 키만, 메트릭 없음).
- Classifier 는 시계(now)/window 미보유(순수·결정적). → recency 를 외부 now 로 plumbing 하면 결정성·테스트성 훼손.
- `Finding.Zombie(host,method,pathTemplate,confidence,specRef,reason)` — severity/estimated 없음.
- findings 는 이미 reportJson·ETag 입력 → Zombie 필드 추가는 자동 반영(별도 plumbing 불요).
- 버전 인식: `^v\d+$`(ApiScorer.VERSION_SEG 와 동일). PathNormalizer 는 버전 세그먼트를 정적 유지(변수화 안 함).
- 가용 메트릭: `Metrics(hits, firstSeen, lastSeen, statusDist 2xx/3xx/4xx/5xx, distinctClients)`.

## 1. 버전 기반 Zombie 추정 (§5)

**버전 식별**: pathTemplate 세그먼트 중 **첫** `^v(\d+)$`(대소문자 무시)를 그 endpoint 버전으로. 없으면 비버전(추정 대상 외).

**리소스 페어링**: `resourceKey = method | host(* if null) | (버전 세그먼트를 위치 그대로 "{V}" 치환한 template)`.
→ `/v1/orders/{id}`·`/v2/orders/{id}` 동일 resourceKey(버전 위치·나머지 경로 동일해야 페어). `/orders/v1/{id}` 와는 키가 달라 페어 안 됨(위치 인식).

**추정 규칙**(doc/04 §5): 그룹 내 **observed & 비-deprecated**(=active) 버전 중 최대 버전 `Vmax`.
active 이면서 버전 `< Vmax` 인 endpoint → **추정 Zombie**(원래 Active 였을 것을 재분류). "신버전 active 인데 구버전도 트래픽" 만 추정.
- 신뢰도: 명시 deprecated=1.0, **추정=0.6**(doc/04 §5, 명시보다 낮게). `estimated=true` + reason("신버전 vN active, 구버전 트래픽 지속 — deprecated 미표기").
- 명시 deprecated 우선: 추정 분기는 `!s.deprecated()` 에만 → 명시 Zombie 와 중복 없음.

**엣지**: 버전 1개/동일 버전→페어 없음→추정 없음. 비버전 경로→무영향. 신버전 Unused(미관측)→트리거 안 함(active 신버전 필요).
신버전 명시 deprecated→active 아님→트리거 안 함. 구버전 여럿(v1,v2<v3)→각각 추정. 한 경로 다중 버전 세그먼트→첫 번째를 버전으로.

## 2. Zombie severity (§4.2) — 가용 메트릭만, 결정적

severity = f(hits, recency, 2xx비율). **외부 시계 없이** 가용 메트릭으로 실현(Classifier 순수성 유지).
- `hitsScore = clamp(log10(hits+1)/3, 0..1)` — 볼륨(마이그레이션 리스크). (1→0.1, 100→0.67, 1000→1.0)
- `successScore = total>0 ? 2xx/total : 0` — 진짜 성공 사용 vs 탐침/에러.
- `spanScore = clamp(log10((lastSeen−firstSeen)초 + 1)/4, 0..1)` — window 내 **지속 사용**.
  (recency 의 가용 메트릭 실현: 관측 자체가 "최근 window" 보장 + 지속될수록 고착. 절대 cross-scan recency 는 후속, 히스토리 필요.)
- `score = 0.5·hitsScore + 0.3·successScore + 0.2·spanScore` → band: ≥0.66 HIGH / ≥0.33 MEDIUM / else LOW. (가중치·임계 1차값, 튜닝 가능.)
- **모든 Zombie 에 적용**(명시 deprecated + 추정). 매칭 Evidence(누적 hits/firstSeen/lastSeen/2xx/total)에서 산출.
- **confidence vs severity 역할 분리**(doc/04 §4.2): confidence="진짜 Zombie 인가"(명시 1.0/추정 0.6), severity="조치 시급성"(트래픽 메트릭). 직교.

## 3. Finding.Zombie shape + 노출

- `Finding.Zombie(host, method, pathTemplate, double confidence, Severity severity, boolean estimated, String specRef, String reason)` — **severity·estimated 가산**.
- `model/Severity(double score)` + `@JsonProperty("band") SeverityBand band()` 파생(단일 진실원) + `enum SeverityBand {LOW, MEDIUM, HIGH}`.
- 노출: Zombie findings 새 필드 reportJson 에 가산. **ETag 자동 반영**(severity 는 Finding.Zombie 안 → findings 가 이미 ETag 입력).
- summary.zombie 카운트: 추정 Zombie 포함(추정도 Zombie). 카운트 의미 유지.

## 4. 산정 위치 / 무회귀

- **Classifier 가 owner**(spec S + observed + 매칭 메트릭 보유). `observedSpecKeys: Set<String>` → **`Map<String, Evidence>`**
  (1st pass 에서 매칭 d 메트릭 누적; host-agnostic spec 은 여러 host d 가 한 키에 누적되므로 합산). 2nd pass: 버전 추정 + severity.
- 헬퍼 분리: `VersionZombieInference`(spec+observedKeys→추정 zombie 키 집합), `ZombieSeverity.of(Evidence)→Severity`. Classifier 오케스트레이션.
- **무회귀**: 명시 deprecated Zombie 는 confidence 1.0·reason 보존(+severity·estimated=false 가산). 버전 추정은 **버전 페어가 있는 spec 에만** 작용
  (없으면 전부 현행). 기존 ClassifierTest 입력에 v1/v2 페어 없으면 무영향(확인). `Finding.Zombie` 생성자 변경 → 생성처(Classifier)+ClassifierTest 갱신.
- **설정(린 판단)**: 추정 confidence 0.6·severity 가중치/임계는 **코드 상수(1차)**. 튜닝 필요 시 `@ConfigurationProperties` 이동(seam 명시).
  중앙 API 는 후속(범위 밖). 새 properties 클래스/Classifier 의존 churn 회피.

## 5. dev 구현 체크리스트 (9건)

### 신규
- [ ] `model/Severity(double score)` + `@JsonProperty("band") band()` 파생 + `enum SeverityBand{LOW,MEDIUM,HIGH}`.
- [ ] `classify/ZombieSeverity` — `of(Evidence)→Severity`(hitsScore·successScore·spanScore 가중합→band). 순수·결정적.
- [ ] `classify/VersionZombieInference` — `^v\d+$` 버전 식별·resourceKey 페어링·추정 규칙 → 추정 Zombie 키 집합.
      + `Evidence`(누적 hits/firstSeen/lastSeen/2xx/total) 소형 타입.

### 수정
- [ ] `model/Finding.Zombie` — `Severity severity` + `boolean estimated` 필드 추가(가산).
- [ ] `classify/Classifier` — `observedSpecKeys: Set→Map<String,Evidence>`(1st pass 메트릭 누적), 2nd pass 버전 추정+severity 배선
      (명시 1.0/추정 0.6, 모든 Zombie severity). classify/classifyWithMetrics 반영.

### 테스트
- [ ] `VersionZombieInferenceTest` — v1+v2 observed·v1 비deprecated→v1 추정; 단일/동일 버전 무; 비버전 무; 신버전 Unused→구버전 비Zombie;
      명시 deprecated 구버전 중복 없음; 다중 구버전; 버전 위치 인식.
- [ ] `ZombieSeverityTest` — band 경계(hits/2xx/span 버킷), HIGH vs LOW, total=0·firstSeen=lastSeen 엣지.
- [ ] `Classifier` 통합 — 명시 deprecated Zombie 에 severity+estimated=false·**confidence 1.0 무회귀**; 추정 Zombie 0.6·estimated=true;
      비버전 spec Active 유지(무회귀); evidence 합산(host-agnostic 다중 host).
- [ ] 하위호환 — 기존 Zombie/Active/Unused(비버전) 동작 동일, ClassifierTest 생성자 갱신, 리포트 가산 필드.

## 6. 범위 밖 / 후속

- 절대 cross-scan recency(스캔 히스토리 필요).
- 추정 임계/severity 가중치 중앙 API 설정(필요 시 yml→중앙, seam=`@ConfigurationProperties`).
