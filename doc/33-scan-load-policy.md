# 엔드포인트 스캔 Loki 부하 운영정책 (A–F) + 운영자 온디맨드 스캔 CLI (P3)

> 브랜치 `feature/scan-load-policy`. 근거: doc/05 §2.4/§3/§6(Loki 부하보호·watermark)·doc/30(디스커버리·DomainConfig)·doc/06(배포모델)·doc/12·13(dropped 카운트=메트릭 재사용). 근거 결정 **DECISIONS D45**.
> **설계만. 코드는 dev.** dev 항목은 TASKS subitem(D26). 사용자 승인 정책 A–F + 매니저 추가요구(온디맨드 스캔 CLI) 구현화.

## 0. 배경 / 문제

- 디스커버리가 `max-domains-per-run=0`(무제한)로 관측 전 도메인(**~13,920+**) 등록.
- 현 스캔 `DiscoveryScheduler.scanEnabledDomains()` = `@Scheduled(PT1H)` 가 매 사이클 **enabled 전 도메인 순차 스캔**(`findByEnabledIsTrue()` 전수). 14k × (윈도우/chunk) 쿼리 = 운영 Loki(192.168.8.100) **지속 과부하**.
- watermark 증분(`nextWindow`: watermark 최신이면 skip, 성공 후 `advanceWatermark`)은 **이미 있음** → 정상상태는 델타. 단 **첫 스캔 윈도우 = `end − initialBackfill`(P7D)** → 미스캔 도메인 1회 runScan 이 7일/10분 ≈ **1008 쿼리 × hostnames**. 14k 동시 백필이 재앙.

## 0.1 운영자 관점 요약 (technical_writer 매뉴얼용 — 주기·예산·노브·부하상한 근거)

| 노브(설정) | 의미 | 기본(제안) | 부하/효과 |
|---|---|---|---|
| `scan.tick-interval` | 스캔 틱 간격(짧게) | PT5M | 틱마다 소수 도메인만 → 순간부하 평탄화 |
| `scan.domains-per-tick` | 틱당 도메인 예산(B) | 100 | 틱당 Loki 쿼리 상한 ≈ K×(윈도우/chunk) |
| `scan.max-window` | per-scan 윈도우 상한(A) | PT6H | 백필을 슬라이스 → 1회 스캔 비용 상한 |
| `scan.active-interval` / `inactive-interval` | 활성/비활성 스캔 주기(C) | PT30M / PT6H | 활성 자주·비활성 드물게 |
| `schedule.off-peak-window` | 백필 집중 시간대(D) | 01:00–06:00 | 무거운 백필을 저부하 시간으로 |
| `scan.max-queries-per-hour` | 전역 시간당 쿼리 상한(E) | 3000 | **Loki 부하 하드캡**(초과=다음 틱 이월) |
| `scan.throttle-on-error` | 429/5xx 자동감속(E) | true | Loki 불안정 시 속도 자동 하향 |

**부하 상한 근거**: 순간부하 = `domains-per-tick × (per-scan 윈도우/chunk-window) × hostnames`, LokiClient(동시 2·200ms·page-limit·백오프) 위에 **시간당 전역 쿼리/바이트 예산(E)**이 하드 천장. 전수 1회 커버리지 = `전체도메인 / domains-per-tick × tick-interval`(예 14k/100×5m ≈ **11.7h**) — 작을수록 부하↓·커버리지 느림(트레이드오프, §11).
**즉시 필요 시**: 스케줄을 기다리지 않고 **온디맨드 CLI**(§7)로 특정 도메인 즉시 스캔.

---

# 필수 (B + A + E) — 1 PR 권장

## 1. B. 틱당 예산 + 라운드로빈 (least-recently-scanned)

한 틱에 전 도메인 금지. **짧은 틱(`tick-interval` PT5M)마다 예산(`domains-per-tick` K) 내에서 '오래 안 본 도메인부터' 처리·다음 틱 이월**.

- **선택 쿼리(least-recently-scanned)**: `DomainConfig.lastScanAttemptAt`(신규 필드) **오름차순(nulls first=미스캔 우선)** 상위 K. `DomainConfigRepository` 에 `findByEnabledIsTrueOrderByLastScanAttemptAtAscNullsFirst(Pageable)` (또는 JPQL + `Pageable.ofSize(K)`).
- **라운드로빈 커서 = 영속(권장)**: `lastScanAttemptAt`(Instant, DomainConfig 가산 필드). **메모리 인덱스 미채택** — 재기동 시 소실·14k 동적 집합에 취약. 영속 타임스탬프가 자연스러운 LRS 키(재기동 생존).
- **★커서 전진은 attempt 마다**(skip 포함): 한 도메인을 picked 하면 **runScan 전/후로 `lastScanAttemptAt=now` 갱신** — `nextWindow` 가 empty(watermark 최신)라 runScan 이 조기 skip 해도 큐 뒤로 회전. 안 그러면 up-to-date 도메인을 매 틱 재선택해 예산 낭비.
- **이월**: 틱은 K(또는 E 예산 소진)까지만 처리, 나머지는 다음 틱이 이어받음(커서가 자연 진행). `fixedDelay` 전수순회 → **슬라이스 순회**로 교체.
- **기아 방지**: `lastScanAttemptAt` asc = FIFO → 최악 대기 = 전수 1회 커버리지 시간(유한). 영구 기아 없음.
- **watermark 관계**: watermark(`lastEnd`)=데이터 윈도우 진행(무엇을 수집했나), `lastScanAttemptAt`=스케줄 공정성 커서(언제 차례를 줬나). **직교**(둘 다 필요).

## 2. A. watermark 증분 확정 + per-scan 윈도우 상한

watermark 증분은 동작 중 → **확정 + 윈도우 상한 보강**으로 백필 슬라이스화. line 105 주석 "윈도우 산정 watermark 기반(TODO)" 해소.

- **per-scan 윈도우 상한(핵심)**: `windowFor` 에 `max-window` 적용 — `if (end − start) > maxWindow → end = start + maxWindow`. → 미스캔/지연 도메인의 1회 runScan 이 **최대 max-window** 만 수집(7일 일괄 pull 차단), watermark 가 max-window 씩 전진 → **여러 틱에 걸쳐 점진 백필**.
- **정상상태**: window=[lastEnd, now−lag) = **직전 스캔 이후 경과분**(자연히 작음, 보통 tier interval 수준) → 상한 미발동.
- **수렴/staleness(정직)**: 저티어 도메인이 사이클당 1회·max-window 로만 전진하면 실시간 대비 **항상 ~사이클-시간 뒤처짐**(예 ~12h). **엔드포인트 디스커버리엔 허용**(실시간 경보 아님). 활성 티어(C, 30분)는 near-current. 백필 수렴은 off-peak(D)에서 윈도우/예산 상향으로 가속.
- **확정**: `nextWindow` empty=skip, `advanceWatermark`=성공 후만(at-least-once) — 유지. `initialBackfill`(P7D)는 **윈도우 상한과 무관하게 백필 '시작점'** 만 정함(첫 start=end−backfill, 단 한 번에 max-window 만 처리).

## 3. E. 전역 레이트 가드 + 자동감속

LokiClient 보호(동시 2·200ms·page-limit·MAX_ATTEMPTS 백오프) **위에** 전역 예산·자동감속.

- **전역 시간당 예산(`LokiBudget` 신규)**: 현재 시간창 누적 **쿼리 수 + 응답 바이트** 카운트(시간 롤오버 리셋). 틱 루프가 도메인마다 예산 체크 — `max-queries-per-hour`(또는 `max-bytes-per-hour`) 초과 시 **틱 조기 종료(이월)**. = Loki 부하 **하드 천장**.
- **자동감속(`throttle-on-error`)**: LokiClient 에 **적응형 throttle 배수** — 429/5xx 발생 시 inter-query 간격 ×배수(상한까지), 연속 성공 시 감쇠. 현 per-attempt 백오프(일시적)에 더해 **지속 슬로다운**.
- **메트릭(P3 TODO 연계)**: LokiClient 에 Micrometer `Counter`(쿼리수·바이트·429/5xx) 계측 → Actuator `/metrics`. doc/12 `DroppedNonApi`·doc/13 `DroppedByLimit` 카운트와 함께 노출. **정확도 한계(정직)**: 바이트는 응답 본문 길이 근사(헤더/압축 제외), 시간창 경계 카운트는 ±1틱 오차 — 운영 가시화엔 충분.
- **HA**: 단일 인스턴스 전제 시 in-memory 예산 OK. HA(복수) 시 인스턴스별 예산=2×부하 → **DB-backed 예산 또는 ShedLock 게이트 필요**(기존 P3 HA TODO 와 함께, §9).

---

# 다음 (C + D) — 후속 PR 권장

## 4. C. 활동 기반 티어링 (per-domain 주기 차등)

- **티어**: lastSeenAt(디스커버리 갱신, doc/30)·검출활동(hits/최근 discovered)으로 active(짧게 PT30M)·default(PT2H)·inactive(길게 PT6H+) 구분.
- **due 판정**: 틱 선택을 "enabled **AND due**(`now − lastScanAttemptAt ≥ tierInterval`) ORDER BY lastScanAttemptAt asc LIMIT K" 로 — 티어가 빈도 변조. `DomainConfig.intervalOverride`(기존, 미배선) = 명시 per-domain override(티어보다 우선) → **기존 P3 'intervalOverride 스케줄 반영' TODO 를 이 정책으로 배선**.
- active 신호: `now − lastSeenAt ≤ active-threshold`(예 24h). 무회귀: 미설정/콜드=default 티어.

## 5. D. off-peak 백필

- **off-peak(`schedule.off-peak-window` 01–06, 현재 config-only·미배선 → 여기서 배선)**: 백필(watermark 많이 뒤처진 도메인)을 off-peak 에 **큰 예산·큰 윈도우**(`off-peak-domains-per-tick`·`off-peak-max-window`)로 집중. peak 엔 **델타·active 티어만**, 백필 도메인 디프라이오리티.
- 구현: 틱이 현재시각이 off-peak 인지 판정 → 예산/윈도우상한/선택조건 스위치(peak: due+active 우선·max-window 작게 / off-peak: 백필(lastEnd 오래된 순) 우선·max-window 크게).

---

# 보조 (F) — 선택, 후속

## 6. F. 비활성 디프라이오리티 (삭제 아님)

- `now − lastSeenAt > dormant-after`(예 14일) 도메인 → **최장 티어(dormant-interval, 예 1일)** 로 강등. **삭제 절대 없음**(무삭제 요건, doc/30 D42 일관) — 우선순위 강등만. C 티어링의 dormant 티어 확장.

---

# 매니저 추가요구 — 운영자 온디맨드 스캔 CLI

## 7. 즉시 스캔 CLI (B 패턴, runOnDemand 재사용)

운영자가 특정 도메인 API 정보가 **즉시** 필요할 때 스케줄러를 안 기다리고 CLI 로 바로 스캔.

- **명령**: `--adc.cli.scan-domain=<domain>` [옵션 `--adc.cli.window=PT1H` / `--adc.cli.edge=<hostname>`]. 무인자=서버, `export-domain`(기존 CSV)·`scan-domain`(신규) 분기. `@Profile("cli")`·`web(NONE)`·스케줄러 미기동 = 기존(doc/31 §B1) 동일.
- **동작**: 윈도우 산정(기본 `window` 옵션 또는 `scan.max-window` 기본, **상한=max-window**) → 수집(--edge 지정 시 그 엣지만 `runOnDemand(edge,domain,win)`; 미지정 시 도메인 hostnames 전체 collect) → `analyze`(ScanResult 영속 + discovered_endpoint upsert).
- **★watermark 관계(설계 답)**: 온디맨드는 **임시 윈도우만, watermark 전진 안 함**(`runOnDemand` 이 현재도 advanceWatermark 미호출 — 기존 동작). 이유: 즉시 스캔이 [now−1h, now) 을 보고 watermark 를 당기면 스케줄러가 [lastEnd, now−1h) 를 **skip → 데이터 갭**. 임시 스냅샷은 누적(discovered_endpoint)·최신 결과(ScanResult)만 갱신하고, 증분 진행은 스케줄러가 계속 소유.
- **출력/종료코드**: 스캔 후 **요약(검출 수·Active/Zombie/Shadow/Unused 카운트) 출력** + exit(성공 0 / 도메인 미존재·미enabled 비0 / Loki 실패 비0). 기존 `CliExportRunner` exit 패턴 동형.
- **Loki 부하**: 단일 도메인 즉시 스캔도 **LokiClient 보호·E 예산 준수**(윈도우 상한·스로틀·동시·백오프). 운영 대용량은 off-peak 권장 문구.
- **scan→export 조합(과설계 금지)**: `scan-domain` 과 `export-domain` 은 **독립 명령** — 즉시 스캔 후 CSV 가 필요하면 **순차 2회 호출**(scan→export)로 조합. 단일 호출 체인 플래그는 불요(YAGNI)·필요 시 후속.

---

## 8. 설정 키 체계 + 기본값 (제안)

`ApiDiscoverProperties` 에 nested `Scan` 레코드 추가(기존 Loki/Schedule/Discovery 패턴). `schedule.off-peak-window`·`ingest-lag`·`initial-backfill` 재사용.
```
apidiscover.scan:
  tick-interval: PT5M            # B 스캔 틱(기존 schedule.default-interval PT1H 대체)
  domains-per-tick: 100          # B 틱당 도메인 예산
  max-window: PT6H               # A per-scan 윈도우 상한
  max-queries-per-hour: 3000     # E 전역 쿼리 하드캡(0=무제한)
  max-bytes-per-hour: 0          # E 전역 바이트 캡(0=무제한)
  throttle-on-error: true        # E 429/5xx 자동감속
  # --- 다음(C/D) ---
  active-interval: PT30M
  default-interval: PT2H
  inactive-interval: PT6H
  active-threshold: PT24H        # lastSeenAt 이내=active 티어
  off-peak-domains-per-tick: 500 # D off-peak 예산 상향
  off-peak-max-window: PT24H     # D off-peak 백필 윈도우 상향
  # --- 보조(F) ---
  dormant-after: P14D
  dormant-interval: P1D
```
- 전부 신규 키(기존 설정 불변). `tick-interval` 이 구 `schedule.default-interval` 의 스캔 트리거 역할 대체(그 키는 온디맨드 `defaultWindow`·디스커버리 등 잔여 용도 확인 후 정리).

## 9. 변경 범위 + HA

- **`DiscoveryScheduler`**: `scanEnabledDomains()`(전수) → `scanTick()`(`@Scheduled(scan.tick-interval)`, LRS K 선택·due 판정[C]·off-peak 스위치[D]·E 예산 체크·도메인별 runScan·`lastScanAttemptAt` 갱신). 도메인 격리(try/catch) 유지.
- **`DiscoveryJobService`**: `windowFor` 에 max-window 상한 인자(A). 온디맨드 도메인 스캔 진입점(§7, collect+analyze, **watermark 미전진**) — `runOnDemand`(edge) + 신규 `scanOnDemand(domain, window, edgeOrNull)`. line 105 TODO 주석 해소.
- **신규**: `ScanSelector`(LRS+티어+off-peak 선택 로직, 테스트 가능 분리), `LokiBudget`(E), `LokiClient` Micrometer 계측 + 적응형 throttle, `CliScanRunner`(§7, @Profile cli).
- **신규 필드**: `DomainConfig.lastScanAttemptAt`(B 커서, ddl-auto nullable). (티어는 lastSeenAt 에서 계산 — 저장 불요.)
- **HA(단일 인스턴스 전제)**: `lastScanAttemptAt` 커서·in-memory 예산은 단일 인스턴스 가정. **복수 인스턴스 시** 중복 스캔·예산 2배 → 기존 **P3 HA TODO(ShedLock/Quartz)** 로 스케줄러 단일 실행 보장 + 예산 DB-backing 필요. **여기서 HA 미해결**(기존 TODO 와 충돌 없이 전제만 명시).

## 10. 구현 스테이지 권고 (매니저 최종결정)

- **PR1 = 필수 B + A + E**(+ §7 온디맨드 CLI 동봉 권장 — 작고 독립, 운영자 즉시스캔 + 부하정책을 한 릴리스로). 14k 과부하를 즉시 해소하는 핵심.
- **PR2 = 다음 C + D**(티어링 + off-peak — 정교화, 필수 위에 빌드).
- **PR3 = 보조 F**(dormant 디프라이오리티, 선택).
- 근거: B/A/E 가 과부하 해소 본질(먼저·격리 리뷰). C/D 는 최적화(B 의 선택 로직 확장이라 위에 자연 적층). F 는 선택적 꼬리. 온디맨드 CLI 는 runOnDemand+CLI(PR#23) 재사용이라 PR1 에 저비용 동봉. (분리 원하면 PR1.5 로 빼도 무방 — 매니저 결정.)

## 11. 무회귀 / 리스크 (정직)

- **무회귀**: 신규 컴포넌트/설정/필드 가산. `analyze`·`runScan`·watermark·LokiClient 보호 코어 불변(max-window 는 추가 상한일 뿐, 미설정 시 현행). 온디맨드/CLI 는 게이트 분리(서버 모드 무영향). `lastScanAttemptAt` ddl-auto nullable.
- **리스크①(전수 커버리지 시간↑)**: domains-per-tick 작을수록 부하↓·1회 커버리지 느림(14k/100×5m≈11.7h). 운영 노브로 조정, off-peak(D)·티어(C)가 완화.
- **리스크②(백필 수렴/staleness)**: max-window < 사이클시간 이면 저티어 도메인이 실시간 대비 뒤처짐(§2) — 디스커버리엔 허용, off-peak 가속.
- **리스크③(기아)**: LRS FIFO 로 영구기아 없음, 최악대기=커버리지 시간(유한).
- **리스크④(메트릭 정확도)**: 바이트 근사·시간창 경계 오차(§3) — 가시화 충분, 정밀 과금용 아님.
- **리스크⑤(HA)**: 단일 인스턴스 전제(§9). 복수 시 중복·예산초과 — 기존 HA TODO 와 함께 해결.

## 12. dev 구현 체크리스트 (TASKS subitem, D26)

**PR1 — 필수 (B+A+E) + 온디맨드 CLI** — *구현 완료(PR, build green 374·실패 0·skip 2=live 게이트)*
- [x] (A) `windowFor` max-window 상한 인자(5-arg) + 적용(백필 슬라이스, 0/null=무제한 무회귀), line 105 TODO 주석 해소. `nextWindow` 가 `scan.max-window` 전달.
- [x] (B) `DomainConfig.lastScanAttemptAt`(ddl-auto) + `DomainConfigRepository.findByEnabledIsTrue(Pageable)`(호출측 Sort asc NULLS FIRST) + `touchLastScanAttempt`(@Modifying 단일 컬럼=lost-update 무관).
- [x] (B) `ScanSelector`(LRS K, PR2 확장점) + `DiscoveryScheduler.scanTick()`(@Scheduled scan.tick-interval, K 선택·attempt 마다 커서 전진[skip·실패 포함]·예산 소진 break 이월) — 전수순회 대체.
- [x] (E) `LokiBudget`(시간당 쿼리/바이트 하드캡, 초과=hasBudget false 이월, 0=무제한) + `LokiClient` Micrometer 계측(loki.queries·response.bytes·errors{status}) + 적응형 throttle(429/5xx level+1·성공 −1, throttle-on-error 게이트, ≤16×).
- [x] (§7) `CliScanRunner`(@Profile cli, `--adc.cli.scan-domain`/`--window`/`--edge`) + `scanOnDemand`(edge→runOnDemand/미지정→collect+analyze, **watermark 미전진**) + `onDemandWindow`(상한=max-window) + 요약·exit code(0/2/3/4). main() scan-domain 분기, CliExportRunner blank=no-op(명령 공존).
- [x] `ApiDiscoverProperties.Scan` 레코드 + application.yml 기본값(§8, PR1 키만 — C/D/F 키는 PR2/PR3 시 추가).
- [x] 테스트 — windowFor 상한/LRS nulls-first(@DataJpaTest)/scanTick 커서 전진(skip·실패)·예산 break/budget 캡·롤오버·Micrometer/온디맨드 exit·scanOnDemand 위임(watermark 미전진). 운영 Loki 보호(단위 mock, 실호출 `-Dloki.live` 게이트 미실행).

**PR2 — 다음 (C+D)**
- [ ] (C) 티어(lastSeenAt/활동) + due 판정 선택 + `intervalOverride` 배선(기존 TODO 해소).
- [ ] (D) off-peak 윈도우 배선(`schedule.off-peak-window`) — off-peak 백필 우선·예산/윈도우 상향, peak 델타·active 우선.

**PR3 — 보조 (F, 선택)**
- [ ] (F) dormant 티어(`dormant-after`→최장 주기, 무삭제).

## 13. 범위 밖 / 후속

- **HA 단일 실행·예산 DB-backing** — 기존 P3 HA TODO(ShedLock/Quartz)에서.
- **Spring Batch JobRepository 연결** — 별도 P3 TODO(현 `@Scheduled`).
- **scan→export 단일 체인 플래그** — 순차 호출로 충족(§7), 필요 시 후속.
- **메트릭 알람(임계·통지)** — Actuator 노출 후 알람 연동은 별도 P3.
