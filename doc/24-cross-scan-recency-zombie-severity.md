# cross-scan recency 로 Zombie severity 보강 — 설계

> 브랜치 `feature/cross-scan-recency-zombie-severity`. 근거 doc/16(D23 버전 Zombie + severity)·doc/18(DB 스키마)·doc/07 §8(ETag 내용기반)·doc/20(dormant/무증거 선례).
> 근거 결정 doc/DECISIONS.md **D33**. dev 항목은 TASKS 부모 'cross-scan recency Zombie severity' 아래 subitem(D26).

## 0. 현 상태 / 문제

- `ZombieSeverity` = `0.5·hitsScore + 0.3·successScore + 0.2·spanScore`. **spanScore = clamp(log10((lastSeen−firstSeen)초+1)/4)** — 단일 스캔 **window 내** 지속(recency 대용, doc/16). window 크기(예 1h)에 상한돼 "몇 달째 entrenched zombie" 를 표현 못 함.
- `ScanResult`(PK=host)는 **최신 1건만** 보관(엔티티 주석 "이력 보관은 후속"). 과거 firstSeen 이력 없음 → cross-scan 산출 불가.
- severity 산정은 `Classifier` 2차 패스 `ZombieSeverity.of(ev)`(Evidence: hits/2xx/firstSeen/lastSeen). 외부 시계(now) **미사용**(순수·결정적).

## 1. recency 의미 확정 (핵심 해석)

"절대 cross-scan recency" 를 **zombie 의 누적 lifespan(entrenchment)** 으로 확정한다.
- **채택**: `lifespan = lastSeen(이번 스캔) − firstSeen(이력상 최초 관측)` — 이 deprecated endpoint 가 트래픽을 **얼마나 오래 지속**했나. 길수록 클라이언트가 못 떠난 = entrenched = 마이그레이션 리스크·시급성 ↑.
- **`now − lastSeen` 류 미채택**: Zombie 는 정의상 트래픽 보유(`S_deprecated ∩ D`, 이번 스캔 관측됨) → `now − lastSeen ≈ 0` 으로 **퇴화**(severity 분별 못 함). 게다가 `now()` 의존은 ETag churn 유발(§5). → 의미·ETag 양면에서 부적합.
- 즉 doc/16 spanScore 가 window 에 갇혀 못 잰 **절대 지속기간**을 이력으로 푼다. **모두 데이터 타임스탬프(firstSeen/lastSeen)** 기반, `now()` 불사용.

## 2. severity 통합 — 보강(additive), 대체 아님

window spanScore(doc/16) 는 **그대로 두고**, cross-scan entrenchment 를 **가산 보너스**로 더한다.
```text
severity = clamp( base(doc/16 그대로: 0.5·hits + 0.3·success + 0.2·spanScore_window)
                + entrenchmentBonus(lifespanDays) )
lifespanDays   = (lastSeen − historicalFirstSeen) / 1d        // 데이터 ts (now 아님)
entrenchmentBonus = W·clamp01( (log10(lifespanDays+1) − log10(GRACE+1))
                                / (log10(SAT+1) − log10(GRACE+1)) )   // GRACE 미만 → 0
```
- **1차값(캐비엇)**: `W=0.2`(최대 보너스)·`GRACE=7d`·`SAT=90d`. 예: 7d→0, 14d→+0.05, 30d→+0.11, ≥90d→+0.20. 실데이터 보정은 D24 보류(severity 1차값)와 함께.
- **대체 아닌 보강 이유**: ① 대체 시 cold-start(window span)와 cross-scan(일 단위) 척도 불일치로 **불연속(cliff)** 발생(1h window spanScore 0.89 ↔ 동일 zombie 2번째 스캔 recencyScore ~0). ② 보강은 base 불변 → 콜드스타트 무회귀(§4)·연속(보너스 0에서 시작). entrenched zombie 만 band 상향(MED→HIGH 가능).
- **GRACE 가 콜드스타트를 자동 흡수**: 이력 없으면 `historicalFirstSeen = 현재 firstSeen` → lifespan = window span(≪ GRACE) → 보너스 0 → **base=현행**. 별도 분기 불요.

## 3. 이력 영속 (scope #1)

> **갱신 2026-06-25 (doc/26 §8, D36 — EndpointHistory 흡수)**: 이 절의 `EndpointHistory`(@Lob historyJson, spec-bound, specKey 키)는 **검출 SoT `discovered_endpoint`(doc/26)로 흡수·제거**됐다. recency(firstSeen/lastSeen)는 이제 `discovered_endpoint`(host,method,pathTemplate 누적)에 저장되고, Zombie severity entrenchment 는 **검출 signature(`"{METHOD} {host} {template}"`) 키로 매칭 endpoint 의 누적 firstSeen 을 조회**(Evidence.entrenchedFirstSeen)한다. 콜드스타트 폴백·band 투영·now() 불사용·재구축 이관(무회귀)은 **동일하게 유지**. 아래 원안은 이력으로 보존.

- **신규 엔티티 `EndpointHistory`**(`@Table` 신규) — `@Id host` + **`@Lob String historyJson`** + `Instant updatedAt`. `historyJson` = `Map<specKey, {firstSeen, lastSeen}>`(Jackson 왕복).
  - specKey = `Classifier.key(spec)` = `"METHOD|host|template"`(host=null→`*`). 스캔 간 안정 키.
  - **`@Lob String` JSON 채택**(정규화 테이블 아님): doc/11/D17 컨벤션(JSONB 미사용, H2/PG 이식)·per-host 접근(스캔 단위)·`ScanResult.findById` 와 동일 패턴. 정규화(엔드포인트당 행)는 규모 확대 시 대안(§6).
  - **ScanResult 에 안 싣는 이유**: ScanResult=스캔 출력·ETag 상태, history=누적 이력 → 관심사 분리(ETag 레코드 비대화 방지). 엔티티 1개 추가.
- **보관 정책(무한 누적 방지)**: **spec 매칭 endpoint 만** 기록(Zombie/severity 는 spec endpoint 한정) → spec 크기로 자연 bound(수십~수백, 안정). Shadow/inferred·스캐너 noise 미기록. (선택) lastSeen 이 retention(예 365d) 초과한 엔트리 prune. 키 in-place 갱신(행 1/host).
- **마이그레이션**: doc/18 §1.1 ddl-auto(`update`) → **신규 테이블 자동 생성, 기존 데이터 무영향**. 콜드스타트(행 없음)=빈 이력=현행.

## 4. 콜드스타트 / 무회귀 (scope #3)

- **콜드스타트=현행**: 이력 부재 → `historicalFirstSeen=현재 firstSeen` → lifespan=window span ≪ GRACE → 보너스 0 → severity=doc/16 그대로. (부재=무증거, doc/20 dormant 선례 일관.)
- **하위호환**: `ZombieSeverity.of(Evidence)` 오버로드 보존(→ `of(ev, ev.firstSeen)` 위임=보너스 0=현행). `Classifier`·`ClassificationResult` 도 이력 없는 경로(빈 map) 오버로드 유지. → **기존 `ZombieSeverity`/`Classifier` 테스트 무변경 통과**.
- 변화는 오직 "이력상 7d+ 지속된 zombie" 의 severity 소폭 상향(의도). 명시/추정 Zombie confidence·findings 구조 불변.

## 5. 시간 의존 / ETag (scope #4 — 핵심, churn 방지)

- **① `now()` 불사용 → wall-clock churn 0**: lifespan = `lastSeen − historicalFirstSeen`, **둘 다 데이터 타임스탬프**. 동일 데이터+이력 재스캔 → 동일 lifespan → 동일 severity → **동일 ETag**. 시간 흐름만으로 bump 되는 일 없음(304 보존). `now−lastSeen` 류를 §1 에서 배제한 핵심 사유.
- **② ETag 는 severity `band` 만(버킷화)**: 지속 관측 zombie 는 매 스캔 lastSeen 전진(실 트래픽)으로 score 가 미세 creep → 매 스캔 ETag bump 우려(현 doc/16 도 hits 로 이미 score churn). **ETag 입력의 Zombie severity 를 raw score→`band`(HIGH/MED/LOW)로 투영** → band 전이 시에만 bump, 미세 creep 무bump. raw score 는 리포트 body 유지(operator 가시).
  - 투영은 ETag 입력 list 에서 findings 를 band-치환 형태로(선례: `typeDistribution.distinctKeys()`·`preflightSignal.status()` 투영). 부수효과로 **기존 hits 발(發) severity churn 도 완화**(pre-existing 개선). (Shadow confidence churn 은 별개·범위 밖.)
- 종합: **시간 의존 신호(recency)는 ETag 에 raw 로 안 들어가고(데이터 ts 파생) band 로 버킷화** → 매니저 요구("넣지 않거나 버킷화") 양쪽 충족.

## 6. 규모 / 성능 (scope #5)

- 이력 = host당 @Lob blob 1행, spec 크기 bound. 로드 = 스캔당 `EndpointHistory.findById(host)` 1회(신규 쿼리 1), merge O(관측 spec endpoint), save 1행. 배치 스캔 빈도라 무시 가능.
- 정규화 테이블 미채택(엔드포인트당 행) — 현 규모(spec bound)엔 @Lob 가 단순·이식적. 대량(수만 endpoint)·독립 쿼리 필요 시 정규화 전환(후속 seam).

## 7. 통합 흐름

```text
analyze:
  priorFirstSeen = EndpointHistory.load(host) → Map<specKey, firstSeen>   // 이번 스캔 전 이력
  classified = classifier.classifyWithMetrics(..., priorFirstSeen)        // Zombie severity = base + bonus(lastSeen − prior firstSeen)
  ... report ...
persist:
  history.merge(classified.observedTimes())   // specKey→{min(firstSeen), max(lastSeen)}
  EndpointHistory.save(host, history)          // ddl-auto 테이블
```
- `Classifier.classifyWithMetrics` +`Map<String,Instant> priorFirstSeen` 인자(빈 map 오버로드=현행). Zombie 생성 시 `ZombieSeverity.of(ev, priorFirstSeen.getOrDefault(key, ev.firstSeen))`.
- `ClassificationResult` +`observedTimes`(specKey→{firstSeen,lastSeen}, observedSpec 투영) — persist 의 이력 merge 입력(3-arg 편의 ctor 하위호환).
- merge: `firstSeen=min(prior, current)`, `lastSeen=max(prior, current)`. (firstSeen 단조 비감소 → lifespan 단조 비감소.)

## 8. dev 구현 체크리스트 (TASKS subitem, D26)

- [x] `domain/EndpointHistory`(@Id host, @Lob historyJson, updatedAt) + repository. `model/EndpointObservation(Instant firstSeen, Instant lastSeen)`(Map 왕복).
- [x] `ZombieSeverity.of(Evidence, Instant historicalFirstSeen)` — base(doc/16 불변) + `entrenchmentBonus`(W=0.2/GRACE=7d/SAT=90d, 1차값·코드상수, seam=@ConfigurationProperties). `of(Evidence)` 오버로드(→ ev.firstSeen 위임=현행).
- [x] `Classifier.classifyWithMetrics` +`priorFirstSeen` 인자(빈 map 5-arg 오버로드 하위호환) → Zombie severity 에 prior firstSeen 전달. `ClassificationResult` +`observedTimes`(3-arg 편의 ctor).
- [x] `DiscoveryJobService.analyze` — 스캔 전 `EndpointHistory` 로드→priorFirstSeen 주입, persist 후 observedTimes merge→save. ETag 입력 findings 의 Zombie severity→`band` 투영(churn 버킷화).
- [x] 테스트 — 콜드스타트(보너스 0=현행·기존 단언 green) / entrenched(lifespan≥SAT→band 상향) / GRACE 미만(보너스 0) / **ETag: 재스캔→동일 version(now 무의존), 미세 creep 무bump** / spec-only(observedTimes). → 완료 2026-06-24.
- [ ] (doc/18 sync, technical_writer) 신규 `endpoint_history` 테이블 스키마 반영.

## 9. 범위 밖 / 후속

- severity 가중치·entrenchment(W/GRACE/SAT) **실데이터 보정** — D24 보류(severity 1차값)와 함께.
- entrenchment 임계 중앙 API 튜닝 — P4(분석 파라미터 중앙 API 묶음, doc/16 후속과 동일).
- 트래픽 **추세**(스캔 간 증가/감소) 신호 — 이력에 lastSeen·hits 시계열 필요, 별도 항목(현재 firstSeen/lastSeen 2값만).
- 정규화 이력 테이블(엔드포인트당 행) — 대량 규모 시 @Lob blob 대안(§6).
- Shadow confidence 등 다른 연속 신호의 ETag churn(pre-existing) — 별개 항목.
