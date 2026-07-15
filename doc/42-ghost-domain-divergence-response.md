# 42. 스캔 due/워터마크 발산 대응 — 유령(endpoint 0) 도메인 억제·정리 설계

> 상태: **설계(architect, 2026-07-14)** — 실제 DB 정리(파괴적)는 리뷰·사용자 승인 후 별도 실행.
> 근거 실측: 매니저 조사(2026-07-14) + architect 운영 DB read-only 재실측(본문 표기).

## 0. 요약

봇/스캐너/스푸핑 트래픽의 Host 헤더가 캐치올 엣지를 거쳐 무검증 자동등록되어, 스캔해도
자사 endpoint 가 0 인 **유령 도메인 ~39.5k** 가 due 큐의 66%를 잠식 — 밀림 p50 4.4일/max 14일로 발산.
대응은 4개, 즉효(A·B)와 근본(C)을 분리한다.

| 항목 | 성격 | 효과 | 규모 |
|---|---|---|---|
| A. `NEW-PAJ*` 엣지 제외 | 즉효(설정 1행) | 봇 Host 주요 유입원 1곳 차단 | 설정+재배포 |
| B. 유령 도메인 대량 **하드 삭제** | 즉효(ops 1회) | due 큐 즉시 회복(−~31k enabled) | runbook SQL |
| C. discovery 등록 status 신뢰 필터 | **근본** | 유령 신규 등록·재유입 원천 억제 | 소규모 코드 |
| D. `last_access_log_at` 백필 | 정합(ops 1회) | 무접속 오분류 3,464건 해소 | SQL 1문 |

> **정리 방식 = hard DELETE(사용자 결정)**. soft(enabled=false) 아님 — 근거·트레이드오프는 §4.2, 결정 기록은 DECISIONS D81.

권장 순서 = **A+C 한 PR/배포 → B+D 승인 후 ops 창 1회**. B 는 A·C 배포 이후 실행해야
재유입이 막힌 상태에서 정리된다.

## 1. 현황 실측 (2026-07-14)

- enabled 67,176 · due 53,731 · 밀림 p50 4.4일/max 14일 · 워터마크 near-now(30m) 6.7%
  (세션 73, 5일 전: due 15.6k·밀림 2.6h → 폭증).
- **유령(enabled & discovered_endpoint 0): 39,526** — 전건 스캔 이력 있음(`last_scan_attempt_at` non-null).
  - 그중 **discovered_at ≥7일 경과: 28,712** (반복 스캔에도 지속 0 = 정리 1차 대상).
  - 최근 3일 내 `last_seen_at` 갱신: 8,898 — 봇 재방문이 계속 lastSeenAt 을 갱신해
    inactive-after(P3D) 게이트를 **진동**시킴(제외→봇 재방문→재포함→스캔 0→제외…).
  - 사용자 설정(interval_override·base_path_strip) 보유: **0건** — 정리 안전.
- 유령의 유입 엣지는 광범위(캐치올 다수): AHJ11 5,511 · AOBE-NRT-2602M0 2,671 · AOKD2 2,065 ·
  AAI22 2,042 … — **NEW-PAJ\* 는 유입원 중 하나**(전부가 아님). A 단독으로는 부족, C 가 근본.
- `NEW-PAJ*` 실측: NEW-PAJ21(784)·41(688)·51(686)·31(657)·61(34) 5종 — 접두 패턴 정확 일치,
  `NEW-` 로 시작하는 다른 엣지 없음.
- 제외엣지 전용(hostnames 전부 AAJ+P\*+NEW-PAJ\*) enabled: **3,109** (그중 유령 510) — 스캔불가 버킷.
- endpoint 有 & `last_access_log_at` NULL: **3,464** (매니저 실측 1,718 은 부분집합 컷) —
  전건 `max(discovered_endpoint.last_seen)` 보유 = 백필 가능.

## 2. 근본 원인 체인

1. 봇/스캐너/스푸핑/프록시가 임의 Host 헤더로 엣지에 요청(sea01.ecogra.org=PAN 스캐너 470,
   ha-111.com=외부 도박 프록시 등). 캐치올 엣지는 이를 access log idx15(host)에 그대로 기록.
2. discovery 는 (host, edge) 서버측 집계를 **status 무관** 수신 → FQDN 형식만 통과하면
   `domain_config` 자동등록 + 매 관측 `lastSeenAt` 갱신. **Host 헤더는 클라이언트 통제값 =
   그 자체로 신뢰 불가**하나 현재 검증 축이 형식(FQDN regex)뿐.
3. 스캔은 그 도메인의 Loki 로그를 수집하지만 자사 서비스가 아니므로 endpoint 0.
   inactive-after(P3D)가 제외해도 봇 재방문이 lastSeenAt 을 되살려 due 재진입(§1 진동).
4. 유령 39.5k 가 due 선택 슬롯·Loki 예산을 잠식 → 실 도메인 워터마크 밀림 발산.

제외 엣지(AAJ·P\*) 오염은 P\* 전용 302(0.56%)·AAJ 0 으로 미미 — 주범 아님(매니저 확인).

## 3. endpoint 0 의 4경로와 정리 안전기준

`discovered_endpoint` 저장(upsertDiscovered, DiscoveryJobService:450)은 InventoryBuilder 결과
기반으로 **분류(API/non-API) 무관** — non-API(정적/웹페이지)여도 자사 Host 요청이 있으면 행이
생긴다. 따라서 'non-API 라서 0' 오탐은 없다. endpoint 0 이 나오는 경로는 4가지.

| # | 경로 | 성격 | 안전기준 반영 |
|---|---|---|---|
| ① | Loki 에 자사 Host 로그 0 (봇/스푸핑 = 실 트래픽 0) | **진짜 유령(주 경로)** | 정리 대상 |
| ② | foreign-host 필터(:394, 양변 normalize)·real_host 폴백·포트 등 정규화 엣지케이스 | 오탐 여지(낮음) | 지속성 기준(b)으로 흡수 + 가역 복구(§4.2) |
| ③ | 제외엣지 전용 → collectBounded 조회 0 | **트래픽 0 아님 — 스캔불가** | **별도 정책 버킷**(§4.3) |
| ④ | 일시 수집실패/타임아웃/oversize(D68) | 일시적(재시도 해소) | 지속성 기준(b)으로 배제 |

**안전기준 — 'endpoint 0' 단독 판정 금지.** 정리 대상은 다음 전부 충족 시에만.

- (a) 스캔 이력 있음: `last_scan_attempt_at IS NOT NULL` (실측: 유령 전건 충족).
- (b) 지속성: `discovered_at <= now() - 7일` — 7일간 반복 스캔·반복 due 재선택에도 0.
  discovery 는 12분 창 연속 관측이므로 실 트래픽이 있으면 lastSeenAt 갱신→재스캔→표본
  노출이 반복된다. 7일 무증거 = ②·④ 일시 요인 배제.
- (c) ③ 제외엣지 전용은 endpoint 유무와 무관하게 별도 버킷(스캔불가 정책 정리, D62 선례).
- (d) 사용자/운영자 흔적 보호: `interval_override IS NULL AND base_path_strip IS NULL`,
  `spec_record`·`documented_api`·`domain_classification_config`(도메인별 분류정책=운영자 흔적) 없음
  (스펙 업로드·분류정책 설정 도메인은 절대 자동 삭제 금지). 실측 0건이나 술어로 방어(QA P2).

## 4. 설계

### 4.1 A — `NEW-PAJ*` 엣지 제외 (사용자 지시, 즉효)

`application.yml` `discovery.excluded-hostnames` 에 `"NEW-PAJ*"` 1항목 추가.
`EdgeExclusions` 의 `*` 접미 접두 매처(D69) 그대로 재사용 — 코드 변경 없음(설정 1행).
D67 원칙대로 기본값 승격(재배포 필요, baked defaults).

- 효과: NEW-PAJ\* 관측 샘플 skip → 그 엣지 전용 봇 Host 는 미등록·lastSeenAt 미갱신,
  기존 등록분은 P3D 게이트 자연 제외(D62 소프트 제외 의미). 스캔 측도 effectiveEdges 에서
  매핑 제거(전부 제외면 조회 0 skip).
- 한계(정직): §1 실측대로 유령 유입 엣지는 AHJ11 등 광범위 — A 는 지시된 유입원 1곳 차단.
  나머지 캐치올 엣지는 업무 트래픽 혼재라 엣지 제외로 풀 수 없고 C(status 필터)가 담당.

### 4.2 B — 유령 도메인 대량 하드 삭제 (즉효, 승인 후 ops)

**방식 = `DELETE` 하드 삭제 (사용자 결정).** 설계 초안은 백업 테이블 선행을 권했으나, **최종 실행(2026-07-15)은
사용자 결정으로 백업 없이** 진행했다(백업 미생성) — 복구는 자기치유뿐. 대신 dry-run 프리뷰·pre/post-guard 로 방어(§7).

> 초안은 soft(`enabled=false`)를 권했으나 — 그 명분(재유입 sticky 차단)이 **C 도입으로 대체**되어
> 사용자가 hard DELETE 를 확정했다. 아래 근거·트레이드오프. (결정 기록: DECISIONS D81.)

- 근거 ① **C 가 재유입을 원천 차단**: soft 의 원래 명분은 "`DomainUpserter.upsert` 가 재관측 시
  재-INSERT(enabled=true)하므로 DELETE 는 무효화된다"였다. 그러나 C(status 신뢰 필터)가 프로브/스푸핑
  Host 의 재관측 자체를 막으므로, **재-INSERT 되는 것은 실 트래픽뿐 = 자기치유**. 삭제가 무효화되는
  게 아니라, 되살아나면 그건 정상 도메인이라는 신호다.
- 근거 ② **가역성은 백업으로 확보**: 백업 테이블(§7) 스냅샷 → 오분류 시 롤백 재INSERT(runbook §5).
  soft 의 "행 보존" 가역성을 백업이 대신한다.
- 근거 ③ 완전 정리 선호: 비활성 행이 남아 카운트·조회에 계속 노출되는 것보다 due 큐를 실제로 축소.
- **★트레이드오프(정직)**: hard DELETE 는 재트래픽으로 재등록 시 `discovered_at`/firstSeen 가 **리셋
  = 이력 손실**(§3 (b) 7일 지속성 기준을 처음부터 재누적). soft 는 이력을 보존하나 재활성이 **수동**
  (`PUT /domains` 또는 일괄 UPDATE)이고 비활성 행이 잔존한다. → 사용자는 자기치유(자동 재등록)의
  단순함을 이력 보존보다 우선, 이력 손실 위험은 **백업 + 7일 지속성 기준**으로 완화.
- D48-F 의 "삭제·비활성 없음"은 **자동 스케줄러 정책**에 대한 결정 — 이번은 운영자 승인
  1회 정리라 상충 아님(경계 명시).

**대상 = §3 안전기준 (a)+(b)+(d) 전부 충족한 유령** — 실측 28,712건(2026-07-14 기준, 시간 술어라
실행 시점 재산출). 7일 미경과 잔여(~10.8k)는 정리하지 않고, C 배포 후 lastSeenAt 갱신이 끊겨
P3D 게이트가 자연 제외 — 필요 시 7일 후 동일 runbook 재실행(기준이 시간 술어라 멱등).

**오분류 복구(자기치유) — C 와 결합.** hard DELETE 라 복구는 **자동 재등록**이 기본이다. C 배포로
프로브 재유입이 막힌 상태에서 삭제된 도메인에 실 트래픽이 다시 오면 discovery 가 신뢰 status 관측으로
**재-INSERT**(= 정상 도메인이라는 신호). 삭제 후 재등장은 `discovered_at > 정리시각` 쿼리 1개로 확인된다
(runbook §5(c)). 즉시·특정 도메인 복구가 필요하면 백업 테이블에서 롤백 재INSERT(§5(a)).

**효과 추정**: 도메인 67,176 → 약 35.9k(−28,712 유령 −3,109 제외엣지 전용, 중복 510 = 삭제된 행).
due 잠식 66%가 걷혀 실 도메인 위주 큐로 회복 — 워터마크 near-now 재수렴 기대
(정착 확인은 배포 후 밀림추이 모니터링, 매뉴얼 scan-tick 콜아웃 절차).

### 4.3 B-2 — 제외엣지 전용 버킷 (③, D62 플레이북)

hostnames 가 전부 제외 엣지(AAJ 23 + P\* + NEW-PAJ\*)인 enabled 도메인 3,109건.
트래픽 유무와 무관하게 **스캔불가**(effectiveEdges empty → 매 due 선택마다 skip 공회전).
B 와 동일 방식(hard DELETE, 사용자 결정) — 백업 → 하드 삭제(domain_hostnames→…→domain_config).
혼합 도메인(제외+비제외 엣지 혼재)은 대상 아님 — 잔여 비제외 엣지로 계속 스캔되므로 `EXISTS` 로
'전부 제외'만 선별. 유령 버킷과 겹치는 510건은 `NOT EXISTS` dedup 으로 1회만 삭제(멱등).

### 4.4 C — discovery 등록 status 신뢰 필터 (근본)

Host 헤더는 위조 가능 = 검증 불가. 실용적 신뢰 프록시는 **status**: 엣지/백엔드가 그 vhost 를
실제 서빙했는가. 스푸핑/프로브 Host 는 전형적으로 404(미존재 vhost)·470(WAAP 차단, PAN 스캐너
실측)을 받는다.

**구현(소규모, `DomainDiscoveryService` 한정)**

- `buildPattern()`: F_STATUS(=9) 위치를 `<_>` 대신 `<status>` 로 명명(LogLineParser 상수 공유 —
  기존 드리프트 차단 방식 그대로).
- `buildLogQL()`: pattern 뒤에 라벨 필터 `| status !~ "404|470"` 추가. Loki 라벨 매처는 전체
  앵커 정규식이라 정확 일치 제외. pattern 파싱은 이미 수행 중이라 추가 비용 미미
  (라벨 필터 1개 — 광역 |= 추가 아님, Loki 부하 안전).
- 설정: `discovery.probe-statuses`(List, 기본 `[404, 470]`, **빈 리스트 = 필터 없음 = 현행 무회귀**).
  470 은 사이트 고유 차단코드라 설정 노출이 정당(D67 excluded-hostnames 동형).

**효과**

- 프로브-status 뿐인 Host 는 **등록도, lastSeenAt 갱신도 안 됨** → 신규 유령 원천 억제 +
  기존 유령 재유입(§2 진동) 종식 + B 오분류 복구 신호 확보(§4.2).
- 5xx 는 제외하지 않음(백엔드 실존 증거). 401/403 도 유지(실 API 의 정상 응답 — 단 WAAP
  차단이 403 으로 나오는 엣지가 확인되면 probe-statuses 에 사이트 설정으로 추가).

**트레이드오프(정직)**

- lastSeenAt 의미가 "아무 트래픽 관측"→"신뢰 status 트래픽 관측"으로 좁아진다. 특정 창에
  404 만 있는 실 도메인은 그 창의 delta-skip 이 스캔을 미룰 수 있다(Zombie 증거 404 포함).
  정상 도메인은 2xx 혼재가 일반적이라 영향은 한계적이고, **스캔 수집 자체는 status 무필터**
  (전 라인 수집)라 스캔이 돌면 404 라인도 그대로 잡힌다.
- ha-111.com 류 "엣지가 실제 프록시(2xx)하는 외부 도메인"은 status 로 못 거른다 — 이는
  A(엣지 제외)의 몫이고, 잔여는 B 기준(스캔해도 endpoint 0 지속)이 잡는다.
- 서버측 필터라 "제외된 프로브 관측 수" 클라이언트 카운트는 불가(로그 가시성 한계 수용).

### 4.5 D — `last_access_log_at` NULL 정합 (버그 아님, 백필 1회)

**점검 결론: `touchLastAccessLogAt` 로직은 정상.** never-decrease 직접 UPDATE(:461)이며
호출 조건(관측 discovered 비어 있으면 미갱신)은 설계 의도(D56). NULL 3,464건의 원인은
코드가 아니라 **이력**이다.

- 해당 도메인의 endpoint 행들은 D56(touch 도입, 2026-07-01) **이전** 스캔이 만든 것이고,
  이후 delta-skip(D60/D61)·롤링 샘플(D66)로 비어 있지 않은 analyze 가 다시 돈 적이 없어
  touch 가 한 번도 실행되지 않았다(rejecter.js-v1.com hits 10k = 과거 누적치).
- D59 이후 이 컬럼은 게이트 미사용·정보성(소비처 실측 0) — 그러나 **정리·운영 판단에서
  "NULL=무접속" 으로 읽히는 오분류**를 만들므로 정합화한다.

**조치(코드 무변경)** — ops SQL 1문 백필. 전건 소스값 존재 실측(§1).

```sql
UPDATE domain_config dc SET last_access_log_at = m.max_seen
FROM (SELECT host, max(last_seen) AS max_seen FROM discovered_endpoint GROUP BY host) m
WHERE m.host = dc.host AND dc.last_access_log_at IS NULL;
```

never-decrease 의미와 일치(NULL→값)·멱등. 아울러 **B 정리 기준에 last_access_log_at 을
쓰지 않는다**(§3 기준은 discovered_endpoint 존재·스캔이력·지속성만 사용) — 이중 방어.

## 5. 우선순위·실행 순서

| 순서 | 무엇 | 종류 | 게이트 |
|---|---|---|---|
| 1 | A + C (한 PR: 설정 1행 + status 필터) | 코드/설정 → 재배포 | 리뷰·build green |
| 2 | 짧은 관찰(1~2일): discovery 등록률·rejected 로그·신규 유령 유입/일 | 모니터링 | — |
| 3 | B + B-2 + D (runbook, 백업→하드삭제→백필→검증) | **파괴적 ops** | **사용자 승인 필수** |
| 4 | 사후: due·밀림추이·near-now 비율, 삭제 후 재등록(discovered_at) 자기치유 점검 | 모니터링 | — |

## 6. 리스크

| 리스크 | 심각도 | 완화 |
|---|---|---|
| B 오분류(실 도메인 삭제) — ② 정규화 엣지케이스·극저빈도 트래픽 | 중 | 7일 지속성 기준 + 백업 롤백(§7·runbook §5) + C 결합 자기치유(재트래픽→자동 재등록, §4.2) + scan-now 는 ScanSelector 미경유라 명시 스캔 가능 |
| C 로 인한 delta-skip 지연(404-only 창) | 낮 | 스캔 수집은 무필터·2xx 혼재 일반적, 관찰 후 probe-statuses 조정 |
| A/C 배포 전 B 실행 시 재유입 | 중 | 실행 순서 강제(§5) — B 는 A+C 배포 후 |
| 정리 규모 오판(시간 경과로 대상 변동) | 낮 | runbook 이 실행 시점 재산출(시간 술어)·카운트 검증 후 커밋 |
| 470 의미 사이트 종속 | 낮 | 설정 노출(probe-statuses), 기본 [404,470] |
| NEW-PAJ 외 신규 캐치올 엣지 등장 | 낮 | C 가 엣지 무관 근본 억제 — 엣지 제외는 지시 시 추가 |

## 7. ops runbook — 실행 완료(2026-07-15) 절차

> **실행 권위본 = `sample/ghost_domain_cleanup.sql`**(dry-run 프리뷰·앱 정지 래퍼·세션 temp 삭제셋·pre/post-guard 포함).
> 아래는 실제 실행 절차 요약. **★사용자 최종 결정: 백업 없이 실행**(백업 테이블 미생성) — 복구는 자기치유(C+실 트래픽 재등록)뿐.

**★데드락 회피(실측 교훈)**: 1차 시도(앱 가동 중 원자 DELETE)는 discovery/scan 의 `domain_config`·`watermark` 동시 쓰기와
**데드락** → 트랜잭션 통째 롤백(무손실·미완). ∴ 삭제 전 `adc-app` 컨테이너를 잠깐 멈춰 경합을 제거한다(`adc-db` 유지,
다운타임 ~2분 — 백그라운드 워커라 서빙 무영향, 재기동 시 워터마크에서 이어감).

**실행 순서**(shell + SQL):
1. **dry-run 프리뷰**(읽기전용): 삭제셋을 세션 temp `del_hosts` 에 빌드 → 버킷별·테이블별 삭제 예정 건수 + `unsafe=0` 육안 확인.
2. `podman stop -t 30 adc-app`(`adc-db` 유지) — discovery/scan 경합 제거(데드락 회피).
3. **원자 트랜잭션**(psql): `del_hosts` 재빌드 → PRE-GUARD(예상 밴드 이탈 시 `RAISE`→롤백) → `BEGIN;`
   DELETE FK 순서(`domain_hostnames→watermark→scan_result→discovered_endpoint→domain_config`) → D 백필(§4.5) →
   POST-GUARD(`orphan=0`·enabled 밴드) → 사후 검증 → `COMMIT;`.
4. `podman start adc-app` → `/actuator/health` UP 확인.

**백업 없는 삭제 안전장치**(가역성 없으므로 사전·자동 방어로 대체): (i) dry-run 프리뷰 육안 확인,
(ii) 세션 temp `del_hosts` 로 삭제셋 1회 고정(양 버킷·전 테이블 동일 집합 — 삭제 중 술어 재평가 드리프트 차단),
(iii) PRE/POST-GUARD 가 예상 밴드 이탈·`orphan≠0` 시 `RAISE EXCEPTION`→롤백, (iv) 단일 원자 트랜잭션.

**복구(오분류)**: 백업 없음 → 재적재 불가. C 로 프로브 재유입이 막힌 상태에서 삭제 도메인에 실 트래픽이 오면 discovery 가
재등록(자기치유). 확인: `SELECT host, discovered_at FROM domain_config WHERE discovered_at > '<삭제시각>'`.
(`discovered_at` 리셋 = 수용된 트레이드오프, DECISIONS D81.)

**실행 결과(2026-07-15)**: ghost 29,361 + excluded-only 2,624 = **31,985 도메인 삭제**
(연계: `domain_hostnames` 41,294·`watermark` 31,985·`scan_result` 31,236·`discovered_endpoint` 25,867). D 백필 2,240건.
사후: enabled 67,347→**35,362**·due 53,256→**20,401**·near-now 7.1%→**8.8%**.

## 8. 도출 dev 항목 (TASKS 반영은 매니저 — D71: 본 문서는 설계·근거만 보유)

1. **A**: `application.yml` `excluded-hostnames` 에 `"NEW-PAJ*"` 추가(설정 1행·주석 D 근거 갱신).
   실측 5종(NEW-PAJ21/31/41/51/61) 접두 일치 확인 완료 — 추가 검증 불요.
2. **C**: `discovery.probe-statuses`(List, 기본 [404,470], 빈=off 무회귀) 신설 +
   `buildPattern()` `<status>`(F_STATUS 상수 공유) + `buildLogQL()` `| status !~ "..."` 조합.
   테스트: LogQL 문자열 단언(필터 포함/빈 리스트=현행 동일 무회귀)·pattern 위치 드리프트 가드.
3. **B/B-2/D runbook 스크립트화**(`sample/ghost_domain_cleanup.sql` 류): §7 스케치 →
   실행·검증·롤백 절차 완성. **실행은 사용자 승인 후 별도**(이번 범위 밖).
4. 사후 모니터링 1회: 재배포 후 due·밀림 p50/p90·near-now 비율, 신규 유령 유입/일
   (§1 쿼리 재실행), 삭제 후 재등록(discovered_at, 자기치유) 점검.
5. (보류, YAGNI) endpoint-0 자동 정리 janitor — C+P3D 게이트로 충분할 전망이라 미도입.
   C 배포 후에도 신규 유령 유입이 유의미하면 재검토.
