# non_api dropped observation 메트릭 (설계)

> 범위: 게이트 DROP_* 사유별 집계 + 스캔 결과(reportJson/`/result`) 노출 + 테스트.
> **범위 밖(후속)**: 별도 Actuator/Micrometer 대시보드·알람(TASKS "부하/운영 메트릭" 항목), repeatMinCount 무관,
> ScanResult/scan-status 에 total 비정규화(선택). 근거 결정은 doc/DECISIONS.md **D19**. 연계: doc/09 §2.2(Gate), doc/07 §8(ETag).
> 구현 브랜치: `feature/non-api-dropped-metric`.

## 0. 현 상태 (배선 대상)

- `Classifier`(5-arg 실impl): spec 미매칭 분기에서 `scorer.evaluate→Gate`, **ADMIT 만 Shadow**, `DROP_*` 는 89-93행에서
  **단순 무시**("메트릭 후속" 주석). OPTIONS·spec 매칭은 게이트 이전 `continue`. classify 3 오버로드 모두 `List<Finding>` 반환.
- `Gate { ADMIT, DROP_EXCLUDED, DROP_WEB_FORM, DROP_LOW_SCORE }` (doc/09 §2.2).
- `DiscoveryReport`(record): host/generatedAt/logWindow/specVersion/Summary(discovered/active/shadow/zombie/unused)/findings.
  `ReportBuilder.build(host, specVersion, window, discoveredCount, findings)`.
- `ScanResult.reportJson`(@Lob) = `DiscoveryReport` 통째 직렬화. `/result` 는 reportJson 직접 반환.
  ETag = `EtagUtil.of(toJson(List.of(specVersion, summary, findings)))` (generatedAt/window 제외, doc/07 §8).

## 1. 집계 방식 — Classifier 가 사유별 집계

게이트 결과를 아는 유일 지점이 `Classifier`(89행 `evaluate`). 다른 곳 집계는 evaluate **재실행** + OPTIONS/spec/cors 로직 중복
→ 비효율·이중 진실. **Classifier 에서 집계**한다.

**dropped 카운트 대상** (D-side, non_api):

| 관찰 분기 | dropped? |
|---|---|
| OPTIONS (CORS-only, 게이트 전 continue) | ✗ 보고도 카운트도 안 함 |
| spec 매칭 (Active/Zombie 후보, 게이트 우회) | ✗ 스펙 권위 |
| 게이트 ADMIT → Shadow | ✗ Shadow 보고됨 |
| 게이트 **DROP_EXCLUDED/DROP_WEB_FORM/DROP_LOW_SCORE** | **✓ 사유별 ++** |

dropped = **non-OPTIONS · spec 미매칭 · 게이트 DROP_*** 인 관찰 시그니처 수.

**반환 방식(하위호환)**: `classify(...)→List<Finding>` 3 오버로드 유지. findings+dropped 를 함께 내보낼 신규 메서드 추가
(반환타입만 다른 동명 오버로드는 Java 불가 → 다른 이름).

```text
record ClassificationResult(List<Finding> findings, DroppedNonApi dropped)        // classify 패키지
Classifier.classifyWithMetrics(discovered, spec, matcher, scorer, hints)          // 실 impl(카운트)
       → ClassificationResult
Classifier.classify(...5-arg...) → List<Finding>                                  // classifyWithMetrics(...).findings() 위임
```

기존 3/4-arg → 5-arg(List) → classifyWithMetrics 위임. 모든 List 오버로드·기존 테스트·LokiLiveIntegrationTest 불변.
DiscoveryJobService 만 `classifyWithMetrics` 로 전환.

**불변식(테스트용)**: `discovered(non-OPTIONS) = specMatched + shadow + dropped.total`.
(active/zombie/unused 는 S-side 카운트로 별개 — 위 식과 직접 연관 없음.)

## 2. 노출 방식 (린 결정) — DiscoveryReport 임베드 → /result

**(a) DiscoveryReport 임베드** vs (b) Micrometer 카운터. → **(a) 채택.**

- 요구는 "이 스캔에서 무엇이 왜 빠졌나" = **스캔 결과 콘텐츠**(인프라 시계열 아님). `/result`(reportJson) 노출이 자연.
- (b) Micrometer/Actuator 는 **TASKS 별도 항목**("부하/운영 메트릭 Actuator/Micrometer + 알람") → 여기서 하면 범위 중복(user 명시).
  또 host-tag 카디널리티 등 인프라 메트릭 고유 고려가 그 항목 소관.
- (a) 는 기존 결과 전달(조건부 GET·ETag·영속)과 일관, 신규 전송 0.
- **우선순위**: v1 = (a)만. 동일 `DroppedNonApi` 카운트를 후속 Actuator 항목이 재사용 가능(재작업 없음).

## 3. 버킷 + 하위호환

```text
model/DroppedNonApi(int excluded, int webForm, int lowScore)
  @JsonProperty("total") int total() { return excluded + webForm + lowScore; }   // 파생, 단일 진실원
```

- `DiscoveryReport` 에 **top-level 필드 `DroppedNonApi droppedNonApi` 추가**. Summary 안에 넣지 않음 — Summary 는
  `/scan-status` 경량 메타라 비대화 방지. 사유별 상세는 `/result` 로.
- 항상 non-null(빈 결과=`(0,0,0)`) → shape 일관.
- **가산적**: reportJson 에 `droppedNonApi` 필드 추가 = 기존 소비자 무시 가능(비파괴). `/result` 응답에 필드 1개 추가.
- `total` 파생 accessor `@JsonProperty` 노출(user "필요 시 합계"). dev 가 JSON 에 `total` 출현 검증
  (Jackson record 의 `@JsonProperty` accessor 직렬화).

## 4. 영속 + ETag

- **영속**: `DiscoveryReport` 가 reportJson(@Lob) 통째 직렬화 → droppedNonApi 자동 포함. **ScanResult 신규 컬럼 불필요**
  (summary 컬럼은 `/scan-status` 비정규화용, dropped 는 상세라 미포함). 스키마 변경 0.
- **ETag**: dropped 는 결과 콘텐츠다. 예) operator 가 exclude 추가 시 어떤 endpoint 가 `DROP_LOW_SCORE→DROP_EXCLUDED` 로
  이동하면 **findings 불변인데 dropped 분포만 변경** → 현 ETag(summary+findings)는 변화를 못 잡아 304 로 새 결과 미노출(버그).
  → **ETag 입력에 droppedNonApi 포함**: `List.of(specVersion, summary, findings, droppedNonApi)`.
  generatedAt/window 제외 원칙(doc/07 §8)과 일관(내용 기반).
- 부작용: 기존 저장 결과 ETag 1회 변경(같은 로그 재분석 시 version 1회 갱신). 콘텐츠 정의 확장이라 정당.

## 5. dev 구현 체크리스트 (10건) — 브랜치 `feature/non-api-dropped-metric`

### 신규
- [ ] `model/DroppedNonApi.java` — record(excluded/webForm/lowScore) + `@JsonProperty total()` 파생.
- [ ] `classify/ClassificationResult.java` — record(List<Finding> findings, DroppedNonApi dropped).

### 수정
- [ ] `classify/Classifier` — `classifyWithMetrics(...5-arg...)→ClassificationResult` 신규(게이트 89행 switch:
      ADMIT→Shadow, DROP_EXCLUDED→excluded++, DROP_WEB_FORM→webForm++, DROP_LOW_SCORE→lowScore++).
      5-arg `classify→List` 는 `.findings()` 위임.
- [ ] `model/DiscoveryReport` — top-level 필드 `DroppedNonApi droppedNonApi` 추가.
- [ ] `report/ReportBuilder.build(...)` — `DroppedNonApi dropped` 파라미터 추가, DiscoveryReport 에 전달.
- [ ] `batch/DiscoveryJobService.analyze` — `classifyWithMetrics` 전환, dropped 를 ReportBuilder 에 전달,
      `persist` 의 ETag 입력에 `report.droppedNonApi()` 추가.

### 테스트
- [ ] `Classifier` 사유별 카운트 — DROP_EXCLUDED/DROP_WEB_FORM/DROP_LOW_SCORE 각 1 + ADMIT(Shadow) 1 + spec 매칭 1
      + OPTIONS 1 → dropped=(1,1,1)/total=3, findings Shadow 1, **OPTIONS·spec dropped 제외**. 불변식 검증.
- [ ] `ReportBuilder` — droppedNonApi 임베드, 빈 결과→`(0,0,0)`(non-null).
- [ ] `DiscoveryJobService` — reportJson 에 droppedNonApi 포함, **ETag 가 dropped 분포 변화 반영**(findings 동일·dropped 사유만
      다른 두 스캔 → 다른 version), 기존 analyze 어서션 갱신(ETag 값 변동).
- [ ] 하위호환 — 기존 `classify(...)→List` findings 동일(ClassifierTest green), `/result`·`/scan-status` 비파괴(추가 필드만).

## 6. 한계 / 후속

- Actuator/Micrometer 노출·알람(별도 TASKS 항목)에서 동일 카운트 재사용 가능.
- 필요 시 `/scan-status`·ScanResult 에 `totalDropped` 비정규화 컬럼 1개 추가(at-a-glance) — 선택, 후속.
