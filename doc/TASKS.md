# TASKS — 작업 목록 (세션 메모리)

> 새 세션 시작 시 **반드시 이 파일을 먼저 읽고** todo/done 을 파악한 뒤 작업을 이어간다.
> 완료한 항목은 `[x]` 로 표기하고 **Done** 섹션으로 옮긴다.
> 설계 도출 개발 항목은 해당 작업의 **subitem(들여쓴 하위 체크박스)** 으로 추가하고, **모든 subitem 완료 시 부모를 `[x]` 로 바꿔 Done 으로 옮긴다**(D26).
> 의사결정은 `doc/DECISIONS.md`, 진행 로그는 `doc/PROJECT_LOG.md`, 설계 상세는 `doc/00~17`.

---

## 설계문서 ↔ TASKS 매핑 (dev 항목 일원화)

> 설계문서(09~17)의 'dev 구현 체크리스트'는 해당 PR 머지로 완료됐고, '범위 밖/후속'은 아래 TODO 로 흡수했다.
> 다음 세션은 **이 TASKS 를 단일 기준**으로 보면 된다(설계 상세만 doc 참조).

| doc | 다룬 범위(구현 완료) | 잔여 후속(→ TODO 위치) |
|---|---|---|
| 08 (D15) | API 점수화 코어(ApiScorer·신호·게이트·프로파일) | 가중치 실데이터 보정 → 보류 |
| 09 (D16) | explicit-hint 매처(MatcherConfig·ApiHintMatcher) | — |
| 10 (D17) | 분류설정 DB 저장(ClassificationConfig·resolver) | repeatMinCount override → P4(파라미터 중앙 API) |
| 11 (D18) | 분류설정 중앙 REST + effective 캐시 | 서비스간 인증 → P4 / HA cross-instance 무효화 → P3(HA) |
| 12 (D19) | non_api dropped 메트릭(DroppedNonApi) | Actuator 노출 → P3 / scan-status total → P1(리포트, 선택) |
| 13 (D20) | 정규화 고카디널리티(T1 상한·T2 param·T3 sensitive) | Active/Zombie param 노출 → P1 / sensitive·상한 중앙 API → P4 / HLL·t-digest → P1 |
| 14 (D21) | Postman/CSV 파서 + 3종 Canonical 동일성 | 멀티 스펙 병합 → P1 / spec_source.warnings 채널 → P1(리포트) |
| 15 (D22) | 매처 캐시 무효화(EndpointMatcherCache) | HA cross-instance 무효화 → P3(HA) |
| 16 (D23) | 버전 Zombie 추정 + Zombie severity | cross-scan recency → P1(분류) / 추정 임계 중앙 API → P4 |
| 17 (D24) | response_type_api 양성 가중치 | $type taxonomy 샘플링 → P1 / 실데이터 보정 → 보류 |

> **의도 배제(후속 아님)**: doc/11(D18) §5 의 `@Version` 낙관락은 **last-writer-wins 채택으로 미채택**(설정 쓰기 희소 — 충돌 비용 < 복잡도). TASKS 항목 없음이 정상. 동시 쓰기 충돌이 잦아지면 재검토.

---

## TODO

> **우선순위 방침(사용자 결정, D25)**: 기본/자체 기능 먼저, **외부(중앙) 연동은 나중**. 순서 = P1 자체 분석기능 → P2 품질/테스트 → P3 운영 → P4 외부연동 → 보류.
> 자체 기능이 충분히 갖춰진 뒤 외부연동 API 를 진행한다. `→ 의존:` 메모는 선행 조건.
> 단, 이 우선순위는 **카테고리(P1~P4) 단위**다 — 같은 P 버킷 안의 개별 항목 긴급도/규모는 별개이며, `(선택·낮음)` 라벨 항목(예: scan-status `total_dropped`)은 P1 안에서도 후순위다.

### P1. 자체 분석 기능 (먼저)

#### 분류 (04/16 문서)
> (현재 비어 있음 — OPTIONS preflight·cross-scan recency severity 완료, Done 참조)

#### 리포트/출력 (01/12/14 문서)
> (현재 비어 있음 — low_confidence+warnings·Active/Zombie params·total_dropped·API 판단근거 노출 완료, Done 참조)

#### 스펙 파서 / Spec Store (03 문서)
> (현재 비어 있음 — 검출/업로드 데이터 모델 통합 + 멀티 스펙 병합 완료, Done 참조)
> 후속(P4·선택): `/discovered`·`/spec` 원 카탈로그 list REST 중앙 노출 — 결합 뷰 `/discovery` 로 자체조회 충족, 중앙 노출은 외부연동(P4) 시.

### P2. 품질/테스트
> (현재 비어 있음 — 매칭 회귀테스트·F1/F2·@Lob→text 실검증·Testcontainers·엔티티 캡슐화 완료, Done 참조. Docker 의존 항목은 host podman 으로 해소.)

### P3. 운영/인프라 (자체 운영)
- [x] **(배포·검증 완료)** 테스트서버(192.168.8.197, podman) 실배포 — 기동·**무제한 Loki 수집**·**스캔 정책(PR1.1) 다도메인 분산**·CLI(`-domain -ls`·export/scan) 전부 실검증(PR #24~#28 + 2cab6a5, D44/D46/D47, doc/manual). VM 가동 중(정책-bounded 수집). 세부:
  - [x] `max-domains-per-run` 무제한 결정·반영(PR #25) + 정책 이미지 VM 재배포(2026-06-26, a792f107). 무제한 수집 동작 확인(domain_config 352, 캡 없음).
  - [x] **★PR1.1 — 스캔 per-domain 폭주 수정(실배포 발견)** **(완료 — PR #27, D46, 실배포 재검증: scanTick 다도메인 분산·기아 해소)**: max-window PT6H 백필이 단일 busy 도메인(www.takigen.co.jp)에서 1500+ 쿼리 → LokiBudget 독점 + 단일 @Scheduled 스레드 점유 → 다른 도메인·디스커버리 기아, discovered_endpoint=0. (부하 자체는 throttle·budget 으로 묶임=429 0, 문제는 진척0+기아.) 근본: 스캔=라인 페이지네이션, `budget.hasBudget()` 가 도메인 사이에서만 체크(runScan 내부 무한) + 단일 스케줄러 스레드(pool=1).
    - [x] (③) `spring.task.scheduling.pool.size: 2`(application.yml) — scanTick·discover 스레드 격리
    - [x] (②) `scan.max-window` 기본 PT6H→PT30M + `scan.slice-window` PT10M(미지정=chunk-window)
    - [x] (①) `runScan`→`collectBounded`(슬라이스 외부·hostname 내부 순회) → 슬라이스의 모든 hostname 완료 후에만 watermark 그 슬라이스 끝 전진(멀티-hostname gap-free) + `max-queries-per-scan`(50) 슬라이스 경계 하드캡·`budget.hasBudget()` 슬라이스 체크 → 부분 전진(consumedUpTo) 후 종료(resume). ★부분전진 채택. 0=무제한=현행. `collect`(온디맨드용) 유지. DiscoveryJobService +LokiBudget 주입.
    - [x] 테스트 — `ScanSliceBoundedTest`: 캡 hit 부분전진(consumedUpTo=마지막 완료 슬라이스·gap 없음[수집 슬라이스 ≤consumedUpTo])·전역예산 소진 부분전진·무제한(0)=전 윈도우 현행 동치. 운영 Loki 단위 mock. (커밋1: scan-fix)
  - [x] **도메인 목록 CLI `-domain -ls`** **(설계 완료 → doc/33 §15 / DECISIONS D47, 동일 브랜치)** — `main().isListDomains`(단일대시 `-domain`+`-ls` 동시) → `--adc.cli.list-domains=true` 주입 → `CliListRunner`(@Profile cli, `findAll(Sort host)`→host·enabled·#hostnames·discovered_at·last_seen_at stdout, 빈목록 exit0·DB오류 비0). `CliProperties.listDomains` 가산. arg 혼재 허용(기존 `--adc.cli.X=` 불변). DB-only·Loki 무관. 테스트(`CliListRunnerTest` 출력/빈목록/DB오류 + `MainArgModeTest` 감지·기존 명령 비회귀). (커밋2: list-cli)
    - [x] PR 구조: 분리 2커밋(scan-fix / list-cli) 준비 — 커밋은 매니저(보류)
  - [x] **재배포·실검증 완료(2026-06-26)** — PR1.1 이미지 재배포 후: varchar 에러 0(아래 path_template 수정), **scanTick 다도메인 분산**(1분 6+ 도메인·독점 없음)·discovered_endpoint 점증(0→12k+)·watermark 점진 전진·**스레드 격리**(discovery scheduling-2/scan scheduling-1)·무제한 수집(domain_config 11,549). `-domain -ls`→11,549 목록·`export-domain`→63 findings CSV(SHADOW 분류·15컬럼) 동작.
  - [x] **path_template varchar(255)→text** (실배포 발견·PR #28, D40 후속) — 긴 정규화 경로 INSERT 실패 해소. VM 기존 컬럼 `ALTER … TYPE text` 마이그레이션(12k 행 보존).
  - [x] **이미지 container 프로파일 baking**(2cab6a5) — one-off CLI 가 env 없이 PG 접속(`-domain -ls` 등 빈 H2 회피). doc/32 §4 CLI 3명령 env-free 예시.
- [x] **엔드포인트 스캔 Loki 부하 운영정책 (A–F) + 운영자 온디맨드 스캔 CLI** **(완료 — doc/33 / DECISIONS D45·D46·D47·D48, PR #26·#27·#29)** — 무제한 도메인 전수순회 과부하 해소. PR1(B+A+E)+온디맨드 CLI·PR1.1 폭주수정·PR2/PR3(C 티어·D off-peak·F dormant) 전부 머지. off-peak·intervalOverride 흡수. 잔여 후속(아래 P3): 메트릭 알람·Batch JobRepository·doc/18 next_scan_due_at sync·PR2/PR3 VM 재배포 검증.
  - PR1 필수 (B+A+E) + 온디맨드 CLI — **완료(PR #26, build 374 green, 리뷰 P1/P2/P3=0)**:
    - [x] (A) `windowFor` max-window 상한(백필 슬라이스, 5-arg, 0/null=무제한 무회귀) + line 105 TODO 주석 해소 — *PR1 구현 완료(build green 374, 머지 시 Done)*
    - [x] (B) `DomainConfig.lastScanAttemptAt`(ddl-auto) + `findByEnabledIsTrue(Pageable)`(Sort asc nulls-first)·`touchLastScanAttempt`(@Modifying 단일컬럼) + `ScanSelector` + `DiscoveryScheduler.scanTick()`(tick PT5M·K 예산·attempt 마다 커서 전진[skip·실패 포함]·예산 소진 break 이월) — 전수순회 대체
    - [x] (E) `LokiBudget`(시간당 쿼리/바이트 하드캡·초과=hasBudget false 이월, 0=무제한) + LokiClient Micrometer 계측(loki.queries·response.bytes·errors{status}) + 적응형 throttle(429/5xx level+1·성공 −1, throttle-on-error 게이트)
    - [x] (온디맨드) `CliScanRunner`(@Profile cli, `--adc.cli.scan-domain`/`--window`/`--edge`, scan()→exit/run()→System.exit, 미존재·미enabled=3·Loki실패=4) + `scanOnDemand`(edge→runOnDemand/미지정→collect+analyze, **watermark 미전진**) + `onDemandWindow`(상한=max-window) + main() scan-domain 분기·CliExportRunner blank=no-op(명령 공존)
    - [x] `ApiDiscoverProperties.Scan` 레코드 + application.yml 기본값 + 테스트(windowFor 상한·LRS nulls-first·scanTick 커서전진[skip/실패]·budget 캡/롤오버/Micrometer·온디맨드 exit/무전진) — 단위 mock(운영 Loki 미호출), 실호출 `-Dloki.live` 게이트
  - [x] **PR2/PR3 — C 티어링 + D off-peak + F dormant** **(완료·머지 — build green 408·실패0·skip2[live 게이트], 실 PG 가드 PASS, PR #29 squash 6df371f. doc/33 §4–6 / DECISIONS D48)** — C/F/override 를 단일 due 모델로 통합, D 는 그 위 파라미터 스위치. 1 PR. 리뷰 P1(findDueForScan nulls-first 결정화 — @Query ORDER BY 명시·실 PG 회귀가드)·P3-1(OffPeakWindow.zone invalid 폴백)·P3-2(ScanTier 밴드 전제 문서화) 전건 반영.
    - [x] (통합 due) `DomainConfig.nextScanDueAt`(Instant, ddl-auto nullable, @Index) + `ScanTier.effectiveInterval`(override 파싱 ?? tier(lastSeenAt age)) 순수함수 — C active/inactive/default + F dormant band
    - [x] (C) `ScanSelector` due 쿼리 `findDueForScan`(`WHERE enabled AND (next_scan_due_at IS NULL OR <= now) ORDER BY next_scan_due_at ASC LIMIT K`, 이식·인덱스) + `scanTick` `touchScanSchedule`(now+effectiveInterval, skip/실패 포함). `intervalOverride` 최우선 배선(기존 P3 TODO 해소)
    - [x] (D) `OffPeakWindow` 판정(`schedule.off-peak-window`+`scan.off-peak-zone`, 자정 wrap) → off-peak 시 K=`off-peak-domains-per-tick`(ScanSelector)·윈도우=`off-peak-max-window`(scanTick→runScan 오버로드) 스위치, 코어 불변. 백필 우선=due 정렬이 가장 밀린 도메인 앞세움(Watermark join 불요). 쿼리캡 상향은 범위 밖(defer, ponytail)
    - [x] (F) dormant band(`age > dormant-after` → `dormant-interval` 최장)=effectiveInterval 1 분기. **삭제·비활성 없음**(무삭제 일관)
    - [x] 설정키(`tiering-enabled`·active/default/inactive-interval·active-threshold·off-peak-*·dormant-*) application.yml + **무회귀**(`tiering-enabled=false`→effectiveInterval=ZERO→nextScanDueAt 항상 now→LRS=PR1 동치, off-peak 미설정=항상 peak)
    - [x] 테스트 — `ScanTierTest`(active/inactive/dormant/default/override정상·실패폴백/tiering-off ZERO 밴드 경계)·`OffPeakWindowTest`(in/out·경계·자정wrap·blank/파싱실패=peak·zone)·`ScanSelectorTest`(due 술어 미due 제외·nullsFirst asc·K·off-peak K 스위치)·`DiscoverySchedulerTest`(touchScanSchedule=now+effectiveInterval·tiering-off PR1 동치·off-peak maxWindow 주입). 운영 Loki 단위 mock
- [x] off-peak 시간대 제한 **(완료 — doc/33 D, PR2 `OffPeakWindow`·K/윈도우 스위치, PR #29 머지)**
- [ ] **REST API 대규모 변경 배치 (삭제·수정·신규)** **(설계 → doc/35 / D50. PR1(D1+M1+M3) #39·PR2(filename+M2+M4+M6) #40·PR3(M5+A2) #41 머지·PR4(P3 A1) 구현 완료(브랜치 feat/api-batch-p3-scannow, build green 486·커밋 보류·머지 시 Done). ★P1·P3 전부 완료(A1=마지막 기능 PR). P2(M7)=보류만 잔존)** — 스키마변경=`spec_record.filename` 1건(PR2·재배포 필요). ★중앙영향: M1=배열+헤더(non-breaking)·M2/M4/M6=additive·M5 rationale 가산(additive·ETag 불변)·A1=신규(영향 0). ★매뉴얼 일괄 반영 완료(TW, api-rest-manual.html: D1 삭제·M1~M6·A1·A2·W1, 재배포 c435d9f 실데이터).
  - 1단계(저위험·additive, 독립 PR):
    - [x] **PR1 D1** `HostQueryController` 제거(+`GET /hostnames/{}/domains`·`POST .../query`) + `findByHostname` orphan 정리(전용 확인 후 삭제). 매뉴얼 `/hostnames` 절 제거 완료(TW)
    - [x] **PR1 M1** GET /domains 페이지네이션 — ★body=JSON 배열 유지 + 헤더(X-Total-Count/Pages·X-Current-Page), page 0-based(음수→0)·size [1,1000] clamp·host asc. non-breaking. N+1 은 page-bounded 완화+batch meta 후속(ponytail)
    - [x] **PR2 filename** `spec_record.filename`(ADD-only·ddl-auto·기존행 null) + `SpecRecord` 접근자 + PUT /spec `?filename=`(SpecStore.upload 오버로드). ★재배포 필요
    - [x] **PR2 M2** GET /domains/{host} 보강 — `DomainDetailView`(lastScanAt·spec{filename,uploadedAt}·effectiveClassification). ★목록(M1)은 경량 `DomainView` 유지(성능 회귀 방지). effective 빌더=`EffectiveClassification.toView()` 공유(/discovery 중복 제거)
    - [x] **PR1 M3** PUT /domains 부분수정(`DomainUpsert` enabled Boolean nullable·present-only apply, []=비우기/null=유지, 전항목·create 무회귀)
    - [x] **PR2 M4** GET /scan-status 보강(`latestSpec`=SpecMetaView{filename·uploadedAt·endpointCount} 재사용, 없으면 null)
    - [x] **PR3 M5** GET /result rationale 가산 — serve-time report_json 파싱→rationale 주입→재직렬화(CombinedDiscoveryService.forHost(host).rationale() 재사용). report_json 기존 필드·ETag(r.version) 불변·304 보존(미재계산)·report null=204
    - [x] **PR2 M6** GET /spec 목록 — flat `List<SpecMetaView>`(specName·filename·active 가산, activeRecords·정렬, 무스펙=[]). `?history` 후속
    - [x] **PR3 A2** PATCH /classification/weights(도메인·전역) — 현 effective 14 스냅샷(resolve/신규 resolveGlobal) ∪ 요청 부분 → profile CUSTOM·편집 안 한 키 유지(MIDDLE 리셋 방지)·threshold/matcher 미터치·unknown 키 400. 기존 PUT 불변(가산)
  - 2단계(M7 **재설계** — 영속 API 인벤토리 + reconcile, 고위험·독립 PR) **(★P1 전체(P1-1~P1-9) 완료·머지 PR #46 / main `3b70c3e` + 매뉴얼 `2d76859` → doc/37 / DECISIONS D53. build green 493·PostgresIntegrationTest 27/27. 재배포·라이브 검증(union·★삭제 격리·UPDATED[param]·ADDED·DELETED·매니저 격리 RED-증명). doc/36/D52[문서버전 diff]=의도 불일치로 supersede. ★P2 후속만 잔존)** — ★M7a(PR #44/#45, compute-on-read diff)는 doc/37 §5 롤백 경계로 **제거·이관**(구현됐으나 폐기). 재설계 P1/P2:
    - [x] **P1-1 롤백(M7a)**: `SpecDiffService`·`SpecChanges`·`SpecCanonicalProjection` 삭제 + `SpecRecordRepository.findCanonicalVersions` 제거(`findActiveSpecMetas` 유지) + `SpecController` /spec/changes 제거 + 테스트(diff 6건 제거·`reuploadSameFilenameViaHttpDoesNotHit500` 단언을 `/apis` 로 이관). 유지=filename→specName 도출·upload @Transactional·filename 컬럼·SpecMetaProjection. (doc/37 §5)
    - [x] **P1-2 신규 테이블**: `DocumentedApiRecord`/`documented_api`(키 host·specName·method·path_template·unique, paramsJson text·status{ACTIVE,DELETED}·lastChange{ADDED,UPDATED,UNCHANGED}·deprecated·version·sourceSpecVersion·firstDocumentedAt·lastDocumentedAt·changedAt) + repository. **ddl-auto ADD TABLE 무손실**·인덱스(host/host,spec_name/host,status). (doc/37 §1)
    - [x] **P1-3 파라미터 모델·추출**: `SpecParam(name,in{QUERY/PATH/HEADER/COOKIE/BODY},required,type 요약)` + `CanonicalEndpoint` params 가산(매칭키 불변·구 canonicalJson null→빈·6-arg 편의 생성자 하위호환). 3 파서 추출(OpenAPI getParameters/getRequestBody·Postman url.query/path/body·CSV `params` 컬럼). (doc/37 §2)
    - [x] **P1-4 reconcile-on-upload**: `ApiInventoryService.reconcile`(upload @Transactional 전파 REQUIRED·별도 빈=self-invocation 무관) — 존재+변경=UPDATED·동일=UNCHANGED·부재=ADDED·인벤토리 active+파싱 부재=DELETED[그 specName 한정·삭제 격리]. SpecRecord/rawDoc 미접근=oid 무관. (doc/37 §3)
    - [x] **P1-5 노출** `GET /api/v1/domains/{host}/apis`(`ApiInventoryController`·?specName/status/method·DELETED 노출·host 정규화 400·잘못된 status 400). (doc/37 §4)
    - [x] **P1-6 ★DELETED→Zombie 결합**(사용자 핵심): Classifier core 에 `Set<String> deletedKeys` 가산(빈=무회귀)·1st pass active spec 미매칭+DELETED 키 → Zombie(deleted-from-spec·confidence 0.8·specRef 마커·게이트 우회). DiscoveryJobService(스캔)·CombinedDiscoveryService(forHost) 1쿼리 주입. deprecated(1.0) vs DELETED(0.8) 구분. 실 PG RED-확인. (doc/37 §6)
    - [x] **P1-7 ★rawDoc(oid) 삭제**: `SpecRecord.rawDoc` 필드·접근자·`SpecStore.setRawDoc` 제거(필드 제거=oid 함정 클래스 구조 소멸) + rawDoc 전용 테스트 은퇴(probe 삭제·meta/forHost/round-trip 에서 rawDoc strip). ops DDL 런북(lo_unlink→DROP COLUMN/vacuumlo·차기 점검창·디커플=dev 범위 밖). 백필=재업로드(go-forward, prod 사용자 spec ~0). (doc/37 §7)
    - [x] **P1-8 실 PG 테스트**: 문서별 union·★격리(B 가 A 미삭제)·UPDATED/DELETED/ADDED/UNCHANGED·param 변경(type)·재업로드 200·Zombie 결합. 전부 MockMvc 실 HTTP(auto-commit)=oid 안전. (doc/37 §9)
    - [x] **P1-9 매뉴얼(TW)**: `api-rest-manual.html` §2.4 — `/apis` 신설·PUT reconcile 보강·status/lastChange 의미·deprecated(1.0) vs DELETED(0.8) Zombie·/spec/changes 절 제거·요약표/TOC/TODO 갱신(oid→bytea TODO 제거). 재배포 3b70c3e 실데이터.
    - P2(후속) **(P2-2+P2-3+P2-4 머지 완료 PR #47 / main `b60b929` → doc/38 / DECISIONS D54. build green 497·PostgresIntegrationTest 31/31. 재배포·라이브 스모크(breaking·merged 정상)·Loki 수집 계속 확인. breaking·merged status·merged version 출처(P3-1) 실 PG RED-확인. ★P2-1 보류·P2-5 범위 밖만 잔존. ★매뉴얼 현행화 완료(TW, api-rest-manual.html §2.4: lastChangeBreaking·changedParams·?breaking·?view=merged·breaking 규칙표, 재배포 b60b929 실데이터))**:
      - [ ] **P2-1 매칭 source 대체 — ★보류**(이 PR 제외·미터치): loadActiveCanonical→인벤토리(status ACTIVE) 단일화. 동작보존=가시가치 0·★SEPARATE 발산(reconcile specName 격리 vs upload replaceAll 전체비활성)·★status 오버로드 지뢰(동기화 시 교체문서=DELETED→Zombie 오탐)·백필 갭 → 핵심 분류 직격 위험. loadActiveCanonical 유지·인벤토리 보완. 추진 시=MERGE 한정+canonicalJson 폴백+골든 이중경로 회귀. (doc/38 §1)
      - [x] **P2-2 백필 = 재업로드(코드 0)**: rawDoc 삭제(P1)로 (a) 확정 → 운영자 재업로드 시 reconcile 이 param 채움. (b)bytea 기각. 매뉴얼/doc 노트만(dev 구현 항목 없음·doc/38 §2 노트). 매뉴얼 반영 완료(TW, §2.4 P2 잔여 TODO). (doc/38 §2)
      - [x] **P2-3 풍부 param diff + ★breaking 판정**(제품가치 최고): `ParamDiff`(added/removed/modified + 요청계약 규칙표: required 추가/optional 제거/optional→required/type 비호환=breaking·widening 허용표 integer→number) — reconcile `applyUpdate` 에서 old(row) vs new(p) ★setParamsJson 전 계산·저장. 신규 컬럼 2개(`last_change_breaking` boolean·`last_change_detail_json` text·ddl-auto ADD·무손실·기존행 false/null). `DocumentedApiView` 에 `lastChangeBreaking`·`changedParams` 노출 + `?breaking=true`. type 호환표 최소(enum/format/nested 범위 밖). 실 PG 6 규칙+widening RED-확인. (doc/38 §3)
      - [x] **P2-4 도메인-merged 뷰**: `/apis ?view=merged`(`MergedApiView`·method+path_template dedupe·compute-on-read 스키마 0). 병합=status(하나라도 ACTIVE→ACTIVE·전부 DELETED→DELETED)·deprecated OR·version/params latest-by-sourceSpecVersion·`contributingSpecNames[]`. 기본(?view 무)=per-document(무회귀). 실 PG 병합/겹침/status combos RED-확인. (doc/38 §4)
      - [ ] **P2-5 inactive prune — ★범위 밖**(TODO 유지): `spec_record` inactive 누적은 희소·무해, 상한 prune 은 수요 시 후속.
  - 3단계(A1, 독립 PR) **— ★PR4 구현 완료(브랜치 feat/api-batch-p3-scannow, build green 486·커밋 보류·머지 시 Done, 마지막 기능 PR)**: [x] POST /domains/{host}/scan-now 동기 즉시스캔(ScanController: 정규화 null→400 → registerIfAbsent 자동등록 → onDemandWindow(?window)+scanOnDemand watermark 미전진 → forHost findings/rationale 반환. Loki 실패=502. 비동기 POST /scan(202) 와 구분, 둘 다 유지. 테스트 전부 Loki mock)
  - [x] W1 매뉴얼 §2.5 /classification 의미 정리(thresholdOverride·customWeights·matcher) — `api-rest-manual.html` §2.5 반영 완료(TW)
- [x] **실배포 버그: rawDoc oid auto-commit 500 (메타조회 M2/M4/M6 + forHost /discovery·/result M5)** **(구현 완료 — build green 488·실 PG 회귀가드 2건 PASS·각 fix 원복 RED 확인·커밋 보류·머지 시 Done. doc/28 §10·DECISIONS D51, 브랜치 fix/spec-meta-oid-autocommit)** — `SpecRecord.rawDoc=@Lob byte[]`=PG oid → 비-@Transactional(OSIV=false→auto-commit) 엔티티 로드 → oid materialize → `Large Objects may not be used in auto-commit mode` 500. prod 스펙 0 이라 잠복.
  - [x] 메타 조회(M2/M4/M6) → `SpecMetaProjection`(rawDoc 미선택 JPQL) 전환(스키마 변경 없음). 가드 `specMetaEndpointsDoNotMaterializeRawDocOidInAutoCommit`.
  - [x] forHost(/discovery·/result M5) → `CombinedDiscoveryService.forHost` 에 `@Transactional(readOnly=true)`(진입점 tx, loadActiveCanonical 미터치=analyze 무영향). 가드 `forHostEndpointsTolerateRawDocOidSpecOnRealPg`.
  - [x] M6 projection 쿼리 ORDER BY `specName asc nulls first` 명시(h2-pg-null-ordering-trap·D48 동형, 인메모리 nullsFirst 동작 일치). 실 PG 가드 `specListNullSpecNameOrdersFirstDeterministicallyOnRealPg`(nulls first 제거 시 PG NULLS LAST 발산 RED 확인).
  - [x] **PUT /spec 동일 filename 재업로드 500(self-invocation, 후속·브랜치 fix/spec-reupload-tx-self-invocation)** — `SpecStore.upload` 4-arg core 만 @Transactional·진입 오버로드 비-tx → 컨트롤러가 진입 오버로드 호출 후 core self-invocation → tx 무력 → 재업로드 비활성화 루프(prev.setActive=rawDoc oid 로드) auto-commit 500. 수정=진입 오버로드 3개 @Transactional. 가드 `reuploadSameFilenameViaHttpDoesNotHit500`(★MockMvc PUT=실 HTTP·테스트 tx 미적용, fix 제거 시 oid RED 확인). build green 497·PG 29.
  - [ ] (선택 후속) rawDoc `@Lob byte[]` oid → bytea 매핑 정밀화(마이그레이션 위험 동반) — 실수요 시.
- [ ] 부하/운영 메트릭 (쿼리수·바이트·429) Actuator/Micrometer 노출 + 알람 — doc/12 `DroppedNonApi`·doc/13 `DroppedByLimit` 카운트 재사용 가능 **(→ 계측은 doc/33 E PR1, 알람 연동은 별도 후속)**
- [ ] Spring Batch JobRepository 실연결 (현재 `@Scheduled`만, `batch.job.enabled=false`)
- [x] 도메인별 `intervalOverride` 스케줄 반영 (도메인 설정은 이미 영속, 스케줄러 반영만) **(완료 — doc/33 C, PR2 `ScanTier` override 최우선 파싱, PR #29 머지)**
- [ ] HA 단일 실행 보장 (ShedLock 또는 Quartz 클러스터) — 도입 시 **cross-instance 무효화**(effective 설정 캐시·매처 캐시 TTL/pub-sub, doc/11 §3·doc/15 후속) 함께
- [x] **CLI 문법 `-domain` 서브커맨드 통일** **(완료·머지 — build green 413, PR #30 squash 37820d6. doc/33 §15·DECISIONS D47 갱신. 리뷰 P1/P2=0·P3 2건 전건 반영)** — 전 명령 단일대시 `-domain` 서브커맨드로 교체(사용자 확정). HTML 매뉴얼 동기는 technical_writer 후속.
  - [x] `main().parseCli`(순수·static) 신문법→내부 `--adc.cli.*` translate: `-domain -ls`/`-export <도메인>`/`-scan <도메인> [-window <ISO8601>] [-edge <hostname>]`. `-domain` 없음=서버, 단독·도메인 누락=usage+exit(2). 기존 `isListDomains`/`isCliMode`/`CLI_TRIGGERS` 제거.
  - [x] ★기존 `--adc.cli.export-domain=`/`scan-domain=` 사용자 트리거 제거(직접 입력 시 CLI 미진입). CliProperties·3 런너·바인딩 불변(최소 변경).
  - [x] 테스트 `MainArgModeTest` 갱신(신문법 3종 주입 단언·기존 문법 제거 회귀=서버 모드·usage 에러 경로). md 문서 동기(doc/31·32 §4·33 §7/§15·D47). HTML 매뉴얼=technical_writer 후속(미터치).
- [x] **discovered_endpoint unique 위반 버그 수정(실배포 발견·2단계)** **(구현 완료 — build green, 실 PG 가드 PASS, 커밋 보류·머지 시 Done. doc/26 §2)** — 선존 버그(PR2/PR3 무관, upsertDiscovered 경로). 한 스캔 `discovered` 가 기존 DB 행/배치 중복과 충돌해 2번째 INSERT 가 unique(host,method,path_template) 위반 → 도메인 격리 catch 로 스캔 전체 실패(결과 미저장).
  - [x] **1차(PR #31, 머지됨)** — `prior.put(d.signature(), rec)` 로 intra-batch 동일 signature 병합. **그러나 signature 발산 시 무력**(아래).
  - [x] **2차(template 축, 브랜치 fix/discovered-endpoint-identity-key)** — 실배포 bind 로깅으로 확정: `prior` 적재 키는 제약 튜플(`signatureOf`=method+host+template)인데 upsert 가 `d.signature()` 로 lookup → `DiscoveredEndpoint.signature` 가 최종 path_template 과 발산("/" 등)하면 기존 행 miss → 신규 INSERT 충돌(예: examinee-portal.eiken.or.jp GET "/"). 수정: `identityKey(method,host,pathTemplate)` 헬퍼로 lookup·put 키를 제약 튜플로 통일(signature 의존 폐기), `signatureOf` 도 이 헬퍼로 리팩터.
  - [x] **3차(host 축, 같은 브랜치, reviewer 실 PG probe)** — 2차가 키 host 를 `d.host()`(파싱 Host)로 둬 host 축 발산 잔존: `LokiQueryBuilder` `|= domain` substring 라인필터가 referer/URL/UA 에 도메인 든 다른 Host 라인도 매칭 → `d.host()≠스캔도메인` 시 키 갈림 → 둘 다 `setHost(host파라미터)` INSERT 충돌. 수정 1글자: 키 host 를 `d.host()`→**스캔 `host` 파라미터**(영속값·prior 키와 일치). 실 PG 회귀가드 신규 2건(`upsertMergesDivergentParsedHostsUnderScanHostOnRealPg` intra-batch·`upsertMatchesExistingRowWhenParsedHostDivergesOnRealPg` cross-batch, 둘 다 수정 전 duplicate key red 확인). 기존 가드(template 발산·intra-batch signature) 유지.
- [x] **classifier recency 발산 signature 미스 수정(P3, 무크래시 정확도)** **(구현 완료 — build green, 발산 회귀가드 RED-without-fix 확인, 커밋 보류·머지 시 Done. doc/26 §2, 브랜치 fix/recency-identity-key)** — classifier cross-scan recency 가 `priorFirstSeen.get(d.signature())`(발산값)로 조회해 entrenchment miss → Zombie severity 과소. 수정: 공유 util `model/EndpointIdentity.key(method,host,pathTemplate)` 추출(upsert·recency·영속 단일 진실원), `DiscoveryJobService.identityKey`/`signatureOf` 가 위임, `Classifier.classifyWithMetrics` 8-arg(+host) 추가(짧은 오버로드 host=null 위임)·2곳 lookup 을 `EndpointIdentity.key(d.method(), host, d.pathTemplate())`로, analyze 가 host 전달. 무회귀: 비발산 경로 키 동일. 테스트 `ClassifierTest`(기존 entrenchment 8-arg 갱신 + 신규 `recencyMatchesByIdentityKeyDespiteSignatureDivergence` 단위, 수정 원복 시 band LOW RED 확인). `DiscoveredEndpoint.signature` 식별 소비처 0(주석).
- [x] **foreign-host 누수 수정 + signature 원천 발산 정리(하나의 근본)** **(구현 완료 — build green, 필터 RED-without-fix 확인, 커밋 보류·머지 시 Done. doc/05 §2.2·26 §2, 브랜치 fix/scan-foreign-host-filter)** — 조사: signature 는 `Acc.toEndpoint` 에서 record (method,host,template)와 같은 변수로 재계산 → template 축 발산 구조적 불가, 발산은 host 축뿐. 원인=`LokiQueryBuilder` `|= domain` substring coarse 필터가 referer/URL/UA 의 다른 Host 라인도 매칭 → InventoryBuilder 가 파싱 host 로 endpoint 생성·유입. 수정: ① 공유 `util/DomainNames.normalize` 추출(DomainDiscoveryService 위임=동일 규칙 보장) ② `DiscoveryJobService.analyze` parse→dedup 직후 `host.equals(DomainNames.normalize(r.host()))` 스캔도메인 전용 필터. 효과: 인벤토리·findings·discovered·recency 스캔도메인 전용, host 발산 0(signature 잔여 발산도 해소). 테스트 `DomainNamesTest`·`DiscoveryJobServiceTest.analyzeFiltersForeignHostRequests`(대문자 host 정규화 포함·foreign 제외, 필터 제거 시 RED 확인). `signature` 필드 제거만 별도 cleanup(소비처 0·무해, 미진행).
  - [x] **P2 견고화(등록 정규화 + 양변 normalize, 머지 전 리뷰)** — 필터 좌변이 '스캔 host 이미 정규화' 불변식에 의존했으나 `DomainController` 수동 등록이 미강제(비정규화 도메인 오필터 위험). ① `DomainController.create`/`PUT` 가 `DomainNames.normalize` 후 저장·조회(normalize==null=400) → domain_config.host 항상 정규화. ② analyze 필터를 양변 normalize 로 자기완결화(불변식 미의존·레거시 대비). 테스트 `DomainControllerTest`(정규화 저장·중복 정규화 매칭·blank 400·PUT 정규화 조회)·`DiscoveryJobServiceTest.analyzeNormalizesScanHostInForeignFilter`. 무회귀: auto-discovery·정규화 도메인 PUT 불변(normalize 동일값).
  - [x] **P3 자기일관(GET/DELETE 정규화, trivial 동봉)** — create/PUT 만 정규화하면 GET/DELETE `/{host}` 가 raw 키 미스로 404(POST 'API.x'→'api.x' 저장, GET /API.x→raw 404). `requireNormalizedHost` 헬퍼로 create/PUT/GET/DELETE `{host}` 경로변수 정규화 통일(빈/"-"=400). 본문 로직 불변. 테스트 `DomainControllerTest`(대문자 경로 GET·DELETE 정규화 매칭) 가산.
- [ ] **(후속) 교차 컨트롤러 `{host}` 정규화 일괄** — Scan/Spec/Classification/CombinedDiscovery 컨트롤러의 `@PathVariable host` 가 raw(`findById(host)`)라 대소문자/공백 경로는 정규화 도메인과 미스(pre-existing, DomainController 와 별개). 공통 `DomainNames.normalize` 일괄 적용 또는 인터셉터/리졸버 — 별도 PR(이번은 DomainController 자기일관만).
- [x] **`-domain -ls` stdout 표 → CSV 파일(사용자 Option B)** **(구현 완료 — build green, 커밋 보류·머지 시 Done. doc/32 §4·33 §15·DECISIONS D47, 브랜치 feature/domain-ls-csv)** — 대량 도메인(11k+)에서 stdout 표 비실용 → export-domain 동형 파일 다운로드. `DomainCsvWriter.domainsToCsv`(host,enabled,hostnames(';'),discovered_at,last_seen_at·RFC4180·CRLF 재사용). `CliListRunner.list(stamp)` → `output-dir`/domains-&lt;stamp&gt;.csv(빈 목록=헤더만 exit 0·DB 오류 EXIT_DB 4·IO 오류 EXIT_IO 4). `-domain -ls` 도 output-dir 볼륨 필요. 테스트 `CliListRunnerTest`(@TempDir CSV 내용·빈목록·DB/IO 오류)·`DomainCsvWriterTest`(domainsToCsv). main().parseCli·MainArgModeTest 불변. HTML 매뉴얼(collection-ops §4.1)=technical_writer 후속(미터치).
- [x] **경로 matrix 파라미터(;jsessionid 등) 정규화(실배포 발견)** **(구현 완료 — build green, 커밋 보류·머지 시 Done. doc/02 §3, 브랜치 fix/strip-matrix-params)** — `LogLineParser.stripQuery` 가 query 만 제거, matrix(`;key=value`) 잔존 → `/st/login;jsessionid=X`(eos-st.komeda.co.jp)가 세션ID별 별도 endpoint 로 적재(카디널리티 폭증·SPEC 미매칭). 수정: 파서 `rawPath = stripMatrixParams(stripQuery(uri))`(세그먼트별 첫 `;` 절단, leading slash 보존, `;` 없으면 조기반환). rawPath 가 매칭·추론 공통 입력이라 1곳 정리 → 둘 다 정상화(PathNormalizer/매칭 불변). 테스트 `LogLineParserTest.stripsMatrixParamsFromPath`(jsessionid·멀티세그먼트·matrix+query·불변). 기존 DB `;jsessionid` 행은 재스캔 새 적재 + retention(180d) 자연 정리(forward fix, 매니저 재배포 반영).
  - [ ] (선택·동일정합 후속) `RefererSignalExtractor.refererPath` 도 matrix strip — referer matrix 드물고 PAGE_URLS coverage-gated 라 영향 미미, 미적용·메모만.
- [x] **신규 도메인 즉시 처리 CLI — `-domain -register` 신규 + `-domain -scan` 미등록 자동등록** **(구현 완료·머지 PR #37 cd6fe13 — build green 445. doc/31 B1·32 §4·33 §7·DECISIONS D47 확장)** — 운영자가 신규 도메인을 스케줄러 안 기다리고 즉시 등록/스캔. 등록 로직은 공유 헬퍼 `DomainRegistrar.registerIfAbsent`(register·scan 자동등록 재사용, REST `DomainController` 는 409 라 CLI 부적합·자동 디스커버리 `DomainUpserter` 는 `discoveredAt` 마킹이라 부적합 → 별도 최소 컴포넌트). 운영 Loki 미호출(register=DB only, scan 만 기존대로 Loki).
  - [x] `DomainRegistrar`(@Component) — `registerIfAbsent(rawHost)`: 정규화(`DomainNames`)→null=IllegalArgumentException, `findById.orElseGet`(enabled=true·createdAt/updatedAt=now·`discoveredAt=null`=수동 등록 자연 구분·멱등).
  - [x] `main().parseCli` + usage: `-register` 서브커맨드 추가(`--adc.cli.register-domain=` translate), subcommands 카운트에 포함(복수=usage 오류). `CliProperties.registerDomain` 가산.
  - [x] `CliRegisterRunner`(@Profile cli) — run() blank=no-op, register()→existsById 사전판정으로 "등록 완료"/"이미 등록됨(no-op)" 메시지 구분·exit(0 성공(이미 존재 포함)/2 도메인 누락/4 DB).
  - [x] `CliScanRunner` 수정 — `domain` 정규화 일원화(findById·scanOnDemand 공통), 미등록=`registrar.registerIfAbsent` 자동등록(enabled=true) 후 스캔, 존재·비활성=자동활성 안 함→EXIT_NOT_SCANNABLE.
  - [x] 테스트 — `DomainRegistrarTest`(신규 save·`discoveredAt=null`/기존 무save 멱등/정규화/null거부) + `CliRegisterRunnerTest`(신규/멱등/대문자정규화/누락/DB오류) + `CliScanRunnerTest`(미등록 자동등록후스캔·비활성 자동활성안함·정규화·기존무회귀) + `MainArgModeTest`(-register 주입·누락 usage·복수 모호). `CliExportRunnerTest`/`CliListRunnerTest` 생성자 인자 갱신.
  - [x] 문서 동기 — doc/31 B1·32 §4(register 예시+scan 자동등록 주석)·33 §7·DECISIONS D47 확장. HTML 매뉴얼(collection-ops-manual.html §4: -register 절·scan 자동등록·종료코드·즉시처리 워크플로) 반영 완료(TW).

### P4. 외부 연동 (자체 기능 완료 후)
> 중앙 서버 연동·인증. 자체 분석기능(P1)이 안정된 뒤 진행.
- [ ] 서비스 간 인증 실구현 (mTLS 또는 OAuth2 client-credentials) — 현재 `SecurityConfig` permitAll
- [ ] 완료 웹훅 (Worker→중앙 scan-events push) 실구현
- [ ] **(신규 묶음, doc/10/11/13/16 후속)** 분석 파라미터 중앙 API 확장 — 기존 분류설정 중앙 API(doc/11) 패턴·`@ConfigurationProperties` 재사용:
      `repeatMinCount` override(doc/10) / sensitive 키 목록·normalization 상한 도메인 override(doc/13) / version-zombie·severity 추정 임계(doc/16). `→ 의존:` doc/11 분류설정 중앙 API(완료)

### 보류 (08 §9 — 현 시점 미채택)
- [ ] (보류) endpoint decision cache — 배치 재집계 구조라 이득 작음, 필요 시 재검토
- [ ] (보류) 가중치 **실데이터 보정** — 참고 설계 정확값 + `responseTypeApi`(doc/17)·Zombie `severity`(doc/16) 1차값(보정 전 임의값)을 실 Loki 데이터로 보정 후 확정

---

## Done

### REST API 매뉴얼 경로 rename 반영 — GET /apis → /spec/apis (2026-06-30, PR #48 7480a8c, 문서만) — TW
- [x] `api-rest-manual.html` 의 도메인 API 인벤토리 경로 `/apis` → `/spec/apis` 9곳 갱신(TOC·개요표·§2.4 제목/시그니처·curl 2건·본문·TODO) + 스펙 리소스 구분 설명(/spec=문서 목록·/spec/apis=문서의 API·discovery 와 구분) 보강. 로직/응답/쿼리 무변경. 구 `/apis` 경로 잔존 0 검증. `ApiInventoryController` 라우트 교차검증.

### DB 스키마 명세 HTML — M7 재설계·P2 현행화 (2026-06-30, 실 PG 캡처, 문서만) — TW
- [x] `db-schema-spec.html` 9테이블화 — 신규 `documented_api` 카드(16컬럼·UNIQUE(host,spec_name,method,path_template)·idx host/host,spec_name/host,status·status=DELETED→Zombie 입력) + `spec_record` `filename` 추가·`raw_doc`(oid) 제거됨(orphan) 표기 + ER 다이어그램·관계표(8건)·개요표 documented_api 추가. `DocumentedApiRecord` 코드 교차검증.

### REST API 매뉴얼 응답 예시 축약 펼치기 (2026-06-30, 사용자 요청, 문서만) — TW
- [x] `api-rest-manual.html` 의 모든 축약(`…`·`{…}`·`"…":"…"`·`...14키`·`13개`) → 라이브 실값으로 펼침(숨은 필드 0). bounded 구조(14키 weights·effective 16필드·/apis 14필드·scan-status·/result 중첩객체·13 신호) 전부 표시, 대용량 배열(findings 153·rationale 194)은 대표 1건 완전+건수 주석. scan-status/result/§3 새 캡처(829bfa4c628a7ede·162/153/9) 일관화. 코드 교차검증(Weights 레코드 16필드·weightsAsMap 14키·ParamDiff). 커밋 후속.

### REST API 사용 매뉴얼 신규 작성 (2026-06-29, 사용자 요청, 문서만) — TW
- [x] `doc/manual/api-rest-manual.html` 신규 — 전 엔드포인트(도메인 CRUD·scan-status·result·discovery(신규 rationale)·spec·classification·hostnames·actuator) 18종 사용법(curl)+라이브 실데이터 응답 예시(VM 재배포 8b37f89 캡처)+조건부 GET(ETag 304 실증)+현재 제약사항·TODO 절. 자기완결 HTML. 컨트롤러 6종 라우트·상태코드(201/204/202·ETag) 교차검증.

### API 판단 근거(점수 산출 내역) 노출 — /discovery 가시화 (2026-06-29, doc/34 / DECISIONS D49, PR #38 dcdd4dc) — build green 457
- [x] **API 판단 근거 노출** — A(조회 시 재계산), 스키마 변경 0. corsPreflight=discovered_endpoint OPTIONS 행 도출(컬럼 추가 불요).
  - [x] `ApiScorer.scoreExplain()`(신호별 key/weight/fired/contribution + total) + `score()`=`scoreExplain().total` 위임(단일 진실원). `weightsAsMap`(14키).
  - [x] `Classifier` explain 변형(9-arg nullable `rationaleOut`·8-arg null 위임·스캔경로 findings 바이트 동일) — Shadow=score / Active·Zombie=spec_match / Unused=spec_only.
  - [x] `CombinedDiscovery` 가산(`effectiveClassification`·`rationale`, 6-arg 하위호환→Finding/report_json/ETag 무영향) + `forHost` classifyExplained·effectiveView + 신규 model 6종.
  - [x] 매뉴얼 §4.3 — `api-discovery-manual.html` §4.3 에 effective 확인(`effectiveClassification`)·엔드포인트 점수 내역(`rationale[].basis`)·신호↔`signals[].key` 매핑·분류별 근거 차이(Shadow=score/Active·Zombie=spec_match/Unused=spec_only) 반영(TW, doc/34 §5).
  - [x] 테스트(scoreExplain↔score 동치·rationale↔findings 정합·분류별 basis·"type" 판별자·effective MIDDLE/CUSTOM·스캔경로 findings 동일).

### DB 테이블 명세 HTML + ER Diagram 신규 작성 + doc/18 동기 (2026-06-29, 사용자 요청, 문서만) — TW
- [x] `doc/manual/db-schema-spec.html` 신규 — 8테이블(7엔티티+domain_hostnames) 전체 컬럼 명세(의미 포함, 특히 scan_result discovered/active/shadow/zombie/unused·report_json) + inline SVG ER Diagram(자기완결, 외부 의존 0). 코드 교차검증(ScanResult·DomainConfig·ReportBuilder·DiscoveryJobService OPTIONS 제외).
- [x] `doc/18` 동기 — scan_result summary 컬럼 의미 보강(S∩D/D−S/S−D·OPTIONS 제외·discovered≠합 주의) + domain_config 누락 4컬럼 추가(discovered_at·last_seen_at·last_scan_attempt_at·next_scan_due_at@Index, doc/18 next_scan_due_at sync 후속 해소) + §2.8 인덱스 문구 정정.

### CLI CSV 내보내기(B) + Docker/podman 테스트 배포(C) (2026-06-25, doc/31·32 / DECISIONS D43, PR #23) — tests=357(347+10) 실패0 skip2(live 게이트), podman build 성공
- [x] B CLI — `main()` `--adc.cli.export-domain=` 분기(web NONE·profiles cli), `@EnableScheduling`→`SchedulingConfig(@Profile("!cli"))` 분리(서버 동일 활성·무회귀). `DomainCsvWriter` 15컬럼(source 파생=sealed Finding switch 망라·RFC4180·discovered join·score 범위밖), `CliExportRunner` exit 0/2/3/4. Loki 무접촉.
- [x] C Docker — Dockerfile 멀티스테이지(temurin:21-jdk→jre, `*-SNAPSHOT.jar` glob)+`.dockerignore`, `application-container.yml`(PG·ddl-auto update·Loki LAN, application.yml H2 불변), `adc.yaml`(podman play kube, app+postgres pod, netns localhost:5432, PGDATA `/opt/adc` 서브디렉터리=initdb 충돌 회피, exports `/opt/adc-exports` 분리), `doc/32` 런북.
> 리뷰 P1=0 P2=0 P3=2(둘 다 doc-only: CSV "14행"→"15열", `podman run` 예시 `java -jar` 중복 제거) 교정. 운영 Loki 보호: 단위 미호출·검증 `podman build` 까지. 실배포·LAN Loki 도달·실 coalesce 는 doc/32 §6 후속(P3, 테스트서버). **이로써 3부 묶음(도메인 디스커버리+CLI+배포) 전체 완료.**

### 도메인 자동 디스커버리 (A) — Loki 서버측 집계·무삭제 업서트 (2026-06-25, doc/30 / DECISIONS D42, PR #22) — tests=347(332+15) 실패0 skip2(live 게이트)
- [x] `LokiClient.queryInstant`(/loki/api/v1/query 벡터) — requestWithRetry URL 인자형 추출로 throttle/concurrency(max2)/백오프/timeout 재사용, queryRange 불변.
- [x] `DomainDiscoveryService` — sum by(domain,hostname) count_over_time(... pattern 필드15/16 | label_format coalesce(host,real_host) ...). 라인 미수신·엣지 hostname 동시 확보. 필드포지션 LogLineParser 공유 상수+교차검증 테스트.
- [x] ★무삭제 업서트 — `DomainUpserter`(@Transactional managed 단일 tx): 신규 INSERT, 기존 hostnames 합집합·lastSeenAt 만, 설정 미터치·자동삭제 없음. `DomainConfig @DynamicUpdate`+managed-tx 로 동시 PUT 설정 lost-update 구조 차단(@Version 미도입, D18 §5 일관).
- [x] 가드(FQDN 정규식·max-domains-per-run), 윈도우(롤링+부트스트랩 1h), 스케줄러 분리(@Scheduled 기본 10분·분단위 설정). 운영 Loki 보호: 단위 mock, 실 coalesce 는 `-Dloki.live` 게이트.
> 리뷰 P1=0 P2=0 P3=0. P3-1(lost-update): 1차 @DynamicUpdate 단독이 detached-merge 로 동시성 무효 → managed 단일 tx(별도 @Transactional 빈)로 정정, 실 PG 통합테스트로 4설정 보존 입증. 레이스 결정적 테스트 불가는 구조+주석으로 고정(리뷰 수용). doc/18(discovered_at/last_seen_at) sync=technical_writer 후속. B(CLI)·C(Docker)=PR2.

### 엔티티 캡슐화 — public 필드 → private + 접근자 (2026-06-25, doc/29 / DECISIONS D41, PR #21) — tests=332 불변, 엔티티 public 필드 잔존 0
- [x] 7 엔티티(Watermark·ClassificationConfig·DomainClassificationConfig·DomainConfig·SpecRecord·ScanResult·DiscoveredEndpointRecord) 약 63 public 필드 → private + 수기 getter/setter(Lombok 미도입). 블래스트 반경 오름차순 스테이지·단계별 build green.
- [x] JPA 매핑 불변 — 애너테이션 필드 유지(getter 이동 0)→field access 보존, @ElementCollection·@Lob byte[]·@Column(text) 9필드·@GeneratedValue·@Enumerated·초기화자 전부 보존. PostgresIntegrationTest podman green=PG DDL 동일 입증. doc/18 무영향.
- [x] 직렬화/ETag 불변(엔티티 직접 직렬화 경로 없음·전 컨트롤러 DTO), 생성 id 2개 setter 미노출, boolean isX(), 파생/equals/hashCode/toString 무신설. call-site 구문만 치환·테스트 기대값 0 변경.
> 리뷰 P1=0 P2=0 P3=0(독립 빌드 재현+6포인트 전수, 이슈 무잔존). 예외 1건: SpecStoreTest 생성 id 모사 라인 제거(identity add-once 유지·id 미단언→불변). **이로써 P2 품질/테스트 전 항목 완료.**

### Testcontainers(PostgreSQL) 통합 테스트 + @Lob String→text 매핑 실결함 수정 (2026-06-25, doc/28 / DECISIONS D40, PR #20) — tests=332(+13 PG) 실패 0 skip 1(LokiLive)
- [x] L52 `@Lob String` 9컬럼 PG `text` 실검증 — `information_schema.data_type='text'` 엄격 단언 + 대용량 round-trip. `raw_doc`(byte[])은 `oid` 유지(범위 밖, round-trip만).
- [x] L53 통합 테스트 — build.gradle.kts(testcontainers 3종 + `tasks.withType<Test>` DOCKER_HOST(XDG 가드)/RYUK 주입), `PostgresIntegrationTest`(@ServiceConnection postgres:16-alpine, ddl-auto=create-drop, @MockBean LokiClient), `/discovery` e2e + `/result` 조건부 GET 304.
- [x] 게이팅 무회귀 — `@Testcontainers(disabledWithoutDocker=true)` docker 부재 시 auto-skip(build green). PG 13건 실행·통과(skip 0). bogus DOCKER_HOST auto-skip 확인.
> ★실결함(D37 원칙): @Lob String 9컬럼이 PG `oid`(large object) 매핑 → 비트랜잭션 `/discovery` 에서 `Unable to access lob stream` 실패 = 실 운영결함. 테스트 느슨화 대신 5엔티티 9필드 @Lob→`@Column(columnDefinition="text")` 수정해 해소. 리뷰 P1=0 P2=1 P3=1 전건 수정. doc/18 스키마 text 동기 완료(b0db071, D28).

### catch-all {var+} dead code 제거 (2026-06-25, DECISIONS D39, PR #19) — tests=319 불변
- [x] `EndpointMatcher` `isCatchAll` 분기(compile `.+`)+헬퍼 삭제 — `{var+}`→`isVariable` `[^/]+` 일관
- [x] doc/04 §1.1 catch-all '미지원' 갱신(파서 미생성+segCount 버킷팅 충돌=vestigial, 진짜 지원=버킷팅 재설계 별도 기능)
- [x] 회귀 — `EndpointMatcherTest` 무변경 green(catch-all 도달 불가라 결과 동일)
> D37 F2 해소. 무회귀: 파서 미생성(분기 dead)·segCount 버킷팅이 다중 세그먼트 .+ 차단·도달케이스 .+≡[^/]+. F1·F2 양 플래그 모두 해소. 리뷰 P1/P2/P3=0.

### base-path-strip 옵션 — false Shadow/Unused 방지 (2026-06-25, doc/27 / DECISIONS D38, PR #18) — tests=319 green
- [x] `DomainConfig.basePathStrip`(String nullable, 기본 null=off) + `DomainController`/`DomainDtos` DTO 가산 (ddl-auto)
- [x] `EndpointMatcher.match(m,h,path,stripPrefix)` 4-arg — as-is 우선·미매칭+prefix 시 관측 path 에 stripPrefix prepend 재시도, 3-arg 하위호환
- [x] `Classifier`(7-arg/6-arg null 위임)·`DiscoveryJobService`·`CombinedDiscoveryService` basePathStrip 로드·주입
- [x] 테스트(matcher prepend/as-is 우선/null 현행/잘못 prefix no-op + e2e strip→Active·null 대조) / doc/03 §2.2·doc/18 `domain_config.base_path_strip` sync
> D37 F1 해소. 게이트웨이 base prefix strip 시 관측(/users/N)↔basePath 결합 스펙(/v2/users/{id}) 불일치 false Shadow/Unused → at-match prepend 교정. canonical/파싱/matcherCache 불변(strip=match 파라미터), 기본 null=현행 100%, as-is 우선=double-prefix/오판 방지. 리뷰 P1/P2/P3=0.

### 매칭 엣지 케이스(doc/04 §7) 회귀 테스트 (2026-06-25, doc/04 §7.1 / DECISIONS D37, PR #17) — tests=314 green
- [x] doc/04 §7.1 회귀 테스트 매핑표 보강 (케이스↔불변식↔테스트↔상태)
- [x] (EndpointMatcherTest) case3 동일 템플릿 양 method distinct 매칭 / case4 specificity front-segment 우선·동률 결정성
- [x] (ClassifierTest) case5 INFERRED 단독→shadowConfidence −0.1 격리(control=SPEC 1.0)
- [x] (확인) 기존 커버 5케이스(1·6·7·8·9)는 §7.1 명시만(중복 테스트 없음)
> 순수 테스트(프로덕션 무변경). §7 9케이스 중 5 기존커버·3 신규회귀·1 미구현(F1). F1(base-path-strip)·F2(catch-all dead code)는 미구현/dead code 라 회귀 고정 안 함→P2 후속 분리. 리뷰 P1/P2/P3=0(P3 doc 테스트명 정밀화).

### 검출/업로드 데이터 모델 통합 + 멀티 스펙 병합 전략 (2026-06-25, doc/26 / DECISIONS D35·D36, PR #16) — tests=311 green
- [x] (1단계 데이터모델) `discovered_endpoint`(검출 SoT, host idx+unique(host,method,template)+version, 누적 upsert+cap5000/prune180d) + **EndpointHistory 흡수**(endpoint_history 제거, severity recency→discovered_endpoint.firstSeen) + `spec_record.spec_name`·`discovered_endpoint.version`
- [x] (2단계 멀티스펙+모드) `DomainConfig.specMergeStrategy`(MERGE/SEPARATE/VERSION_GROUPED, 기본 MERGE) + `SpecStore` 모드 분기 + `SpecCanonicalizer.merge` 결정적(dedupe+deprecated OR+latest-wins, 순서무관) + 합성 spec 버전(SHA-256, EtagUtil 일관)
- [x] (3단계 결합·버전그룹) `CombinedDiscoveryService.forHost`(discovered ∪ active spec → Classifier 불변 → 결합 findings) + `GET /api/v1/domains/{host}/discovery` + VERSION_GROUPED 버전그룹 뷰 + `SpecSource.documents[]` 멀티문서
- [x] (doc/18 sync) `discovered_endpoint` §2.8·`spec_name`·`spec_merge_strategy` 추가·`endpoint_history` 제거(7엔티티/8테이블 유지)
> 사용자 요구: 검출(Loki)↔업로드(파일) DB 전용테이블 분리·비교(Shadow/Zombie)·도메인별 결합 목록·버전 그룹핑·병합 옵션. 검출 SoT=discovered_endpoint(spec_record 대칭), 결합은 Classifier 불변. 기본 MERGE+단일=현행 무회귀. ETag 결정적·시간非의존(content 버전·per-record 제외·lastSeen 비입력). 단계별+통합 리뷰 P1/P2/P3=0. 후속(doc/26 §11): 결합 카탈로그 OPTIONS=M3 dormant 가정(catalog acrmPresentCount 저장), 원 카탈로그 list REST 중앙 노출 P4.

### 리포트/출력 보강 3항목 — low_confidence·spec warnings·Active/Zombie params·total_dropped (2026-06-24, doc/25 / DECISIONS D34, PR #15) — tests=298 green
- [x] §A low_confidence + spec_source.warnings — `SpecParseResult(endpoints,warnings)` seam 신설·3파서 수집, `SpecRecord.warningsJson` 영속→스캔 로드→`model/SpecSource`+`DiscoveryReport.specSource`(EMPTY 폴백), Finding.Shadow/Zombie 파생 `low_confidence`(confidence<0.5)+`Summary.lowConfidence`
- [x] §B Active/Zombie params — `Finding.Active/Zombie`+`ParamCandidates`(편의 ctor=EMPTY), `Evidence` query union(멀티host)→Classifier 2차 ev.queryCandidates+spec path param. canonical query-param 미보유 한계
- [x] §C scan-status total_dropped(선택·낮음) — `ScanResult.totalDropped`(3종 합, `@Column default 0` 기존행 백필)+`ScanStatusView`, /result 불변·ETag 무영향
- [x] (공통) doc/18 DB 스키마 sync — `spec_record.warnings_json`·`scan_result.total_dropped`(엔티티/테이블 개수 불변)
> 전부 가산/파생·편의 ctor·ddl-auto = 무회귀. ETag: specSource 버전당 고정·params 이름집합 투영(count 무bump, doc/24 선례)·total_dropped scan-status 비대상. low_confidence 단일진실원=confidence(별도 섹션 아님). 리뷰 P1/P2/P3=0(P3 컬럼 DEFAULT 하드닝).

### cross-scan recency 로 Zombie severity 보강 (2026-06-24, doc/24 / DECISIONS D33, PR #14) — tests=291 green
- [x] `domain/EndpointHistory`(@Id host, @Lob `Map<specKey,EndpointObservation>`)+repository — spec 매칭만 기록(spec-bound), ddl-auto 신규 테이블, doc/18 §2.8 동기(6→7엔티티/7→8테이블)
- [x] `ZombieSeverity.of(Evidence, Instant historicalFirstSeen)` = base(doc/16 불변)+entrenchmentBonus(lifespan=lastSeen−이력firstSeen, W0.2/GRACE7d/SAT90d log, <GRACE→0). of(Evidence) 오버로드(콜드스타트=현행)
- [x] `Classifier` 6-arg classifyWithMetrics(+priorFirstSeen, 5-arg 오버로드)·`ClassificationResult`+observedTimes / `DiscoveryJobService` 이력 로드·주입·merge(min/max)·save
- [x] ETag: Zombie severity→band 투영(now() 불사용=데이터 ts→재스캔 동일 version, creep 무bump·band 전이만 bump)
- [x] 테스트 — 콜드스타트(보너스0=현행)/entrenched(band 상향)/GRACE 미만 무보너스/ETag 재스캔 동일·creep 무bump/spec-only/merge
> 절대 recency=누적 lifespan(entrenchment, 데이터 ts). 보강(additive·base 불변)이라 콜드스타트=현행 무회귀. now() 불사용+band 버킷화로 ETag 시간非의존(304 보존). 한계(doc/24 §3 추적): merge carry-forward prune(선택) defer, spec-bound 로 현규모 무해. 리뷰 P1/P2/P3=0.

### preflight vs 진짜 OPTIONS 구분 — 판정(B) + 완화 M1/M2/M3 (2026-06-24, doc/23 / DECISIONS D32, PR #13) — tests=285 green
- [x] (판정 B) 로그에 preflight 구분 신호(Origin/ACRM) 부재 → 진짜 OPTIONS↔preflight 결정 불가, 한계 확정·문서화 (doc/23 §1·§2)
- [x] (M1) `Finding.Unused.preflightAmbiguous` — Classifier 2차 패스에서 OPTIONS spec op 미관측+OPTIONS 트래픽 시 false-Unused 가시화(corsKeys 재사용·비대칭)
- [x] (M2) operator genuine-OPTIONS 힌트 — `MatcherConfig.optionsOperationPrefixes` 선언→관찰 패스 매칭→Active, spec-match 한정(과declare 안전), 중앙 API 자동(DTO)
- [x] (M3, dormant) acrm 결정적 해소 — `isPreflight=OPTIONS && acrm!=null` + 가용성 게이트(기본 idx=-1→DORMANT=현행 100%, org 로그포맷 시 ACTIVE), M1 자동 승급·M2 게이트 배타, `model/PreflightSignal` 노출
> 판정 B(한계 확정) 후 단계적 완화: M1 가시화 → M2 operator 회복 → M3 정의적 해소(org 로그포맷 의존, dormant 선구현·무회귀 구조적 보장). 'OPTIONS 절대 Shadow' 불변식·무회귀 전 단계 보존. 리뷰 P1/P2/P3=0(M1·M3 각 P3 커버리지 보강).

### distinct/분위수 대용량 근사 — Acc HLL+KLL sketch (2026-06-24, doc/22 / DECISIONS D31, PR #12) — tests=267 green
- [x] `Acc` 필드 교체 — `HashSet clients`→`HllSketch`(lgK=12), `ArrayList respTimes`→`KllDoublesSketch`(k=200) (DataSketches 6.1.1 기확보, 신규 의존성 0)
- [x] `Acc.add` — `hll.update(ip)`(null skip)·`kll.update((double)ms)`; `mergeFrom` — HLL `Union`·KLL `merge`
- [x] `Acc.toEndpoint` — distinctClients=`round(hll.getEstimate())`·p50/p95=`round(kll.getQuantile, INCLUSIVE)`(빈 sketch→0), `Metrics`(long) shape·소비처 불변
- [x] 테스트 — 정확도(HLL±3% 결정적·KLL rank 허용)·경계(distinct 0/1/2 HLL-exact→shadowConfidence)·병합(분할 union/merge≈단일)·회귀(기존 normalizer·percentile exact 단언 green 유지)
- [x] (확인) ETag 무변경(distinctClients/percentile 비입력)·CardinalityNormalizer 임계(statics.size, 근사 무관) 무변경·sketch 비영속
> 변경 Acc.java 한정(surgical). 본질=per-signature 고정크기 메모리 가드(무한 성장 ArrayList 제거). distinctClients '<=1' 경계는 HLL 소-N exact(coupon)·getQuantile INCLUSIVE=nearest-rank 동치라 분류/단언 무파손. 신규 의존성 0(DataSketches 기확보 D8). 리뷰 P1/P2/P3=0.

### $type taxonomy 샘플링 확정 + corpus $type 히스토그램 (2026-06-24, doc/21 / DECISIONS D30, PR #11) — tests=261 green
- [x] (research 0.4) doc/21 §A 프로토콜로 Loki $type 샘플링(작은 창/limit·부하보호·`limit=1e8` 금지, 총 3쿼리) → 증거표. **vocab={document,library}, API_TYPES 5값 실관측 0, document 트랩 ≈100%(api 호스트)**
- [x] (분석) 증거표 → §B 규칙 → API_TYPES 무변경 확정 + document 트랩 재확인, doc/21·DECISIONS D30 결론 기록
- [x] (Tier0) `EndpointKindClassifier.API_TYPES` 무변경 + 근거 주석(실관측 0·관례 유지·responseTypeApi dormant·자동 전파), ApiScorer 무변경 확인
- [x] (Tier1) corpus `$type` 히스토그램 — `InventoryBuilder` 집계(top-N 20+other)→`model/TypeDistribution`→`DiscoveryReport`+`ReportBuilder`+`DiscoveryJobService` ETag(distinct 키집합만, count 제외)
- [x] 테스트 — API_TYPES 매핑 5값 불변/히스토그램 집계·top-N·other·노출/ETag(신규 키 bump·count 무bump)/무회귀(확장자 1순위·document 약신호)
> 데이터 게이트: 실관측이 추가/제거 근거를 안 줘 API_TYPES 무변경 확정, responseTypeApi/$type-API_CANDIDATE dormant 확정(무감점·무해). Tier1 히스토그램은 앱별 vocab self-reporting(매 스캔, 수동 Loki 재조회 불요). ETag 는 키집합만→드리프트 bump·count 무bump.

### endpoint_kind referer 보조 신호 (2026-06-24, doc/20 / DECISIONS D29, PR #10) — tests=256 green
- [x] `model/RefererSignal`(SignalStatus·pageUrls·ratios·`dormant()`) + `model/EndpointKindSignal`(노출 status·ratios·`NONE`) + `enum SignalStatus{ACTIVE,DORMANT}`
- [x] `normalize/RefererSignalExtractor`(@Component) — static referer path 정규화→PAGE_URLS freq + static_ratio/referer_present_ratio + 게이트(원시 ratio 비교, static≥0.05 AND referer≥0.20, else dormant)
- [x] `EndpointKindClassifier` — 3-arg classify(+RefererSignal) UNKNOWN+active+PAGE_URLS≥2→WEB_PAGE(conf 0.6, 비대칭 양성), 2-arg 오버로드(dormant 위임) 하위호환, `isStaticPath()` public
- [x] `InventoryBuilder.buildWithLimits` — corpus pre-pass(RefererSignalExtractor)→classify 3-arg→`InventoryResult` 에 EndpointKindSignal
- [x] `model/DiscoveryReport` top-level `endpointKindSignal`(non-null) + `ReportBuilder.build` 인자 + `DiscoveryJobService.analyze` ETag(ratios round3, 게이트는 원시값)
- [x] 테스트 — PAGE_URLS 구축 / web_page 가점(UNKNOWN+PAGE_URLS≥2) / 비대칭(부재→UNKNOWN·무감점) / dormant 게이트 / 게이트 경계 포함성(`>=`) / $type 우선 / 2-arg 오버로드 하위호환 / 노출·ETag
> 비대칭 양성(referer 부재=무증거, 감점 없음) + dormant 게이트(실 Loki 정적 미경유 환경 무회귀). $type 우선·WEB_PAGE⊕API_CANDIDATE 배타로 doc/17 responseTypeApi 무충돌. 리뷰 P1/P2/P3=0(P3 2건 수정 후 재리뷰 클린).

### 실재성 404-only 필터 (인벤토리 단계) (2026-06-24, doc/19 / DECISIONS D27) — tests=243 green
- [x] `Acc` — `status404` 카운터(add/mergeFrom) + `isNonExistent()`(hits>0 && status404==hits) — 404 100% 만, 401/403 보존
- [x] `InventoryBuilder.buildWithLimits` — Acc 집계 후·승격/상한 전 `source==INFERRED && isNonExistent()` 제외+카운트(SPEC 보호) + `InventoryResult` 에 droppedNonExistent 추가
- [x] `model/DroppedNonExistent(int notFound)` + `DiscoveryReport` top-level(항상 non-null) + `ReportBuilder.build` 인자
- [x] `DiscoveryJobService.analyze` — droppedNonExistent → ReportBuilder 전달 + ETag 입력 추가
- [x] 테스트 — 404-only INFERRED drop·카운트 / 401·403-only 보존 / 2xx·3xx·5xx 혼재 보존 / mostly-4xx(≠100%) 보존→Classifier soft -0.7 / spec 매칭(SPEC) 보존 / reportJson·ETag 반영
> hard-drop(100%-404, INFERRED) ⊂ soft(4xx≥90%, Classifier -0.7) — 역할 분리. 통합 4xx 아닌 404 전용으로 401/403 보존. SPEC 매칭 보존.

### 문서 정합화 + 우선순위 재정렬 (2026-06-23, DECISIONS D25)
- [x] doc/09~17 의 dev 항목·후속 전수 추출 → TASKS 교차대조(누락 0). 미반영 후속 4건 추가(cross-scan recency·Active/Zombie param 노출·scan-status total·파라미터 중앙 API 확장 묶음) + HA cross-instance·warnings 채널 seam 메모
- [x] 완료된 "API 점수화 모델" 섹션 Done 이동, 보류 섹션 response_type_api 중복 제거
- [x] TODO 우선순위 재배열(P1 자체기능→P2 품질→P3 운영→P4 외부연동→보류) + 항목 간 의존 메모 + 상단 '설계문서↔TASKS 매핑' 표

### API 점수화 코어 — 점수 모델·게이트·프로파일 (2026-06-22, doc/08 / DECISIONS D15) — 린 채택
- [x] 가중치 보정: api.weble.net(API) vs dreampark(웹) 실데이터 → html penalty 제거, host_api_subdomain+cors_preflight 추가, static 강화. 분리 마진 0.82 vs 0.27 (08 §8)
- [x] `ApiScorer` — 가용 신호 가산식 점수(clamp 0..1), Classifier 앞단 게이트 (보정 weight)
- [x] 신호 추출: path shape(api/version/id/graphql/machine), write_method, query, non_browser_ua, host_api_subdomain, cors_preflight(OPTIONS sibling), static penalty(확장자/library), repeat_bonus (html penalty 미사용)
- [x] api_confidence(후보성) vs shadow/zombie confidence(실재성) 역할 분리 — Classifier 게이트 후 매칭/분류
- [x] `min_api_confidence` 게이트 → 미달 unmatched 는 보고 안 함, OPTIONS 는 CORS 신호로만(미보고)
- [x] 기존 `EndpointKindClassifier`(static/web_page)를 점수 penalty 입력으로 흡수
- [x] 프로파일 HIGH/MIDDLE/LOW preset (threshold+weights)

### response_type_api 양성 가중치 — $type API성 신호 채택 (2026-06-23, doc/17 / DECISIONS D24) — tests=237 green
- [x] `ApiScorer.Weights` 14번째 가중치 `responseTypeApi`(MIDDLE 0.25/HIGH 0.18/LOW 0.32, §9 보정전 1차값) + `WEIGHT_KEYS` 14 + `applyOverrides` 반영
- [x] `score()` 공통 섹션 `endpointKind==API_CANDIDATE → += responseTypeApi`(양성-only 비대칭, document/UNKNOWN/STATIC/부재 무가산·무감점)
- [x] 기존 `EndpointKind.API_CANDIDATE`($type∈{xhr,fetch,json,api,ajax} dominant) 재사용 — 신규 필드/Acc 불요. customWeights 자동 수용(resolver/DTO/controller 무변경)
> 08 §9 보류 사유($type taxonomy 불확실·document 트랩) 를 양성-only + 보수적 집합으로 해소(보류→채택). 무회귀: 비-API endpoint 무변경, API_CANDIDATE만 상승(보류 해제 목적).

### 설계 (2026-06-22)
- [x] 설계 문서 00~07 (개요/아키텍처/파싱·정규화/스펙·Canonical/매칭·분류/Loki수집/구현스택/MSA연동)
- [x] WAAP API Discovery 개념 정리, nginx 로그 가용/불가 항목 구분
- [x] Shadow/Zombie 정의 (문서 업로드 기반), 3종 포맷 선정(OpenAPI/Postman/CSV)
- [x] endpoint_kind 설계 (web_page vs API, 비대칭 증거 → 이후 `$type` 기반으로 강화)
- [x] Loki 주기 배치 수집 + 운영 부하 보호 설계
- [x] Java+Spring 확정, 상주 서비스 + Spring Batch 배포 모델
- [x] MSA: Worker 서비스 + 중앙 서버 연동(Pull+조건부GET), Spec Store(업로드 시 파싱·영속)

### 구현 (2026-06-22) — 58 tests green, 실 Loki e2e 검증 완료
- [x] Spring Boot 3.3 + Java 21 스캐폴딩 (Gradle, 모듈 구조)
- [x] `LogLineParser` — `^|^` 실로그 24필드, type/referer/request_id 수집
- [x] `PathNormalizer` — 휴리스틱 템플릿 추론
- [x] `EndpointKindClassifier` — `$type` 기반(확장자 1순위), web_page/static/api_candidate
- [x] `InventoryBuilder` — 시그니처 집계(hits/status/분위수/distinct), endpoint_kind 부여
- [x] `SpecFormatDetector` + `OpenApiSpecParser`(swagger-parser) + `SpecStore`(업로드 파싱·버전·Canonical 영속)
- [x] `EndpointMatcher` — 템플릿→정규식, 버킷 인덱스, specificity 우선순위, host-agnostic
- [x] `Classifier` — Shadow/Zombie/Active/Unused/WebPage 2-pass + Shadow 신뢰도, STATIC은 Shadow 제외
- [x] `ReportBuilder` + `EtagUtil` — 요약 집계 + 내용 기반 ETag(generatedAt 제외)
- [x] `DiscoveryJobService` — analyze 파이프라인 + runScan(watermark 증분) + request_id dedup
- [x] `LokiClient` — query_range 윈도우분할·페이지네이션·동시성·스로틀·429 백오프
- [x] REST API — DomainController(CRUD), SpecController(업로드/조회), ScanController(scan-status/result 조건부GET/scan), HostQueryController(hostname→domains, 온디맨드 query)
- [x] 내부 DB — H2(dev)/PostgreSQL(prod), 엔티티 DomainConfig/SpecRecord/ScanResult/Watermark
- [x] watermark 증분 + request_id dedup
- [x] 실 Loki(192.168.8.100:3200) end-to-end 호출 검증 (+분류 버그 1건 수정)

### explicit-hint 매처 + 매처 설정 (2026-06-22, doc/09 / DECISIONS D16) — tests=110 green
- [x] explicit hint 모드(`api_path_prefixes`/`api_path_regexes`) — `ApiScorer` explicit-hint 분기(pathHint weight, 내장 path-shape 비활성)
- [x] 매처 설정: `MatcherConfig`(prefixes/regexes/exclude + `include_web_forms` + NONE + merge 전역∪도메인) + `ApiHintMatcher`(세그먼트경계 prefix·full-match regex·컴파일 캐시·개수/길이 상한·비공백/'/'시작 검증·ReDoS deadline 50ms)
- [x] 게이트 `ApiScorer.evaluate→Gate`(exclude→hint admit→web-form→score), Classifier ADMIT만 Shadow, 하위호환(2-arg score/3-arg classify→NONE 위임)

### 분류 설정 DB 저장 + effective 병합 (2026-06-23, doc/10 / DECISIONS D17) — tests=147 green
- [x] 설정 저장: 전역 `ClassificationConfig`(단일 PK=1L) + 도메인 `DomainClassificationConfig`(host PK) 엔티티/리포지토리. `@Lob String` JSON(매처/custom weights)+`Double`(threshold), JSONB 미사용(H2/PG 이식)
- [x] `ClassificationProfile`(HIGH/MIDDLE/LOW/CUSTOM) + `ApiScorer`(Weights ctor/weights/presetWeights/applyOverrides·값검증) + `EffectiveClassificationResolver`(host→weights+matcher+scorer+hints 병합)
- [x] 병합(threshold 도메인>전역>preset, CUSTOM weights merge, matcher 전역∪도메인) + 무회귀(부재/seed=MIDDLE+NONE, 억제 opt-in) + fail-fast(손상 JSON·unknown 키·범위/비유한 reject) + Classifier 5-arg + DiscoveryJobService 배선

### 분류 설정 중앙 REST API + effective 캐시 (2026-06-23, doc/11 / DECISIONS D18) — tests=164 green
- [x] 중앙 API: `GET/PUT /api/v1/classification`(전역) + `GET/PUT /api/v1/domains/{host}/classification`(도메인 override+effective). `ClassificationController` + `ClassificationDtos`(5 record, MatcherConfig/Weights 재사용)
- [x] 쓰기 검증→400(저장 전 validateThreshold/validateWeightOverrides/ApiHintMatcher, 컨트롤러-로컬 `@ExceptionHandler`) + 저장 손상→500(resolver IAE→ISE 래핑 + `@ExceptionHandler(ISE)`) + 부재(전역→default/도메인→effective)/미등록 404
- [x] effective 캐시 활성화: `EffectiveClassificationResolver` `ConcurrentHashMap`+`computeIfAbsent`, PUT 시 `invalidate(host)`/`invalidateAll()`, poisoning 없음. 스캔경로 무변경(resolve 캐시 자동 경유)

### non_api dropped observation 메트릭 (2026-06-23, doc/12 / DECISIONS D19) — tests=167 green
- [x] `Classifier.classifyWithMetrics→ClassificationResult`: 게이트 DROP_* 사유별 집계(excluded/webForm/lowScore), default→fail-fast. 기존 `classify→List` 오버로드 위임 보존(하위호환)
- [x] `model/DroppedNonApi`(excluded/webForm/lowScore + `@JsonProperty total` 파생) + `DiscoveryReport` top-level `droppedNonApi`(가산적·항상 non-null) + `ReportBuilder` 전달
- [x] `DiscoveryJobService` classifyWithMetrics 전환 + ETag 입력에 droppedNonApi 포함(분포 변화 반영, 304 버그 방지). 카운트=non-OPTIONS·spec 미매칭·DROP_*. ScanResult 스키마 무변경

### 정규화 고카디널리티 방지 — T1 통계승격+상한 / T2 param 후보 / T3 sensitive (2026-06-23, doc/13 / DECISIONS D20) — tests=184 green
- [x] T1 통계 `{var}` 승격(`CardinalityNormalizer`: distinct≥20·ratio≥0.3·수렴≥0.7+형제 재병합) + 상한(host template 5000 / endpoint query param 50, 초과 drop) → `DroppedByLimit`
- [x] T1 = doc/02 §3.3 "통계적 정규화 보정 3단계"와 동일 알고리즘 → 그 항목도 함께 커버(클러스터링+카디널리티→slug 변수 추론)
- [x] T2 param 후보: `queryKeys→queryParams`(값 폐기·`ValueLenBucket` 길이버킷만) + `ParamCandidates(query/path)` → `Finding.Shadow.params` 노출, `ParamCandidateExtractor`(per-endpoint 상한)
- [x] T3 sensitive: `SensitiveKeyMatcher`(@ConfigurationProperties yml, 대소문자무시) — 키이름+flag 보존·값/버킷 억제(REDACTED, 보안신호)
- [x] 배선: `DiscoveryReport` top-level `droppedByLimit`+ETag 포함, `InventoryBuilder.buildWithLimits`(build→위임 하위호환), `Normalization/SensitiveKeyProperties`

### 스펙 파서 Postman/CSV 실구현 + 공유 정규화 (2026-06-23, doc/14 / DECISIONS D21) — tests=205 green
- [x] `PostmanSpecParser` 실구현 — Jackson 트리 item DFS(폴더 deprecated 전파), url object/string, `:var`/`{{var}}`→`{var}`, host 변수→null, `[DEPRECATED]`/`(deprecated)`/description, sourceRef
- [x] `CsvSpecParser` 실구현 — univocity 헤더검증(method/path 필수→fatal), deprecated 토큰(true/false/1/0/y/n/yes/no), BOM/따옴표, 불량행 skip+warn, `:var`→`{var}`
- [x] 공유 `SpecNormalize`(template/host)·`SpecCanonicalizer`(dedupe+deprecated OR+안정정렬, SpecStore.upload 전 포맷 균일) + SpecFormatDetector `schema.postman.com`. 신규 의존성 0, 시그니처 무변경
- [x] 3종 포맷 Canonical 동일성 테스트(`ThreeFormatEquivalenceTest`) — (method,host,template,deprecated,version) 동일(sourceRef 제외). 품질 항목 충족

### 매처 캐시 무효화 (2026-06-23, doc/15 / DECISIONS D22) — tests=212 green
- [x] `match/EndpointMatcherCache`(@Component) — `ConcurrentHashMap<host,VersionedMatcher(specVersion,matcher)>`, (host,specVersion) 키·host당 1슬롯 → stale 구조적 불가·무누수·poisoning-free(`compute` per-host 직렬화)
- [x] `SpecStore.upload` save 후 `invalidate(host)`, `DiscoveryJobService.analyze` `new EndpointMatcher`→`matcherCache.get(host,specVersion,supplier)`(specVersion=0 균일)
- [x] 순환 회피(캐시 무의존·build supplier 호출측 공급), 불변 매처 공유 안전. 무회귀(동일 spec→동일 matcher→findings/ETag 불변)

### 버전 기반 Zombie 추정 + Zombie severity (2026-06-23, doc/16 / DECISIONS D23) — tests=230 green
- [x] 버전 Zombie 추정: `VersionZombieInference`(첫 `^v(\d+)$` 버전·resourceKey {V} 페어링, 그룹 active Vmax 미만 active→추정 Zombie confidence 0.6·estimated, parseInt 오버플로 비버전 폴백)
- [x] Zombie severity: `ZombieSeverity`(결정적 score=0.5·hits(log)+0.3·2xx비율+0.2·span(log)→HIGH/MED/LOW, 외부 시계 미사용, 모든 Zombie 적용), `model/Severity`(+@JsonProperty band 파생)·`SeverityBand`
- [x] `Finding.Zombie` severity+estimated 가산, `Classifier` observedSpecKeys Set→Map<Evidence>(host-agnostic 합산), 명시 1.0/추정 0.6. confidence↔severity 직교. 무회귀(명시 Zombie 1.0 보존·비버전 spec 현행)
