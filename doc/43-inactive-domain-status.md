# 43. 무접속 도메인 상태 관리 — 7일 무요청 비활성·경로 제외·재활성 정책

> 상태: **설계(architect, 2026-07-20)** — 구현 전. 사용자 확정(2026-07-20): ①임계 **기본 P7D·설정 override**(§4.2),
> ③**수동 스캔 후 주기 스캔 대상으로 승격**(§4.4). **잔여 결정 = §4.3 status 표현(안A 파생 vs 안B 영속) 1건.**
> 근거 실측: architect 운영 DB(.197) read-only 재실측 + 코드 추적(본문 표기).

## 0. 요약

사용자 요구 = "7일간 실요청 없는 도메인은 비활성으로 보고 스캔에서 빼되, `.cloudbric/pron/`·
`.cloudbric/afc/` 요청은 실요청으로 치지 않는다. 비활성 도메인도 수동 스캔이나 요청 재개 시
그때부터 활성으로 되돌린다. DB 가 상태를 갖고 규칙대로 바꾸며 활성만 스캔한다."

기능의 대부분은 **이미 있는 `inactive-after` lastSeenAt 게이트(D57/D59)** 가 수행 중이다.
실 갭은 3가지, 그중 필수 코드 변경은 ①뿐이다.

| 항목 | 성격 | 현재 | 변경 |
|---|---|---|---|
| ① `.cloudbric/pron/`·`afc/` 경로 제외 | **필수(핵심)** | discovery 가 두 경로도 트래픽으로 집계 → lastSeenAt 오염 | discovery LogQL 에 `<uri>` 파싱 + `\| uri !~` 라벨 필터 |
| ② 무접속 임계 3d→7d **(확정)** | 설정값(이미 설정화됨) | `scan.inactive-after: P3D` | **기본 `P7D`·설정 override 가능** |
| ③ 상태를 DB 에 노출 | **설계 결정(잔여)** | `enabled`(수동)+lastSeenAt 게이트(암묵·비영속) | 파생 read-only(권장) vs 영속 enum(§4.3) |

재활성(요구 ②): 요청 재개 시 discovery 가 lastSeenAt 을 갱신해 게이트가 자동 재포함(self-healing).
**수동 스캔(요구 ③, 확정)**: `POST /scan-now`·`/scan` 은 게이트를 우회해 즉시 스캔할 뿐 아니라,
**그 도메인을 주기 스캔 대상으로 승격**시켜야 한다 — 안A=lastSeenAt bump, 안B=status flip(§4.4).

## 1. 현황 실측 (2026-07-20 14:27 KST / 05:27 UTC)

- domain_config: total 57,552 · **enabled 36,671** · disabled 20,881.
- enabled 중 `lastSeenAt` 기준: 3일 내 관측(selectable) **8,882** · 3일 초과(게이트) **27,789**
  (그중 7일 초과 **15,457**) · null 0.
- **7일간 스캔 시도(`last_scan_attempt_at`) 없는 도메인 = 29,082** — enabled **8,201** / disabled 20,881.
- disabled 20,881 = 07-02 워터마크 고착 유령 잔존(doc/42 삭제와 별개, 스캔 대상 아님).

## 2. 원인 규명 — 발산의 정체 = inactive-after 게이트(정상 동작), window 상한·기아 아님

지난 세션이 관측한 "완만한 발산"(워터마크 롱테일)의 원인을 3가설로 실측 분리했다.

| 가설 | 실측 지표 | 값 | 판정 |
|---|---|---:|---|
| A. window 상한(백필 슬라이스가 실시간 못 따라감) | 최근 시도(≤24h) & watermark >3d | **0** | ✗ |
| B. 라운드로빈 도달률(FIFO 기아) | selectable(lastSeenAt≤3d) & attempt >24h | **0** | ✗ |
| C. **inactive-after=P3D 게이트 스킵** | 굶는 enabled(attempt>7d) 중 lastSeenAt>3d | **8,201 / 8,201 = 100%** | ✓ **원인** |

- 재시도된 도메인은 watermark 가 전부 fresh(≤3d)로 따라잡음 → **window 상한 병목 없음**.
- 배포 throughput `domains-per-tick 650 / tick PT1M`(주야 균일)·`active-interval PT30M` 은
  selectable ~8.9k 를 여유 있게 순환 → **기아 없음**.
- 롱테일(워터마크 3d+ 27,789)은 **무접속 도메인이 lastSeenAt 게이트로 스킵되며 워터마크가
  얼어붙은 것**으로, 설계된 동작(D59)이다. 라이브 트래픽 도메인엔 발산이 없다.

즉 사용자가 "7일 미스캔"으로 관측한 enabled 8,201 개는 **이미 스캔에서 빠져 있으나 상태만
`enabled`로 남아** 혼란을 준다. 요구는 이 상태를 (경로 제외를 반영한) 정확한 신호로 바꾸고
가시화하는 것이다.

## 3. 요구 정책 ↔ 현재 동작 대응

| 요구 | 현재 구현 | 갭 |
|---|---|---|
| ① 7일 무request→비활성 | `ScanSelector.staleCutoff`=now−`inactive-after`(P3D). `findDueForScan` 이 `lastSeenAt < cutoff` 제외 → **스캔(수집+평가) 안 함**. self-healing(D59). | 임계 3d≠7d · '스킵'이 상태 아님 |
| ①-단서 `.cloudbric/pron/`·`afc/` request 제외 | `DomainDiscoveryService.buildLogQL` = `sum by(host,real_host,hostname) count_over_time({job} \| pattern.. \| status !~ 404\|470)`. **경로(uri) 필터 없음** → 두 경로도 카운트→`DomainUpserter` 가 lastSeenAt=now 갱신 | **미구현(핵심 갭)** |
| ② 수동 스캔·요청 재개 시 재활성 | 요청 재개→discovery(10분)가 lastSeenAt 갱신→게이트 자동 재포함. `POST /scan-now`(동기)·`/scan`(비동기)=ScanSelector 미경유, **항상 스캔** | 기능 충족. 단 수동스캔은 '주기 스캔 재포함'까진 아님(§4.4) |
| ③ DB status 보유·변경·활성만 스캔 | `enabled` boolean=**수동/사용자 설정**(discovery 가 절대 자동변경 금지, doc/30 §5). 활동기반 상태는 lastSeenAt 게이트로 **암묵 계산**(비영속) | 활동기반 영속/노출 status 없음 |

★주의: `enabled` 는 사용자 소유 설정이라 활동으로 자동 토글하면 안 된다(doc/30 §5 불변식).
따라서 활동상태는 `enabled` 와 **별도 축**이어야 하고, "활성만 스캔" = `enabled=true` **AND**
활동상태=ACTIVE 로 해석한다.

## 4. 설계

### 4.1 `.cloudbric/pron/`·`.cloudbric/afc/` 경로 제외 (필수)

로그에 URI 가 파싱 가능하다 — `LogLineParser.F_REQUEST_URI = 8`. status 필터와 **동일한 안전
패턴**(pattern 파싱 후 라벨 부등호 필터, 광역 `\|=` 아님 → Loki 부하 안전, doc/42 §C 확인)으로 제외한다.

- `DomainDiscoveryService.buildPattern()` 에 인덱스 8 위치를 `<uri>` 로 명명(현재 skip `<_>`).
  포지션 상수는 `LogLineParser` 공유 → 드리프트 차단(기존 규약).
- `buildLogQL` 에 `pathExcludeFilter()` 추가: `| uri !~ "^/\\.cloudbric/(pron|afc)/.*"`
  (앵커·정규식은 실 로그 uri 표기 확인 후 확정 — §7). 설정화: `discovery.excluded-paths`
  (List<String>, null/빈=필터 없음=무회귀. 기본 `[/.cloudbric/pron/, /.cloudbric/afc/]`).
- 효과: 두 경로만 받는 도메인은 discovery 관측에서 빠져 upsert 안 됨 → lastSeenAt 정체 →
  `inactive-after` 경과 후 게이트 제외. 실요청이 하나라도 있으면 그 카운트로 정상 관측(유지).
- 신규 등록도 동일 억제(두 경로만인 신규 Host 는 애초 등록 안 됨 = doc/42 C 유령억제와 결).

★한 쿼리에 status·uri 두 라벨필터가 붙는다. pattern 이 이미 status 를 파싱 중이라 uri 1개
추가는 파싱 비용만 소폭 — 실측 부하 확인은 §7(짧은 창·hostname 스코프).

### 4.2 무접속 임계 기본 P7D (확정) + 설정 override

`scan.inactive-after` 기본값 `P3D` → **`P7D`**. 이 값은 **이미 `ApiDiscoverProperties.Scan.inactiveAfter`
로 설정화**돼 있어(application.yml/adc.yaml/env override 가능) — 코드는 기본값 1행 수정뿐이고,
운영자가 언제든 다른 기간으로 조정 가능(0/null=게이트 비활성=무회귀). adc.yaml 은 application.yml
기본값 baked 사용(D74)이므로 기본 P7D 반영엔 재빌드 필요.
- **트레이드오프**: 7d 로 늘리면 무접속 4~6일차 도메인이 다시 스캔 대상 → 스캔량 증가(현재 3d 가
  더 공격적). 영향 추정: selectable ≈ (lastSeenAt≤7d) 이므로 §1 기준 현 8.9k → 무접속 3~7d 분 추가.
  배포 시 Loki 예산(`max-queries-per-hour 6000`) 여유 관찰(§7). 필요 시 설정으로 재조정.

### 4.3 상태의 DB 표현 — 영속 enum 컬럼 (★사용자 확정 = 안 B)

`domain_config` 에 활동상태를 **영속 컬럼**으로 둔다. 상태 전이 시각/이벤트를 확보해 중앙연동·
감사·알림에 쓴다(안 A[파생 계산] 대비 이 이력성이 채택 이유).

**컬럼**(ddl-auto ADD, 무회귀):
- `activity_status` : enum `ACTIVE|INACTIVE`, 기본 **ACTIVE**(기존 행 전부 ACTIVE 로 시작 →
  첫 sweep 이 무접속분을 INACTIVE 로 수렴). `@Enumerated(STRING)`. 인덱스(스캔 술어에 사용).
- `activity_status_changed_at` : Instant nullable — 마지막 전이 시각(감사·중앙 통지용). flip 시 set.

★`enabled`(사용자 수동 토글, 자동변경 금지·doc/30 §5) 과 **별도 축**. **"활성만 스캔" =
`enabled = true AND activity_status = ACTIVE`.**

**단일 진실원 원칙**: `lastSeenAt` 은 **입력 신호**, `activity_status` 는 그로부터 **구체화된 결정**,
스캔 게이트는 `activity_status` 만 읽는다(이중 진실원 회피). 즉 lastSeenAt→(sweep)→activity_status→(scan).

**전이 writer 3경로** — 전부 엔티티 로드 없는 직접 UPDATE(=`touchScanSchedule` 패턴, 사용자
설정 PUT 과 lost-update 무관):
1. **ACTIVE→INACTIVE (sweep)**: `UPDATE domain_config SET activity_status='INACTIVE', changed_at=:now
   WHERE activity_status='ACTIVE' AND (lastSeenAt IS NULL OR lastSeenAt < :now − inactiveAfter)`.
   discovery 틱 종료 시 1회 bulk UPDATE 로 실행(새 @Scheduled 불요 — discovery 는 이미 lastSeenAt 을
   갱신하므로 같은 사이클 끝에 sweep 이 자연스럽다). 쿼리 1개/틱·인덱스.
2. **INACTIVE→ACTIVE (실요청 재관측)**: `DomainUpserter.upsert` 가 lastSeenAt=now 갱신 시
   `activity_status`=ACTIVE 동반 set(managed 엔티티 dirty → @DynamicUpdate flush). 경로제외 반영된
   실요청만 관측되므로 프로브/`.cloudbric` 로는 flip 안 됨(진동 방지의 핵심).
3. **INACTIVE→ACTIVE (수동 스캔·요구 ③)**: §4.4.

**진동(flapping)**: doc/42 시절 봇 재방문이 게이트를 진동시킨 문제는 **C(probe-status 필터)+본 건
경로제외** 로 discovery 관측이 실요청만 남아 근본 차단된다. 추가로 7일 창 자체가 히스테리시스
(INACTIVE 되려면 7일 연속 무요청, 실요청 1건이면 다시 7일 ACTIVE) → 별도 쿨다운 불요.

**스캔 술어 통일**: `findDueForScan`·`findDueWithNewTraffic`·`findDueWithoutNewTraffic` 의
`lastSeenAt >= staleCutoff` 술어를 `activity_status = 'ACTIVE'` 로 교체(ScanSelector 의 staleCutoff/
EPOCH 센티넬 로직 제거). 스캔은 상태 컬럼만 신뢰.

### 4.4 재활성 경로 (요구 ②·③)

- **요청 재개(②)**: discovery 가 실요청(경로제외 반영) 재관측 → `upsert` 가 lastSeenAt=now +
  `activity_status`=ACTIVE flip(§4.3 writer 2) → 다음 스캔 틱부터 주기 스캔 재포함. **이미 있는
  discovery 경로에 flip 한 줄 추가.**
- **수동 API 스캔(③, 확정)**: `POST /scan-now`(동기)·`POST /scan`(비동기) 은 즉시 스캔에 더해
  해당 host 를 **`activity_status`=ACTIVE 로 flip**(직접 UPDATE) → **주기 스캔 대상으로 승격**.
  이후엔 일반 도메인과 동일하게 7일 무요청 시 sweep 이 다시 INACTIVE 로 강등(요구대로 실요청이
  이어지면 계속 ACTIVE 유지). scan-now 의 워터마크 미전진(온디맨드 스냅샷, doc/33 §7)은 불변 —
  승격은 status flip 으로만, 다음 주기 스캔이 워터마크를 전진시킨다.

## 5. dev 항목 (스프린트 체크리스트 — 구현 시 TASKS 로 이관)

경로 제외(필수):
- [ ] discovery URI 파싱: `buildPattern()` 인덱스 8 → `<uri>` 명명(LogLineParser 상수 공유).
- [ ] `discovery.excluded-paths` 설정(List<String>, 기본 2경로, null/빈=무회귀) + `ApiDiscoverProperties.Discovery` 필드.
- [ ] `buildLogQL` 에 `| uri !~ "..."` 라벨필터(경로 정규식 앵커, 실 uri 표기 확인 후 확정 §7).

임계:
- [ ] `scan.inactive-after` 기본값 P3D→**P7D**(설정 override 유지).

영속 status(안 B):
- [ ] `DomainConfig` 에 `activity_status`(enum·기본 ACTIVE·인덱스)·`activity_status_changed_at`(nullable) ddl-auto ADD.
- [ ] sweep: discovery 틱 종료 시 `ACTIVE→INACTIVE` bulk UPDATE(lastSeenAt<cutoff).
- [ ] flip ACTIVE: `DomainUpserter.upsert`(실요청 재관측 시) + `ScanController` scan-now/scan(수동, 직접 UPDATE).
- [ ] 스캔 술어 교체: 3개 findDue* 쿼리의 lastSeenAt staleCutoff → `activity_status='ACTIVE'`. ScanSelector staleCutoff/EPOCH 제거.
- [ ] 상태 노출: 도메인 조회/scan-status DTO 에 `activityStatus`(+changedAt) 필드.

테스트:
- [ ] buildLogQL 스냅샷(uri 필터 유무 무회귀)·경로제외 도메인 lastSeenAt/status 미갱신 시나리오.
- [ ] sweep 경계(P7D)·실요청 재관측 flip·수동 스캔 flip·스캔 술어가 ACTIVE 만 선택·무회귀(기본 ACTIVE→기존 동작).
- [ ] 실 PG(Testcontainers): ddl-auto ADD·bulk UPDATE·인덱스 술어(seq scan 회피, D75).

## 6. 미결정·리스크

- **[확정]** §4.2 임계 = 기본 **P7D**·설정 override. §4.3 status = **안 B(영속 enum)**. §4.4 수동
  스캔 = **주기 스캔 대상으로 승격**(status flip). (2026-07-20 사용자 확정, D82.)
- **[리스크]** 경로 정규식은 실 로그 uri 표기(선행 슬래시·쿼리스트링·대소문자) 확인 후 확정(§7).
- **[리스크]** uri 라벨필터 1개 추가의 Loki 부하 — 짧은 창·hostname 스코프로 사전 실측(광역 스캔 금지).
- **[리스크·안B]** sweep bulk UPDATE 와 discovery upsert(같은 사이클) 의 순서 — upsert(ACTIVE+lastSeenAt)
  후 sweep(무접속만 INACTIVE) 이면 방금 갱신분은 안 건드림(정합). 인덱스 없으면 대규모 UPDATE seq scan(D75).
- **[무관]** disabled 20,881 유령 잔존은 본 건과 별개(doc/42 후속 정리 대상).

## 7. 검증 계획

1. uri 표기 확인: 특정 hostname·짧은 창(≤5m)으로 `.cloudbric/pron|afc` 로그 라인 실제 uri 필드
   형태 확인(부하보호 준수, loki-broad-substring-scan 금지).
2. buildLogQL 유닛: excluded-paths 유/무 → 필터 문자열 유무(무회귀 스냅샷).
3. dev(.198) Testcontainers PG: 경로제외로 관측 0 된 도메인이 P7D 경과 후 findDueForScan 제외됨.
4. 스테이징 실측(짧은 창): uri 필터 부착 쿼리 지연·Loki 응답(에러 0) 확인 → 부하 안전 판정.
5. 배포 후: `.cloudbric`-only 도메인 표본의 lastSeenAt 정체 → `activity_status` INACTIVE 전환 관찰,
   실요청/수동 스캔 시 ACTIVE 복귀 + `activity_status_changed_at` 기록 확인.

## 8. 후속 — endpoint-yield 게이트(D83, 유령 억제)

배포 후 운영 관찰에서 도출(3단계 조사). ACTIVE(봇 트래픽으로 유지)인데 스캔하면 self-endpoint 0 인
유령(봇/foreign-host/spoofed Host)이 스캔 슬롯을 잠식. **엣지 제외로는 못 거른다** — 최상위 유입 엣지
`new-PAJ11`(947도메인)이 **실서비스 501 + 유령 439 혼합 catch-all** 이라 엣지째 빼면 실서비스 손실.

**결정 = endpoint 산출 기반 가역 억제**(엣지 아님). `domain_config.ghost_suppressed`(boolean, ddl-auto
default false). **"스캔 대상" = enabled AND activity_status=ACTIVE AND NOT ghost_suppressed**(3축).

- **게이트**(discovery 틱, inactive-sweep 후): `scan.ghost-after`(기본 P7D·0=off). 대상 =
  `discoveredAt < now−ghost-after`(지속성) + 스캔이력(`last_scan_attempt_at` non-null) + self-endpoint 0
  + 무설정(`interval_override`·`base_path_strip`·`spec_record`·`documented_api` 없음, 안전기준 doc/42) →
  `ghost_suppressed=true`. `discoveredAt` null(수동 등록) 제외.
- **가역**: 수동 스캔(`markActive`, scan-now/scan)이 해제. 봇 재관측(upsert)은 해제 안 함(억제 유지).
  자동 복구 없음(GHOST→실서비스 전환 시 수동 스캔 필요) — doc/42 하드삭제 트레이드오프의 가역판.
- **배포 결과(2026-07-22)**: 첫 게이트 `ghostSuppressed=537` → scannable 10,729→10,153, 실서비스 보존.
- **★대소문자 함정**: `EdgeExclusions` 는 대소문자 구분이라 `new-PAJ11`(소문자)이 `NEW-PAJ*` 제외를
  회피한다. 그러나 new-PAJ11 은 실서비스 보유 → 제외하면 안 됨. **대소문자 무시 수정 금지**(수정 시 501 손실).
