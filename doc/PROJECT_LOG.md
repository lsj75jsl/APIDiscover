# PROJECT LOG — 작업 내역 (세션 메모리)

> 새 세션 시작 시 참고. 최신 항목이 위로 오도록 역순 기록.
> 형식: `## YYYY-MM-DD 세션 N — 제목` + 한 일/결과/다음 단계.

---

## 2026-06-29 세션 58 — REST API 매뉴얼 M7a(spec 멀티문서 + API 상태추적) 갱신 (PR #44/#45 머지+재배포 201d0b5, 문서만, TW)

### 한 일
- `doc/manual/api-rest-manual.html` §2.4 를 M7a 머지 + 재업로드 500 수정 + 재배포 라이브 실데이터에 맞춰 갱신. SpecController·SpecChanges·SpecStore 실코드 교차검증.
- **PUT /spec 보강** — `?filename`→specName 도출(정규화 trim·소문자, 미전달/빈=`default`), 다른 filename=별개 문서·동일 filename 재업로드=새 버전(구버전 active=false 보존=diff 기준), `Content-Type: application/octet-stream` 명시. 재업로드 200(이전 self-invocation tx 500 수정 PR #45). 기존 예시의 부정확한 `specName:"default"`(filename=petstore-v1.json) → 도출 규칙대로 교정.
- **신규 GET /spec/changes 절** — specName 별 현 active vs 직전 diff(ADDED/DELETED/UPDATED·UNCHANGED 미보고), 동일성=method+pathTemplate, `?specName/status/from/to` 파라미터, 실데이터 JSON(spec-demo.example.com v1→v2). ★`updatedScope:"deprecated_version_only"` 한계 자기노출 콜아웃(deprecated·version 만, query/body param 미검출·path 변경=ADDED+DELETED, M7b 후속). 최초=previousVersion null·전ADDED, 무스펙=documents:[], servers /v2 prefix 반영 명시.
- **요약표** GET /spec/changes 행 추가·GET /spec specName 보강. **TODO** 'M7 보류' 제거 → M7b(param-level UPDATED)만 유지(M7a 완료 명시). oid→bytea(D51) TODO 유지.

### 결과
- HTMLParser OK·HTML 태그 균형 EMPTY·신규/변경 정합·앵커 s2-1~s2-6·실데이터(updatedScope·comparedVersion·201d0b5) 검증. 자기완결(외부 의존 0). 운영 Loki 미호출(spec=파싱·DB only). main 직접 커밋. TASKS M7a 매뉴얼 후속 [x].
- 다음 단계: 매니저 검증 대기.

## 2026-06-29 세션 57 — 실배포 버그: PUT /spec 동일 filename 재업로드 500 (rawDoc oid · @Transactional self-invocation)

### 한 일
- **근본원인(self-invocation)**: `SpecStore.upload` 의 **4-arg core 에만** `@Transactional`, 진입 오버로드(2-arg·3-arg filename·3-arg specName)는 비-@Transactional. `SpecController.upload` 가 3-arg(filename)를 호출 → 그 안에서 4-arg core 를 **self-invocation**(같은 빈) → Spring 프록시 미적용 → `@Transactional` 무력 → auto-commit. 동일 specName 재업로드 시 MERGE 비활성화 루프(`findByHostAndActiveIsTrue`→`prev.setActive(false)` = rawDoc oid 엔티티 로드)가 auto-commit → `Large Objects may not be used in auto-commit mode`/`Unable to access lob stream` 500. 첫 업로드(비활성 대상 0)는 통과, ★재업로드부터 발현. PostgresIntegrationTest 의 v1→v2(M7a)는 테스트 메서드 tx 가 감싸 가렸음.
- **수정**: 진입 오버로드 3개(2-arg·3-arg filename·3-arg specName)에 `@Transactional` 추가 → 컨트롤러 호출이 프록시 경유로 tx 시작 → 내부 self-call 4-arg core 가 그 tx 안(REQUIRED 전파)에서 실행 → oid 엔티티 로드 안전.
- **★실 PG 회귀가드(MockMvc=실 HTTP 경로, 테스트 tx 미적용)**: `PostgresIntegrationTest.reuploadSameFilenameViaHttpDoesNotHit500` — MockMvc PUT /spec 동일 filename 2회 업로드 → 2번째 200(500 아님) + /spec/changes diff 정상(/v2/orders/{id} ADDED). ★단순 repo.save/서비스 직접호출은 tx 가 가리므로 반드시 MockMvc 경로. 진위: 진입 @Transactional 제거 시 정확히 `Large Objects may not be used in auto-commit mode`/`Unable to access lob stream` RED 확인 후 복원→GREEN.

### 결과
- `./gradlew build`(podman) BUILD SUCCESSFUL. 전체 **497**(+1) 실패0 errors0 skip2(-Dloki.live). PostgresIntegrationTest **29/29 PASS(skip 0)**. 운영 Loki 미호출(spec=파싱·DB only). 스키마 변경 0.
- 문서: doc/28 §10·DECISIONS D51(self-invocation 추가·교훈)·이 로그. ★교훈: 같은 빈 self-invocation 은 callee @Transactional(전 프록시 어드바이스) 무력 → 외부 진입 메서드에 tx 를 둬야.

### 다음 단계
- 커밋 금지(매니저, 브랜치 fix/spec-reupload-tx-self-invocation). 머지 후 재배포 시 동일 filename 재업로드 검증 권장. rawDoc bytea 마이그레이션은 선택 후속(별도).

## 2026-06-29 세션 56 — M7a: spec 멀티문서 관리 + API 상태추적(ADDED/DELETED/UPDATED, doc/36)

### 한 일
- **M7.1 멀티문서 버전관리**: `SpecStore.upload(host,content,filename)` 의 specName 을 ★filename 에서 도출(trim·소문자, 미전달/빈=`"default"` 하위호환). 다른 filename=별개 문서, 동일 filename 재업로드=그 specName 새 버전(기존 MERGE 가 같은 specName 비활성·구active 보존). loadActiveCanonical·specMergeStrategy 불변.
- **M7.2 상태추적(compute-on-read·스키마 0)**: `domain/SpecCanonicalProjection`(canonicalJson 포함·**rawDoc oid 미선택**) + `SpecRecordRepository.findCanonicalVersions`(JPQL 생성자식·`coalesce(specName,'default')` 레거시 null 매칭·specVersion desc). `spec/SpecDiffService`(현 active vs 직전 inactive[같은 specName active=false 중 최대 버전] canonicalJson 역직렬화 → method+path_template 맵 → ADDED/DELETED 완전 + UPDATED=deprecated/version, TreeMap 정렬 결정적, UNCHANGED 미보고). ★projection-only(loadVersions 단일 지점)로 비-tx oid materialize 회피(D51).
- **노출**: `GET /api/v1/domains/{host}/spec/changes`(SpecController, M6 목록과 분리). 기본=active 전 specName 현 vs 직전, `?specName/from/to/status` 필터, host 정규화(null→400). 응답=specName 별 {comparedVersion·previousVersion(없으면 null)·changes[{method·pathTemplate·status·changed·changedDetail}]} + 최상위 `updatedScope="deprecated_version_only"`(M7a UPDATED 한계 자기노출). DTO `spec/SpecChanges`(api.dto↔spec 순환 회피로 spec 패키지).
- **★UPDATED 범위=M7a(deprecated/version 만)**: param-level 변경은 canonical 미보유라 미검출(M7b 후속·access-log param 묶음). path 변경=다른 template=ADDED+DELETED. updatedScope 로 한계 노출.

### 결과
- `./gradlew build`(podman) BUILD SUCCESSFUL. 전체 **496**(+7) 실패0 errors0 skip2(-Dloki.live). PostgresIntegrationTest **28/28 PASS(skip 0)**: M7a 실 PG 4건(① v1→v2 ADDED/DELETED/deprecated-UPDATED·previousVersion ② 최초=전ADDED·previous null ③ 멀티문서 specName 분리·?specName ④ param-only 미보고+updatedScope). 단위 `SpecControllerTest`+2(/changes 위임·status 파싱·host 400)·`SpecStoreTest`+1(specName 도출). 운영 Loki 미호출(spec=파싱·DB only).
- **★oid 가드 RED-확인**: loadVersions 를 엔티티 로드(rawDoc oid)로 임시 원복 → M7a /spec/changes 4건 전부 `Large Objects may not be used in auto-commit mode`/`Unable to access lob stream`(JpaSystemException) RED 확인 후 복원 → GREEN. projection-only 가 실제 oid 500 을 막음을 실증(H2 미재현).
- 문서: doc/36 §9(M7a [x])·TASKS·이 로그. ★스키마 변경 0·재배포 불요.

### 다음 단계
- 커밋 금지(매니저, 브랜치 feat/m7a-spec-changes). 매뉴얼(/spec/changes·UPDATED (a) 한계)=TW 후속. M7b(canonical param 추출→param-level UPDATED)=후속 PR(access-log param 묶음·별도 설계).

## 2026-06-29 세션 55 — REST API 매뉴얼 일괄 갱신 (doc/35 배치 머지+재배포 c435d9f, 문서만, TW)

### 한 일
- `doc/manual/api-rest-manual.html` 를 doc/35 배치(삭제·수정·신규) + 재배포 라이브 실데이터에 맞춰 일괄 갱신. 컨트롤러 6종 실코드 교차검증 후 반영.
- **삭제** — `/hostnames/{hostname}/domains`·`POST .../query` 절 통째 제거(HostQueryController 삭제), 요약표·TOC·footer 근거 정리(actuator §2.7→§2.6 재번호).
- **변경** — GET /domains 페이지네이션(body=배열 + 헤더 X-Total-Count 45592/X-Total-Pages/X-Current-Page, ?page·?size[1,1000]) · GET/{host} DomainDetailView(lastScanAt·effectiveClassification·spec filename) · PUT 부분수정(present-only, enabled 미전달=유지·[]=비우기) · scan-status latestSpec · ★result rationale 가산(+시점차 caveat) · ★GET /spec 단일/404 → 배열/[]/200(Breaking, 실데이터) · PUT /spec ?filename.
- **신규** — POST /scan-now(동기 즉시스캔, 미등록 자동등록, CombinedDiscovery 반환, ⚠️Loki 동기·502) · PATCH /classification/weights(전역·도메인, 부분편집→profile CUSTOM·미편집 키 유지·unknown 400).
- **W1** — /classification 절에 thresholdOverride·customWeights·matcher 의미+적용 표 + 우선순위. **제약** M6 GET /spec 배열(Breaking) 추가·목록 페이지네이션 갱신·Loki 행 scan-now 반영(oid 500 은 수정완료라 제외). **TODO** M7 보류·rawDoc oid→bytea(D51) 추가.

### 결과
- HTMLParser OK·HTML 태그 균형 EMPTY·삭제(/hostnames 0)·신규/변경 정합·앵커 s2-1~s2-6 연속·실데이터(45592·c435d9f·apiSegment 0.7) 검증. 자기완결(외부 의존 0). 운영 Loki 미호출(캡처만). main 직접 커밋. TASKS W1 [x]·배치 매뉴얼 완료 표기.
- 다음 단계: 매니저 검증 대기.

## 2026-06-29 세션 54 — 실배포 버그 수정: GET /spec·M2·M4 500 (rawDoc oid auto-commit, fix/spec-meta-oid-autocommit)

### 한 일
- **근본원인(실 PG 스택트레이스 확정)**: `SpecRecord.rawDoc=@Lob byte[]`=PG `oid`(유일 @Lob). REST 메타 조회(M2 GET /domains/{host}·M4 GET /scan-status·M6 GET /spec)가 `@Transactional` 없이(application.yml `open-in-view: false`→auto-commit) SpecRecord **엔티티**를 로드 → Hibernate 가 rawDoc oid 를 materialize → `PSQLException: Large Objects may not be used in auto-commit mode`/`JpaSystemException: Unable to access lob stream` → 500. prod 는 업로드 스펙 0 이라 잠복(H2·기존 PG 통합은 'spec 업로드 후 메타 GET' 미커버).
- **수정(projection, 스키마·oid 마이그레이션 없음)**: `domain/SpecMetaProjection`(record) + `SpecRecordRepository.findActiveSpecMetas`(JPQL 생성자식, 메타 컬럼만 SELECT·rawDoc/canonicalJson/warningsJson 미선택). SpecStore `activeSpecMetas`(M6)·`latestSpecMeta`(M2/M4) projection 신설. REST 소비처(SpecController.meta·DomainController.toView/get·ScanController.status) 전환. `SpecMetaView.of(SpecMetaProjection)` 오버로드. ★엔티티 반환(activeMeta/activeRecords)은 스캔 경로(@Transactional analyze, line 191) 전용 유지(미변경). SpecRecord.rawDoc 가드 주석 + 대안 기각(bytea/메타조회 @Transactional) 명시.
- **forHost 동일버그도 같은 PR 에서 수정(매니저 지시)**: `CombinedDiscoveryService.forHost`(/discovery·/result M5 진입점)가 비-@Transactional·OSIV off 로 `loadActiveCanonical`/`activeRecords` 엔티티(rawDoc oid) 로드 → spec 보유 도메인서 동일 500. 수정 = `forHost` 에 **`@Transactional(readOnly=true)`**(진입점 1곳 tx → 내부 엔티티 로드 안전, 읽기+분류 전용이라 readOnly 적합·LOB 호스트당 active spec 소수라 bounded). 공유 스캔 메서드 `loadActiveCanonical` 미터치(analyze 무영향·저위험).
- **M6 정렬 결정성(P3 보완)**: M6 projection 쿼리 ORDER BY 에 `specName asc nulls first` 명시(specName 레거시 null 가능). h2-pg-null-ordering-trap(H2 ASC=NULLS FIRST 가 PG ASC=NULLS LAST 발산을 가림, D48 findDueForScan 동형) → 기존 인메모리 `Comparator.nullsFirst` 동작·결정성 일치.
- **★실 PG 회귀가드 3건(H2 재현 불가, podman Testcontainers)**: ① `specMetaEndpointsDoNotMaterializeRawDocOidInAutoCommit`(M2/M4/M6 200) ② `forHostEndpointsTolerateRawDocOidSpecOnRealPg`(rawDoc 보유 spec 도메인 GET /discovery·/result 200) ③ `specListNullSpecNameOrdersFirstDeterministicallyOnRealPg`(specName=null 행 nulls-first 결정적). ★진위 증명: 각 fix 임시 원복(projection→엔티티 / @Transactional 제거 / nulls first 제거) 시 RED — ①②=`Large Objects may not be used in auto-commit mode`/`Unable to access lob stream`(JpaSystemException), ③=PG NULLS LAST 로 `$[0].filename expected:<a-null.yaml> but was:<b-zzz.yaml>` 순서 발산 — 확인 후 복원 → GREEN.

### 결과
- `./gradlew build`(podman) BUILD SUCCESSFUL. 전체 **489**(+3) 실패0 errors0 skip2(-Dloki.live). PostgresIntegrationTest **24/24 PASS(skip 0)** — oid 회귀 2건 + nulls-first 정렬 1건 포함. 운영 Loki 미호출(DB 조회 경로). 기존 SpecControllerTest/DomainControllerTest/ScanControllerTest(mock)는 projection 메서드로 갱신 후 green.
- 문서: doc/28 §10(가정 정정·버그·메타조회+forHost 수정 기록)·DECISIONS D51·TASKS·이 로그.

### 다음 단계
- 커밋 금지(매니저, 브랜치 fix/spec-meta-oid-autocommit). ★머지 후 재배포 시 검증 권장. rawDoc bytea 마이그레이션은 선택 후속(별도).

## 2026-06-29 세션 53 — REST API 배치 PR4(P3 A1 즉시스캔, doc/35) — P1·P3 완료, 마지막 기능 PR

### 한 일
- **A1 `POST /api/v1/domains/{host}/scan-now`(동기 즉시 스캔)**: ScanController 에 추가. 동작 순서 — ① `DomainNames.normalize(host)`(null→400 BAD_REQUEST; DomainController.requireNormalizedHost 는 private 이라 util 직접 사용) ② `DomainRegistrar.registerIfAbsent`(미등록 자동등록, enabled=true·discoveredAt=null — CLI -scan 자동등록 일관) ③ `jobService.onDemandWindow(?window)`(상한=scan.max-window) ④ `jobService.scanOnDemand(host, w, null)`(★watermark 미전진, doc/33 §7 온디맨드 스냅샷) ⑤ `CombinedDiscoveryService.forHost(host)` 반환(findings+rationale+effectiveClassification, /discovery 일관). ScanController 에 DomainRegistrar 주입 추가.
- **기존 `POST /scan`(비동기 202·runScan 스케줄 트리거)와 구분**: 둘 다 유지. javadoc 에 'busy 도메인 지연 가능 → window 작게·비동기는 POST /scan' 명시.
- **에러 매핑**: 정규화 실패=400, scanOnDemand(Loki 수집/분석) 실패=502 BAD_GATEWAY(try/catch RuntimeException), 등록 실패=uncaught→500. Loki 부하보호(slice·throttle·동시·백오프·LokiBudget)는 scanOnDemand 내부 준수·window 상한으로 폭주 차단(우회 없음).

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 전체 **486**(+6) 실패0 errors0 skip2(-Dloki.live). `ScanControllerTest` +6(미등록 자동등록+scanOnDemand+forHost 반환·멱등·정규화 FOO→foo·window 위임·blank 400·Loki실패 502). ★전부 mock(registrar/jobService/combined) — 운영 Loki(192.168.8.100:3200) 미호출. PostgresIntegrationTest 등 기존 무회귀.
- ★P1(D1·M1·M2·M3·M4·M5·M6·A2 + filename) + P3(A1) 전부 완료 = doc/35 기능 PR 종료. P2(M7)=보류만 잔존(추후 access-log 파라미터 추출과 함께).

### 다음 단계
- 커밋 금지(매니저, 브랜치 feat/api-batch-p3-scannow). 매뉴얼(api-rest-manual.html: POST /scan-now)=TW 후속. ★spec_record.filename(PR2) 포함 일괄 재배포는 매니저. M7=보류.

## 2026-06-29 세션 52 — REST API 배치 PR3(M5 /result rationale + A2 가중치 편집, doc/35) — P1 완료

### 한 일
- **M5 GET /result rationale 가산(조회시 재계산)**: `ScanController.result()` 200 경로에서 report_json 파싱(ObjectNode)→`rationale` 키 주입→재직렬화. rationale = `CombinedDiscoveryService.forHost(host).rationale()`(/discovery 와 동일 메커니즘, 현재 discovered+spec+effective 재계산, doc/34). ★report_json 기존 필드 불변(중앙 additive-safe), ETag=r.version 유지(rationale 는 ETag 입력 아님), 304 경로 보존(rationale 미재계산), report null=204. ScanController 에 CombinedDiscoveryService·ObjectMapper 주입. caveat 주석(findings=스캔시점 vs rationale=현재).
- **A2 PATCH /classification/weights(도메인·전역)**: 부분 weight map → ① 현 effective 14 스냅샷(도메인=`resolver.resolve(host).weights()`, 전역=신규 `resolver.resolveGlobal()`) ② profile=CUSTOM 자동 ③ customWeights = 스냅샷 ∪ 요청(요청 키 override) → ★편집 안 한 키는 현 effective 유지(MIDDLE 리셋 방지). validateWeightOverrides(unknown/비유한)→400. threshold·matcher 미터치(weights 만). resolver.invalidate(All). 기존 PUT /classification 불변(가산).
- **resolver `resolveGlobal()` 신규**: build(host) 를 `buildFrom(global, domain, label)` 로 추출 → resolveGlobal=buildFrom(global, null, "global"). A2 전역 스냅샷용(단일 진실원, 병합 로직 중복 0).

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 전체 **480**(+8) 실패0 errors0 skip2(-Dloki.live). `ScanControllerTest` +3(rationale 주입·기존 필드/ETag 불변·304 미재계산·204)·`ClassificationControllerTest` +5(도메인/전역 PATCH→CUSTOM·편집 안 한 키 HIGH 유지·threshold 미터치·unknown 400·PUT 무회귀). PostgresIntegrationTest 21(/result 실 report_json rationale 주입 검증)·기존 무회귀. 운영 Loki 미호출(M5=DB 재계산·A2=설정 저장).
- ★P1(D1·M1·M2·M3·M4·M5·M6·A2 + filename) 전부 완료.

### 다음 단계
- 커밋 금지(매니저, 브랜치 feat/api-batch-p1-result-weights). 매뉴얼(api-rest-manual.html: /result rationale·PATCH weights)=TW 후속. 후속 PR: P3(A1 즉시스캔). P2(M7)=보류.

## 2026-06-29 세션 51 — REST API 배치 PR2(spec-meta 노출: filename + M2·M4·M6, doc/35)

### 한 일
- **스키마 ADD `spec_record.filename`**: `SpecRecord` 에 `filename` 컬럼+접근자(ddl-auto update·기존행 null·무손실). ★머지 후 재배포 필요.
- **filename 수신**: `SpecStore.upload` 에 filename 오버로드 추가(`upload(host,content,filename)`·`upload(host,specName,content,filename)` core, 기존 2/3-arg 는 filename=null 위임=무회귀). `SpecController.upload` 에 `@RequestParam(required=false) String filename` → 저장.
- **M6 GET /spec 복수**: 단일 `SpecMetaView`(404) → flat `List<SpecMetaView>`(200, 무스펙=[]). `SpecMetaView` 에 `specName`·`filename`·`active` 가산(additive — 단일 meta 소비처 무회귀). `activeRecords` specName/version 정렬(결정적). 별도 wrapper DTO 안 만듦(린).
- **M2 GET /domains/{host} 보강**: 신규 `DomainDetailView`(DomainView 필드 + `lastScanAt`·`effectiveClassification`). ★목록(M1)은 경량 `DomainView` 유지 — 단건 get() 만 scanRepo.findById·resolver.resolve 조회(page 당 N+1 회귀 방지). DomainController 에 ScanResultRepository·EffectiveClassificationResolver 주입.
- **M4 GET /scan-status 보강**: `ScanStatusView` 에 `latestSpec`(SpecMetaView 재사용=filename·uploadedAt·endpointCount) 가산. ScanController 에 SpecStore 주입.
- **effective 빌더 공유**: `EffectiveClassification.toView()`(classify→model) 추출 — CombinedDiscoveryService(/discovery)·DomainController(M2) 가 공유(중복 제거). CombinedDiscoveryService 의 private effectiveView 제거.
- **SpecMetaView 매핑 단일화**: `SpecMetaView.of(SpecRecord)` 팩토리 — upload/M2/M4/M6 공유(7-arg 생성 중복 제거).

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 전체 **472**(+8) 실패0 errors0 skip2(-Dloki.live). 신규 `SpecControllerTest` 3(filename 수신·M6 목록 정렬·빈배열)·`ScanControllerTest` 2(latestSpec·null)·`DomainControllerTest` +2(M2 detail·null)·`SpecStoreTest` +1(filename 저장/null). CombinedDiscoveryServiceTest(effective toView 공유)·integration(/discovery·/result) 무회귀. 운영 Loki 미호출.

### 다음 단계
- 커밋 금지(매니저, 브랜치 feat/api-batch-p1-specmeta). ★filename 컬럼 추가 → 머지 후 재배포 필요. 매뉴얼(api-rest-manual.html: /spec 복수·PUT ?filename·/domains/{host} 보강·/scan-status latestSpec)=TW 후속. 후속 PR: M5·A2·P3(A1). P2(M7)=보류.

## 2026-06-29 세션 50 — REST API 배치 PR1(도메인 엔드포인트 D1+M1+M3, doc/35)

### 한 일
- **권위 스펙=doc/35**(D1·M1·M3). ★사용자 확정 반영: M1=배열 body+헤더(페이지객체 아님), M7=이번 보류.
- **D1 — HostQueryController 제거**: `GET /api/v1/hostnames/{}/domains`·`POST .../query` 컨트롤러 통째 삭제. orphan `DomainConfigRepository.findByHostname` 도 제거(grep 으로 HostQueryController 전용·타 사용처 0 확인 후). ScanStatusView/SummaryView·runOnDemand 는 타 사용처 있어 보존.
- **M1 — GET /domains 페이지네이션**: ★body=JSON 배열(`ResponseEntity<List<DomainView>>`) 유지(원소 shape 불변·non-breaking) + 페이지 정보는 헤더(`X-Total-Count`·`X-Total-Pages`·`X-Current-Page` 0-based). `?page`(0-based·음수→0)·`?size`([1,1000] clamp), `findAll(PageRequest.of(page,size,Sort.by("host")))` 결정적 정렬. page 초과=빈 배열+헤더. N+1(toView→activeMeta)은 page 당 ≤1000 로 page-bounded 완화(기존 14k 전건)·batch meta 로드는 후속(ponytail 주석).
- **M3 — PUT /domains/{host} 부분수정**: `DomainUpsert.enabled` `boolean`→`Boolean`(미전달 null/명시 false 구분). `apply()` 를 present-only(전 필드 `if(req.x()!=null) set`)로 일관화 — 미전달=기존 유지, hostnames=[](non-null)=비우기/null=유지. ★create 무회귀: fresh `DomainConfig` 기본값(enabled=true·hostnames=[]·MERGE)에 present-only 적용 → 미전달 시 기본값 유지(별도 분기 불요, 단일 apply 공유).

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 전체 **464**(+7) 실패0 errors0 skip2(-Dloki.live). `DomainControllerTest` 14(M1 배열+헤더·size/page clamp·초과=빈배열 / M3 present-only 유지·[]비우기·전항목 무회귀·create null→true). 제거 심볼 잔존 참조 0. 운영 Loki 미호출(DB/웹 계층만).
- 문서: doc/35(M1 배열+헤더 갱신·§1 표·M7 보류·dev 체크리스트 D1/M1/M3 [x])·TASKS·이 로그. 매뉴얼 api-rest-manual.html(/hostnames 제거·페이지네이션·PUT 부분수정)=TW 후속.

### 다음 단계
- 커밋 금지(매니저, 브랜치 feat/api-batch-p1-domain). 매니저 리뷰/머지. 후속 PR: P1 잔여(filename·M2·M4·M5·M6·A2)·P3(A1). M7=보류(access-log 파라미터 추출과 함께).

## 2026-06-29 세션 49 — REST API 사용 매뉴얼 신규 작성 (사용자 요청, 문서만, TW)

### 한 일
- VM 신규 이미지 재배포(8b37f89, 데이터 보존) 후 라이브 캡처본 기반으로 `doc/manual/api-rest-manual.html` 신규 작성. 전 엔드포인트(8그룹/18종)별 메서드·경로·용도·curl 예시·실제 응답 JSON + Loki 호출 표기(⚠️=POST /scan·POST /hostnames/.../query 만).
- 컨트롤러 6종(`DomainController`·`ScanController`·`CombinedDiscoveryController`·`SpecController`·`ClassificationController`·`HostQueryController`) 라우트·상태코드 교차검증. ★브리프의 `GET /domains` 트림 예시(host 문자열 배열)는 실제 `List<DomainView>` 객체 배열이라 정확한 shape 로 기술(N+1 spec 조회 주의 일치). `GET /hostnames/{hostname}/domains` 만 `List<String>`.
- 조건부 GET(ETag `"version"`→304) 실증 절, ★현재 제약사항 절(인증 없음·8080 외부차단·무페이지네이션 N+1·effective 재계산·카탈로그 vs 스캔 수치 차·최신1건 보관·Loki 부하·dropped 미노출), ★TODO 절(인증 최우선 등) 포함.

### 결과
- HTMLParser OK·HTML 태그 균형 EMPTY·전 엔드포인트 12경로·§4/§5·실데이터 정합 검증. 자기완결(외부 의존 0). 운영 Loki 미호출(캡처 데이터만). main 직접 커밋.
- 다음 단계: 매니저 검증 대기.

## 2026-06-29 세션 48 — API 판단 근거 노출 매뉴얼 §4.3 갱신 (PR #38 후속, 문서만, TW)

### 한 일
- 권위 스펙 doc/34 §5(매뉴얼 변경 스펙)·§2(응답 JSON) 정독 + 머지 코드(dcdd4dc) 실제 필드명 교차검증 후 `api-discovery-manual.html` §4.3 편집.
- (유지) HIGH/MIDDLE/LOW preset 가중치·threshold 표 그대로.
- (추가) "실제 점수 내역 읽는 법" h4 블록 3개 — ① `effectiveClassification`(profile·threshold·weightsSource·weights 14키) 확인법 + 필드 표, ② `rationale[].basis`(score 예시 JSON) + 신호↔`signals[].key` 매핑·`fired`/`contribution`/`apiScore`/`threshold`/`gate`/`mode` 읽는 법 표, ③ ★분류별 근거 차이(Shadow=score / Active·Zombie=spec_match / Unused=spec_only / WebPage=endpoint_kind) 표 + Active/Unused 예시 + "Active 에 점수 없음" 경고.
- 코드 검증으로 확정한 리터럴: `weightsSource` preset|custom, `basis.mode` pathless(=§4.2 strict)|explicit_hint(=§4.2 explicit), `gate` ADMIT(rationale 는 finding 된 것만→항상 ADMIT), `basis.type` score|spec_match|spec_only|endpoint_kind, WEIGHT_KEYS 14키=§4.3 표 일치.
- TASKS Part B subitem(매뉴얼 §4.3) [x] + 부모 [x] Done 이동, doc/34 §5·§7·헤더 동기.

### 결과
- HTMLParser OK·HTML 태그 균형 EMPTY·신규 h4 4개·잘못된 리터럴 0. 자기완결(외부 리소스 무추가). 운영 Loki 미호출. main 직접 커밋.
- 다음 단계: 매니저 검증 대기.

## 2026-06-29 세션 47 — API 판단 근거(점수 산출 내역) 노출 (doc/34, A=조회시 재계산, 스키마 변경 0)

### 한 일
- **사용자 요구**: "어떤 항목에 어떤 점수로 어떤 기준으로 API 판단됐는지"를 `GET /discovery` 에서 노출. 권위 스펙=doc/34 §3+§7. 사용자 확인 완료=A(조회시 재계산).
- **`ApiScorer.scoreExplain(d,cors,hints)→ScoreBreakdown{total, signals[SignalContribution{key,weight,fired,contribution}]}`**: 14신호 평가 내역. `score()` 를 `scoreExplain().total()` 위임 → **발화 조건 단일 진실원**(드리프트 차단). 부동소수 합 동일(미발화=0.0 가산)이라 기존 score 값 무변경. `weightsAsMap`(effective 14키 맵) 추가.
- **`Classifier` explain 변형**: 8-arg(스캔 경로) core 에 nullable `rationaleOut` 추가한 9-arg core 로 리팩터(8-arg→9-arg null 위임). `rationaleOut!=null`(=`classifyExplained` 진입점, /discovery 전용)일 때만 각 finding 과 병렬로 근거 수집 — Shadow=ScoreBasis(scoreExplain·gate·mode·signals)·Active/Zombie=SpecMatchBasis(deprecated/estimated)·Unused=SpecOnlyBasis. **스캔 경로(rationaleOut=null)는 findings/dropped/preflight 바이트 동일**(report_json·ETag 불변). WebPage=KindBasis 는 Classifier 가 WebPage finding 미산출이라 생성처 없음(sealed 4종 완전성만).
- **모델 신규(model 패키지, classify→model 단방향 유지)**: `SignalContribution`·`ScoreBreakdown`·`ApiBasis`(sealed 4종 `@JsonTypeInfo` "type" 판별자)·`EndpointRationale`·`EffectiveClassificationView`. `CombinedDiscovery` 에 `effectiveClassification`·`rationale` 2필드 가산 + **6-arg 하위호환 생성자**(CLI export 등 findings-only 경로 무회귀). 
- **`CombinedDiscoveryService.forHost`**: `classifyExplained` 호출·`effectiveView`(profile·threshold·weightsSource(CUSTOM=custom)·14키 weights) 동봉. eff·discovered 이미 보유 → 추가 조회 0.

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 전체 **457**(+12) 실패0 errors0 skip2(-Dloki.live). 신규 `ClassifierExplainTest` 7(rationale↔findings 정합·분류별 basis·basis JSON "type"·스캔경로 findings 동일)·`ApiScorerTest`+3(scoreExplain 동치·신호 내역·mode)·`CombinedDiscoveryServiceTest`+2(effective MIDDLE/CUSTOM·rationale 병렬). ReportBuilderTest·CliExportRunnerTest·DomainCsvWriterTest green=스캔/리포트/CLI 무회귀. 운영 Loki 미호출(mock/정적).
- ETag/조건부GET 무영향: /discovery 는 plain GET, report_json 은 별도 스캔 경로 산출(CombinedDiscovery 미사용).

### 다음 단계
- 커밋 금지(매니저, 브랜치 feat/api-rationale-exposure). 매니저 리뷰/머지. 매뉴얼 §4.3(effective 확인·점수 내역 예시·분류별 근거 차이)은 technical_writer 후속(dev 범위 밖).

## 2026-06-29 세션 46 — DB 테이블 명세 HTML + ER Diagram 신규 작성 (사용자 요청, 문서만, TW)

### 한 일
- `doc/manual/db-schema-spec.html` 신규 — 8테이블(7엔티티 + `domain_hostnames`) 전체 컬럼 명세(의미 포함) + inline SVG ER Diagram(자기완결, 외부 의존 0·CDN/JS 라이브러리 없음). 기존 매뉴얼 CSS 토큰 재사용(화이트 테마). ER 은 `domain_config` 허브 방사형(1:1 위성·1:N 자식·전역 상속 점선), 범례·텍스트 fallback `<pre>` 동봉.
- ★사용자 지적 핵심 = `scan_result` 카운트 의미. 코드 교차검증으로 확정: `discovered`=관측 인벤토리(OPTIONS 제외, `DiscoveryJobService:246-248`)·`active`=S∩D·`shadow`=D−S·`unused`=S−D·`report_json`=전체 `DiscoveryReport` 본체(중앙 조건부 GET). `web_page` 분류 요약 제외(`ReportBuilder:48`) → `discovered ≠ active+shadow+zombie+unused` 주의 명시.
- `doc/18` 동기 — (1) scan_result summary 컬럼 의미 보강 + 주의 노트, (2) `domain_config` 누락 4컬럼 추가(`discovered_at`·`last_seen_at`·`last_scan_attempt_at`·`next_scan_due_at`@Index — 실 엔티티 확인), (3) §2.8 "유일하게 @Index" 문구 정정(domain_config 도 단일 컬럼 인덱스 보유), (4) §4 교차참조 출처 보강.

### 결과
- HTMLParser OK·HTML 태그 균형 EMPTY·SVG `<g>` 11/11·`<svg>` 1/1·8테이블 명세 전부·ER 관계 7행 검증. 운영 Loki 미호출. main 직접 커밋.
- 다음 단계: 매니저 검증 대기.

## 2026-06-29 세션 45 — 신규 도메인 즉시 처리 CLI(`-domain -register` 신규 + `-scan` 미등록 자동등록, D47 확장)

### 한 일
- **요청**: 운영자가 신규 도메인을 스케줄러 안 기다리고 즉시 (1) 등록만, (2) 등록+스캔. `-domain -register <도메인>`(신규)·`-domain -scan <도메인>`(미등록이면 자동등록 후 스캔).
- **공유 등록 헬퍼 `DomainRegistrar`(신규 @Component)**: `registerIfAbsent(rawHost)` = 정규화(`DomainNames.normalize`, null→IllegalArgumentException) → `findById.orElseGet`(신규=enabled=true·createdAt/updatedAt=now·`discoveredAt=null`·save / 기존=그대로, 멱등). ★기존 seam 재사용 검토: `DomainUpserter` 는 `discoveredAt=now`(자동 디스커버리 마킹)라 수동 등록엔 부적합(수동=discoveredAt null 자연 구분), REST `DomainController.create` 는 409·ResponseStatusException 라 CLI 부적합 → 최소 신규 컴포넌트(인터페이스/config 없음).
- **parseCli + usage**: `-register` 서브커맨드 추가(`--adc.cli.register-domain=` translate), subcommands 카운트에 포함(복수 지정=usage 오류 fail-loud). `CliProperties.registerDomain` 필드 가산(record canonical 생성자 변경 → 3 테스트 생성자 인자 갱신).
- **`CliRegisterRunner`(@Profile cli)**: run() blank=no-op(다른 runner 대상), register()→정규화·`existsById` 사전판정으로 "등록 완료(enabled=true)"/"이미 등록됨(no-op)" 메시지 구분·exit(0 성공(이미 존재 포함)/2 도메인 누락/4 DB). 멱등이라 이미 존재=성공.
- **`CliScanRunner` 수정**: `domain` 을 `DomainNames.normalize` 로 일원화(findById·scanOnDemand 공통 — 자동등록 중복키·Loki 쿼리 정합). 미등록=`registrar.registerIfAbsent` 자동등록(enabled=true)+"자동 등록" 출력 후 스캔, 존재·비활성=자동활성 안 함→EXIT_NOT_SCANNABLE(운영자 결정 존중). `registrar` 생성자 주입.

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 전체 **445**(+13) 실패0 errors0 skip2(둘 다 -Dloki.live=운영 Loki 미호출). 신규/변경 suite: `DomainRegistrarTest` 3·`CliRegisterRunnerTest` 5·`CliScanRunnerTest` 6·`MainArgModeTest` 10 전부 green. 등록/파싱 단위 전부 mock·정적(운영 Loki 미호출).
- 문서: doc/31 B1·32 §4(register 예시+scan 자동등록 주석)·33 §7·DECISIONS D47 확장·TASKS subitem.

### 다음 단계
- 커밋 금지(매니저, 브랜치 feat/cli-register-and-scan-autoregister). 매니저 리뷰/머지 + 실배포 검증(`-domain -register`·미등록 `-scan` 자동등록). HTML 매뉴얼 동기는 technical_writer 후속.

## 2026-06-29 세션 44 — 경로 matrix 파라미터(;jsessionid) 정규화(실배포 발견, doc/02 §3)

### 한 일
- **근본**: `LogLineParser` 의 `rawPath = stripQuery(requestUri)` 가 query(`?`)만 제거, matrix 파라미터(`;key=value`, RFC3986)는 잔존. `/st/login;jsessionid=FC4C...`(실배포 eos-st.komeda.co.jp POST)가 rawPath 로 남아 ① SPEC 매칭 미스 ② 세션ID마다 별도 template → 카디널리티 폭증(엔드포인트 부풀림). rawPath 가 matcher.match·PathNormalizer.inferTemplate 공통 입력이라 양쪽 오염.
- **수정(파서 1곳)**: `rawPath = stripMatrixParams(stripQuery(requestUri))`. `stripMatrixParams` = 세그먼트별(`/` 분리) 첫 `;` 이후 제거(leading slash 보존, `;` 없으면 조기반환=무오버헤드). `/a;p=1/b;q=2`→`/a/b`, `/st/login;jsessionid=X`→`/st/login`. matrix `;` 는 endpoint identity 와 무관(세션 노이즈)이라 전부 제거 안전. PathNormalizer/매칭 코드 불변.
- **referer 적용 여부**: `RefererSignalExtractor.refererPath` 는 query/fragment 만 제거·matrix 미제거 — referer matrix 드물고 PAGE_URLS 는 coverage-gated 보조신호라 영향 미미. 헬퍼 노출/중복 회피 위해 미적용, TASKS 선택 후속 메모만(매니저 지시의 '과하면 생략' 채택).
- **테스트**: `LogLineParserTest.stripsMatrixParamsFromPath`(jsessionid·멀티세그먼트 `/a;x=1/b;y=2`→`/a/b`·matrix+query `/p;s=1?q=2`→`/p`·';' 없는 정상 불변·`/` 불변). 기존 파서 테스트 무회귀.

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 전체 432(+1) 실패0 skip2(둘 다 -Dloki.live=운영 Loki 미호출). LogLineParserTest 8 green. 운영 Loki 미호출(파싱 단위).

### 다음 단계
- 커밋 금지(매니저, 브랜치 fix/strip-matrix-params). 기존 DB `;jsessionid` 행은 재스캔 시 `/st/login` 새 적재 + retention(180d) 자연 정리(forward fix) — 매니저 재배포 반영. referer matrix strip 은 선택 후속.

## 2026-06-26 세션 43 — `-domain -ls` stdout 표 → CSV 파일(사용자 Option B, doc/33 §15)

### 한 일
- **변경 배경**: 대량 도메인(11k+)에서 stdout 표는 비실용 → 사용자 확정 Option B(export-domain 동형 파일 다운로드).
- **DomainCsvWriter.domainsToCsv(List<DomainConfig>)** 신규: 헤더 host,enabled,hostnames,discovered_at,last_seen_at. 행=host/enabled(true|false)/hostnames(';' 조인)/discoveredAt(ISO|공란)/lastSeenAt(ISO|공란). 기존 RFC4180 `escape`·`appendRecord`(CRLF)·`names`·`nz` 헬퍼 재사용. null Instant→공란. 정렬은 호출측(Sort host).
- **CliListRunner**: stdout printf 표 제거 → CSV 파일 작성(export 동형). `list()`→`list(long stamp)`, run() 이 `list(System.currentTimeMillis())` 호출 후 System.exit. 본체: findAll(Sort host)→domainsToCsv→`output-dir`/domains-&lt;stamp&gt;.csv(Files.createDirectories+writeString)·stdout 에 파일경로·EXIT_OK. EXIT_DB=4(findAll RuntimeException)·EXIT_IO=4(쓰기 IOException 신규 catch). 빈 목록=헤더만 CSV·exit 0. props.outputDir() 재사용(기본 /exports).
- **동작 변화**: `-domain -ls` 도 output-dir 볼륨 필요(예 `-v /opt/adc-exports:/exports`). main().parseCli 의 `-domain -ls` 감지·`--adc.cli.list-domains=true` 주입 불변.
- **테스트**: `CliListRunnerTest` 갱신(@TempDir → CSV 생성·헤더·hostnames ';' 조인·null 공란·빈 목록 헤더만·DB 오류 EXIT_DB·IOException EXIT_IO[파일을 디렉터리로 쓰기 시도]). `DomainCsvWriterTest.domainsToCsvHeaderRowsHostnamesJoinAndNullBlanks` 가산. MainArgModeTest 불변.

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 전체 431(+2) 실패0 skip2(둘 다 -Dloki.live=운영 Loki 미호출). CliListRunnerTest 4·DomainCsvWriterTest 6 green. 운영 Loki 미호출(DB-only).

### 다음 단계
- 커밋 금지(매니저, 브랜치 feature/domain-ls-csv). HTML 매뉴얼(collection-ops-manual.html §4.1)=technical_writer 후속(미터치). doc/32 §4·33 §15·DECISIONS D47 동기 완료.

## 2026-06-26 세션 42 — DomainController GET/DELETE host 정규화 자기일관(P3 trivial 동봉)

### 한 일
- **비대칭 해소**: create/PUT 는 정규화하나 GET/DELETE `/{host}` 는 raw 키 사용 → POST 'API.Example.COM'→'api.example.com' 저장 후 GET/DELETE /API.Example.COM→raw 키 미스→404. `requireNormalizedHost(host)` private 헬퍼 추출(normalize+null=400), create/PUT/GET/DELETE `{host}` 경로변수에 일괄 적용(create/PUT 의 인라인 normalize+400 도 헬퍼로 DRY). 본문 로직 불변.
- **테스트**: `DomainControllerTest` +2(`getLooksUpByNormalizedPathHost`·`deleteUsesNormalizedPathHost` — 대문자 경로 → 정규화 키 매칭). 기존 5건 유지(총 7).
- **무회귀**: 정규화 도메인 경로는 normalize 동일값이라 불변. 본문/응답 불변.

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 전체 429(+2) 실패0 skip2(둘 다 -Dloki.live=운영 Loki 미호출). 운영 Loki 미호출(단위 mock).

### 다음 단계
- 커밋 금지(매니저, 같은 브랜치 fix/scan-foreign-host-filter). 후속(TASKS): 교차 컨트롤러(Scan/Spec/Classification/CombinedDiscovery) `@PathVariable host` raw 도 동일 비정규화 — pre-existing, 별도 PR(공통 normalize 일괄/인터셉터).

## 2026-06-26 세션 41 — foreign-host 필터 P2 견고화: 등록 정규화 + 양변 normalize(doc/05 §2.2·26 §2)

### 한 일
- **P2 근본(등록 소스 비정규화)**: 세션40 필터가 비대칭 정규화(좌변 스캔 host 미정규화)라 '스캔 host 이미 정규화' 불변식에 의존. `DomainController.create`(raw setHost)·`PUT`(raw path find)가 이를 미강제 → 수동 등록 'Example.com'(대문자/공백)은 비정규화 → 필터 `대문자.equals(소문자)`=false → 정상 라인 전건 오필터(스캔 0). `|=` 쿼리도 raw host case-sensitive 미스.
- **수정 ①(등록 정규화·근본)**: `DomainController.create` 가 `DomainNames.normalize(req.host())` 후 existsById·setHost·save(normalize==null=400, 기존 null/blank 흡수). `PUT /{host}` 는 `find(DomainNames.normalize(host))`(정규화 키 매칭, null=400). → domain_config.host 항상 정규화 → |= 쿼리·필터·identity·영속 모두 정규화 host(auto-discovery 와 동일 규칙, RFC 대소문자 무관 동일성).
- **수정 ②(필터 방어적 좌변 정규화·견고성)**: `DiscoveryJobService.analyze` 필터를 `scanDomain=DomainNames.normalize(host); scanDomain!=null && scanDomain.equals(DomainNames.normalize(r.host()))` 로 자기완결화 → 레거시 비정규화 등록분·robustness 대비(불변식 의존 제거).
- **테스트**: `DomainControllerTest`(대문자/공백 create→정규화 저장·중복 정규화 existsById 매칭·blank/"-"/null→400·PUT 정규화 조회) 신규. `DiscoveryJobServiceTest.analyzeNormalizesScanHostInForeignFilter`(비정규화 스캔host 도 좌변 normalize 로 정상 라인 포함). 기존 foreign-host 가드 유지.
- **무회귀**: auto-discovery 경로·정상(정규화 host) 스캔 불변. create 정규화는 신규 등록만 영향. 정규화 도메인 PUT 은 normalize 동일값이라 불변. 기존 create/post 테스트 부재(회귀 대상 없음).

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 전체 427(+6) 실패0 skip2(둘 다 -Dloki.live=운영 Loki 미호출). DomainControllerTest 5건 green. 운영 Loki 미호출(단위 mock).

### 다음 단계
- 커밋 금지(매니저, 같은 브랜치 fix/scan-foreign-host-filter). 기존 VM 비정규화 등록분 있으면 매니저 재배포 전 정규화 마이그레이션 고려(신규 등록은 정규화 보장).

## 2026-06-26 세션 40 — foreign-host 누수 수정 + signature 원천 발산 정리(doc/05 §2.2·26 §2)

### 한 일
- **조사(근본은 하나)**: `DiscoveredEndpoint` 는 `Acc.toEndpoint` 에서만 생성, signature=method+host+template 를 record (method,host,pathTemplate)와 **같은 변수로 재계산** → signature template 축 발산은 구조적 불가. 발산은 **host 축뿐**. 원인=`LokiQueryBuilder` `|= domain`(substring coarse 라인필터)이 referer/URL/UA 에 도메인 든 다른 Host 라인도 매칭 → `InventoryBuilder` 가 host 필터 없이 파싱 host(`r.host()`)로 Acc/endpoint 생성 → `d.host()≠스캔도메인` endpoint 유입·스캔도메인 하 영속(인벤토리/discovered/recency 오염). `LogLineParser` host 는 raw(미정규화), 스캔 host(domain_config)는 discovery 의 normalizeDomain(trim+lowercase).
- **수정 ①(공유 정규화)**: `util/DomainNames.normalize`(trim+lowercase ROOT·빈/"-"→null) 신규 단일 진실원. `DomainDiscoveryService.normalizeDomain` 이 위임(동작·교차검증 불변, Locale import 제거). discovery 등록과 스캔 필터가 동일 규칙 보장(다르면 정상 도메인 오필터).
- **수정 ②(foreign-host 필터)**: `DiscoveryJobService.analyze` 가 parse→dedupByRequestId 직후 InventoryBuilder/RefererSignal 전에 `requests.filter(r -> host.equals(DomainNames.normalize(r.host())))`. host=스캔도메인(정규화 완료), r.host()=raw→normalize 후 비교(대소문자·trim 정합), null host 자동 제외. matcher/classifier 의 d.host() 사용·LogLineParser 정규화는 불변(필터 비교 시점에만 normalize).
- **효과**: 인벤토리·findings·discovered_endpoint·refererSignal 스캔도메인 전용 → foreign-host 누수 제거. 이후 d.host()==스캔도메인(정규화 동치) → signature host 축 발산 0(raw 대소문자 차이는 식별 소비처 0이라 무해). signature 필드 제거만 별도 cleanup(미진행).

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 테스트 `DomainNamesTest`(trim/lowercase/"-"/빈/null) + `DiscoveryJobServiceTest.analyzeFiltersForeignHostRequests`(host=HOST·대문자HOST 포함·evil 제외, discovered 전부 host=HOST). ★진위: 필터 임시 무력화 시 /leak 유입으로 RED 확인 후 복원. 기존 analyze 테스트는 line() 이 Host=HOST 라 무회귀. 운영 Loki 미호출(단위 mock).

### 다음 단계
- 커밋 금지(매니저, 브랜치 fix/scan-foreign-host-filter). VM 재배포 시 인벤토리 foreign-host 0 확인은 매니저. signature 필드 제거 cleanup 은 선택 후속(소비처 0).

## 2026-06-26 세션 39 — classifier recency 발산 signature 미스 수정(P3 정확도, doc/26 §2)

### 한 일
- **근본**: `priorFirstSeen` 은 analyze 에서 `signatureOf(rec)`=제약 튜플 키(method,스캔host,template)로 구축되는데 `Classifier` 가 `priorFirstSeen.get(d.signature())` 로 조회. `DiscoveredEndpoint.signature` 가 (method,스캔host,최종 template)와 발산(T1 {var} 승격 전 template 등)하면 recency miss → Zombie entrenchment 보너스 0 → severity band 과소. upsert(세션37/38)와 동일 발산이 recency 에 남은 것(무크래시·조용한 정확도).
- **수정(식별 통일)**: 공유 util `model/EndpointIdentity.key(method,host,pathTemplate)` 추출(DB unique 제약·upsert·recency·영속 단일 진실원). `DiscoveryJobService.identityKey`(→signatureOf 도) 가 위임. `Classifier.classifyWithMetrics` 8-arg(+스캔 host) 추가, 7/6-arg 등 짧은 오버로드는 host=null 위임(빈 prior 전제 무해). 2곳 recency lookup 을 `priorFirstSeen.get(EndpointIdentity.key(d.method(), host, d.pathTemplate()))`로(host=스캔 파라미터, d.host() 아님 → foreign-host 도 영속 identity 와 정합). `analyze` 가 host 전달.
- **무회귀**: 정상(비발산) 엔드포인트는 `d.signature()`==`EndpointIdentity.key(d.method,스캔host,template)`라 lookup 결과 동일. 빈 prior 오버로드 host=null 무해.
- **테스트**: `ClassifierTest.priorFirstSeenEntrenchmentRaisesZombieSeverityBand` 8-arg 갱신(prior 키=EndpointIdentity.key, host 전달) + 신규 `recencyMatchesByIdentityKeyDespiteSignatureDivergence`(signature 발산 endpoint+deprecated spec+제약튜플 prior → band MEDIUM). ★진위: lookup 2곳 임시 원복(d.signature()) → 신규 가드 band LOW RED 확인 후 복원(단위, PG 불요).
- **DiscoveredEndpoint.signature**: 식별 소비처 0 — 주석 "식별 키 아님(EndpointIdentity.key 사용)"·ponytail(테스트 발산 재현용만, 제거는 별도 cleanup).

### 결과
- `./gradlew build` BUILD SUCCESSFUL(단위, 운영 Loki 미호출). 발산 회귀가드 RED-without-fix 확인.

### 다음 단계
- 커밋 금지(매니저, 브랜치 fix/recency-identity-key). 후속(범위 밖·TASKS): (a) signature 원천 발산 정리(소비처 0이라 무해), (b) foreign-host 누수(LokiQueryBuilder |= domain substring → 이질 host endpoint 유입; 소스 exact host 필터·InventoryBuilder host 필터로 별도).

## 2026-06-26 세션 38 — discovered_endpoint unique 위반 host 축 정정(reviewer 실 PG probe, doc/26 §2)

### 한 일
- **잔존 원인(host 축 발산, reviewer 확정)**: 직전(template 축) 수정이 키를 `identityKey(d.method(), d.host(), d.pathTemplate())`로 둬 host 만 `d.host()` 사용 → 신규 rec 는 `setHost(host 파라미터)`로 영속되므로 키·영속값·prior 키가 host 축에서 불일치. `LokiQueryBuilder` 의 `|= domain` substring 라인필터가 referer/URL/UA 에 도메인 든 다른 Host 라인도 매칭 → InventoryBuilder 가 파싱 Host(d.host())로 endpoint 생성 → `d.host()≠스캔도메인` 발생. 두 endpoint(GET "/", host D·E)가 키 'GET D /'·'GET E /'로 갈려 둘 다 신규 → 둘 다 `setHost(D)` INSERT (D,GET,/) → 충돌.
- **수정(1글자)**: `upsertDiscovered` 키 host 를 `d.host()`→**스캔 `host` 파라미터**(`identityKey(d.method(), host, d.pathTemplate())`). 이제 키=영속 identity=prior 키 → 기존 행 항상 매칭(UPDATE)·d.host() 발산 무관 병합. method·path_template 축은 rec 가 `d.*` 로 영속되므로 그대로.
- **테스트(실 PG, host 축 발산 신규 2건)**: `upsertMergesDivergentParsedHostsUnderScanHostOnRealPg`(intra-batch: GET "/" 둘, d.host() D/E 갈림, host param=D → 1행·E 미생성·last-writer) + `upsertMatchesExistingRowWhenParsedHostDivergesOnRealPg`(cross-batch: 기존 (D,GET,"/") + d.host()=E → 기존 행 UPDATE). ★진위: 수정 원복(d.host()) 시 실 PG 둘 다 `duplicate key ... discovered_endpoint_host_method_path_template_key` RED 확인 후 복원. 기존 가드(template 발산·intra-batch signature) 유지.

### 결과
- 일반 `./gradlew build` BUILD SUCCESSFUL. ★실 PG `./gradlew test`(podman) — host 축 가드 2건 PASS(skip 아님). 무회귀: 기존 가드 유지, 단일 튜플 경로·cap/prune 불변.

### 다음 단계
- 커밋 금지(매니저, 같은 브랜치 fix/discovered-endpoint-identity-key). VM 재배포 시 스캔 실패율 0 확인은 매니저.
- **후속(범위 밖)**: (P3) classifier recency 가 `priorFirstSeen`(제약튜플 키)에 `d.signature()`(발산)로 lookup → 발산 endpoint recency miss(Zombie severity 과소·무크래시). 진짜 근본=`DiscoveredEndpoint.signature` 를 최종 resolved 값(method,스캔host,최종template)으로 생성하거나 d.signature() 소비처 전부 identityKey 통일 → upsert·recency 일괄 해소. 별도 설계 후속(TASKS).

## 2026-06-26 세션 37 — discovered_endpoint unique 위반 근본 수정(제약튜플 키잉, PR #31 정정, doc/26 §2)

### 한 일
- **근본 원인(실 PG bind 로깅으로 확정)**: PR #31 배포 후에도 재현. examinee-portal.eiken.or.jp 온디맨드 스캔 ~30 UPDATE 후 `duplicate key ... (host,method,path_template)=(examinee-portal.eiken.or.jp,GET,/)`. `loadDiscovered` 가 `prior` 를 `signatureOf(rec)`=**(method+host+path_template)** 키로 적재하는데, `upsertDiscovered` 는 `prior.get(d.signature())`·`prior.put(d.signature())`(PR #31)로 **signature 키** 사용. `DiscoveredEndpoint.signature` 가 최종 path_template 과 발산("/" 등 정규화/방출 불일치)하면 기존 (GET,host,"/") 행을 miss → 신규 INSERT → unique 위반. PR #31 의 prior.put 도 signature 키라 무력(테스트가 signature==template 합성이라 발산 미커버).
- **수정(제약 튜플 키잉, signature 폐기)**: `identityKey(method,host,pathTemplate)` 헬퍼 추가, `signatureOf(rec)` 도 이 헬퍼로 리팩터(동일 키 보장). `upsertDiscovered` 의 lookup/put 을 `identityKey(d.method(),d.host(),d.pathTemplate())`(지역변수 1회 계산)로 → prior 적재 키(=DB unique 제약 튜플)와 정확히 일치 → 기존 행 항상 매칭(UPDATE)·배치 내 동일 튜플 병합 → INSERT 충돌 불가. signature 의존 제거.
- **테스트(실 PG, signature≠튜플 재현)**: `PostgresIntegrationTest.upsertMatchesExistingRowByConstraintTupleNotSignatureOnRealPg` — 기존 (host,GET,"/") 행 저장 + signature 발산(GET host /v1/legacy)·튜플=(GET,host,"/")인 DiscoveredEndpoint upsert → 1건 UPDATE(hits 5→99) 단언. `priorFor` 헬퍼가 loadDiscovered 처럼 제약 튜플 키로 prior 구성. ★진위: 수정 임시 원복(lookup 을 d.signature()로) 시 실 PG `duplicate key` red 확인 후 복원. PR #31 가드(upsertDiscoveredMergesIntraBatchDuplicateSignatureOnRealPg)도 유지.
- **별도 플래그(범위 밖)**: signature 발산은 classifier recency(priorFirstSeen, signatureOf 키)도 같은 엔드포인트를 놓칠 수 있음(무크래시·조용한 정확도). 근본=signature 를 최종 template 과 일치 — TASKS 후속으로 기록.

### 결과
- 일반 `./gradlew build` BUILD SUCCESSFUL. ★실 PG `./gradlew test --tests "*PostgresIntegrationTest"`(podman) — 신규 발산 가드 PASS(skip 아님). 무회귀: PR #31 가드 유지, 단일 튜플 경로·cap/prune 불변.

### 다음 단계
- 커밋 금지(매니저, 브랜치 fix/discovered-endpoint-identity-key, PR #31 정정 대체). VM 재배포 시 스캔 실패율 0 확인은 매니저. signature 발산 근본 정리는 TASKS 후속.

## 2026-06-26 세션 36 — discovered_endpoint intra-batch 중복 버그 수정 (실배포 발견, doc/26 §2)

### 한 일
- **근본 원인(확정)**: `DiscoveryJobService.upsertDiscovered` 가 `prior` 맵(DB 기존 record, signatureOf 키)을 보고 신규/기존 판정하나, **신규 rec 를 prior 에 다시 넣지 않음** → 한 스캔 `discovered` 리스트에 동일 signature 2개(T1 통계 {var} 승격으로 두 경로가 같은 template 로 수렴, doc/13)면 둘 다 prior-miss → 새 record 2개 → 2번째 `discoveredRepo.save` 가 unique(host,method,path_template) 위반 → 도메인 격리 catch 로 그 도메인 스캔 전체 실패(결과 미저장). 실측 VM 15분 3실패/12성공(~20%, examinee-portal.eiken.or.jp·sp.skygate.co.jp·www.eyecity.jp). 선존 버그(PR2/PR3 무관).
- **수정(최소·1줄)**: `if (rec == null) { ... count++; }` 끝에 `prior.put(d.signature(), rec)` 추가 → 같은 배치의 후속 동일 signature 가 in-memory rec(1회 save 돼 id 보유) 찾아 UPDATE → unique 위반 없음. last-writer-wins 스냅샷 의미 일관(추가 합산 불필요). 키는 lookup 과 동일한 `d.signature()`.
- **테스트(실 PG 회귀가드)**: `upsertDiscovered` private→public(PostgresIntegrationTest 가 별 패키지라 package-private 불가, 실 PG 컨테이너 1개 재사용 위해 공개·javadoc 명시). `PostgresIntegrationTest.upsertDiscoveredMergesIntraBatchDuplicateSignatureOnRealPg`: 동일 signature 2개(hits 10/20) 리스트 → 1건·hits=20(last-writer) 단언. ★진위 검증=수정 1줄 임시 제거 후 실 PG 실행 → `duplicate key value violates unique constraint "discovered_endpoint_host_method_path_template_key"` → DataIntegrityViolationException red 확인 후 복원.

### 결과
- 일반 `./gradlew build` BUILD SUCCESSFUL. ★실 PG `./gradlew test --tests "*PostgresIntegrationTest"`(podman 소켓) **18건 실행(skip 0, 실패 0)** — 신규 가드 실제 PASS. mock 은 unique 제약 미강제라 실 PG 필수. 운영 Loki 미호출(LokiClient @MockBean).
- 무회귀: 단일 signature 경로 불변(prior.put 은 신규 생성 시에만, 기존 갱신 동일). cap/prune 로직 불변.

### 다음 단계
- 커밋 금지(매니저, 브랜치 fix/discovered-endpoint-intrabatch-dup). VM 재배포 시 스캔 실패율 0 확인은 매니저. doc/26 §2 intra-batch dedup 노트 보강.

## 2026-06-26 세션 35 — CLI 문법 `-domain` 서브커맨드 통일 (doc/33 §15, D47 갱신)

### 한 일
- **신문법(전부 단일대시 `-domain` 서브커맨드, 사용자 확정)**: `-domain -ls`(목록) / `-domain -export <도메인>`(CSV) / `-domain -scan <도메인> [-window <ISO8601>] [-edge <hostname>]`(온디맨드 스캔). `-window`/`-edge` 도 단일대시 통일.
- **`main().parseCli`(순수·static·System.exit 없음 → 단위 테스트 가능)**: 신문법 감지 → 내부 `--adc.cli.list-domains=true`/`export-domain=`/`scan-domain=`(+`window=`/`edge=`)로 translate → `CliArgs`(usageError / inject[] / 서버) 반환. main 은 inject!=null→web NONE·cli 프로파일 부팅, usageError→짧은 usage+exit(2), else 서버. `has`/`valueAfter`(positional 값=flag 다음 토큰, `-`로 시작이면 누락) 헬퍼.
- **★기존 `--adc.cli.export-domain=`/`scan-domain=` 사용자 트리거 제거** — 직접 입력 시 더 이상 CLI 모드 미진입(서버 모드). 기존 `isListDomains`/`isCliMode`/`CLI_TRIGGERS` 제거. CliProperties·CliExportRunner·CliScanRunner·CliListRunner 런너/바인딩 불변(외부 UX↔내부 프로퍼티 구동 분리=최소 변경).
- **테스트 `MainArgModeTest` 갱신**: 신문법 3종 주입 인자 배열 단언(-ls/-export/-scan +window/edge 조합)·기존 `--adc.cli.X=` 단독=서버 모드(제거 회귀)·usage 에러 경로(-domain 단독·도메인 누락·도메인 자리 플래그)·서버 모드(빈 args·--server.port).
- **md 문서 동기**: doc/31(트리거·컨테이너 run/exec 예시)·doc/32 §4(run (b)(c)·exec 대안·"전 명령 통일" 주석)·doc/33 §7(스캔 명령)·§15.3(전면 통일 채택으로 갱신)·§15.4(parseCli)·DECISIONS D47(목록→전면통일 갱신·이력). HTML 매뉴얼은 technical_writer 후속(미터치).
- **리뷰 반영(P3 2건)**: P3-1(코드)=복수 서브커맨드(`-domain -export foo -scan bar` 등) 조용히 하나 선택하던 것을 ambiguous usageError 로 **fail-loud** 거부(우선순위 분기 전 카운트 체크) + `MainArgModeTest` 케이스 추가(단일 서브커맨드 정상 회귀 포함). P3-2(doc)=doc/33 §15.1(구 프로퍼티 대조 서술→통일 후 무효 명시)·§17 체크리스트(PR1.1·목록 CLI 완료 [x] 동기·표현을 parseCli 신문법으로)·§18(통일 문법=완료) 갱신.

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 운영 Loki 미호출(단위 mock). 신문법 3종 동작·기존 문법 제거(서버 모드)·복수 서브커맨드 모호 거부 단언 green.

### 다음 단계
- 커밋 금지(매니저, 브랜치 feature/cli-domain-subcommand). 출하 전·테스트 단계라 기존 트리거 제거에 외부 파괴 없음. HTML 매뉴얼 신문법 반영=technical_writer.

## 2026-06-26 세션 34 — 스캔 정책 PR2/PR3: C 티어링 + D off-peak + F dormant (doc/33 §4–6, D48)

### 한 일 — 통합 due 모델 (C·F·override 합성, 1 PR)
- **신규 필드**: `DomainConfig.nextScanDueAt`(Instant, ddl-auto nullable) + `@Table(indexes=@Index(columnList="next_scan_due_at"))`. null=즉시 due. `lastScanAttemptAt` 은 관측·touch 용도로 유지.
- **`ScanTier.effectiveInterval`(순수함수)**: `tiering-enabled=false`→ZERO(=즉시 due=LRS 동치, 롤백 스위치). `intervalOverride`(String) Duration.parse 성공 시 최우선(기존 P3 TODO 배선, 파싱 실패=warn+티어 폴백). lastSeenAt null→default-interval. age=now−lastSeenAt → ≤active-threshold(PT24H)→active(PT30M)[C] / >dormant-after(P14D)→dormant(P1D)[F·최장·삭제 아님] / else inactive(PT6H)[C].
- **`OffPeakWindow.isOffPeak`(순수함수)**: "HH:mm-HH:mm" 파싱, start≤end=`start≤t<end` / start>end=자정 wrap(`t≥start||t<end`). null/blank/파싱실패=peak(false·무회귀). `zone(String)` 빈값=시스템 기본.
- **`DomainConfigRepository`**: `findDueForScan(now, Pageable)`(`WHERE enabled AND (next_scan_due_at IS NULL OR <= now)`) + `touchScanSchedule(host, now, nextDue)`(lastScanAttemptAt+nextScanDueAt 2컬럼 @Modifying UPDATE). 구 `touchLastScanAttempt`·`findByEnabledIsTrue(Pageable)` 는 이 변경으로 orphan → 삭제(ponytail). 무인자 `findByEnabledIsTrue()`는 선존 dead code라 보존.
- **`ScanSelector`**(Clock 주입): due 쿼리 + `nextScanDueAt asc nullsFirst` + off-peak 시 K=`off-peak-domains-per-tick` 스위치(due 술어 불변). **`DiscoveryScheduler.scanTick`**(ApiDiscoverProperties 주입): 틱당 now 1회 + off-peak 판정→maxWindow 스위치(`off-peak-max-window`), 도메인마다 `touchScanSchedule(host, now, now+effectiveInterval)` + `runScan(host, maxWindow)`. **`DiscoveryJobService`**: `runScan(host, maxWindow)` 오버로드(+`nextWindow(host, maxWindow)`), 기존 `runScan(host)`=max-window 위임(ScanController·온디맨드 무영향).
- **설정**: application.yml scan 블록 +tiering-enabled true·active/default/inactive-interval·active-threshold·off-peak-domains-per-tick/max-window/zone·dormant-after/interval. `schedule.off-peak-window`("01:00-06:00") 재사용. off-peak 쿼리캡 상향은 범위 밖(ponytail defer — LokiBudget 시간당 stateful). `ApiDiscoverProperties.Scan` 레코드 +10 필드(끝).

### 한 일 — 리뷰 반영 (P1 1건 + P3 2건, 전건)
- **P1(머지 차단) findDueForScan null 정렬 결정화**: Pageable `Sort` 는 Hibernate 가 NULLS FIRST 를 SQL 에 미방출 → PG 기본 ASC=NULLS LAST 로 null(신규 미스캔) 도메인이 dated-due 11549개 뒤로 밀려 영구 기아(tiering 기본 true 직격). 수정: `@Query` 에 `order by d.nextScanDueAt asc nulls first` 명시(Hibernate JPQL→PG/H2 결정적), `ScanSelector` Pageable=limit 전용(Sort 제거·`Sort` import 삭제). ★실 PG 회귀가드 `PostgresIntegrationTest.findDueForScanOrdersNullsFirstOnRealPg`(dated 3[now-1h/-2h/-3h] + null 1, K=2 → null 맨 앞·dated3 둘째, NULLS LAST 회귀 시 빨강) — podman 실행 PASS(H2/H2-PG모드 무력이라 실 PG 단언 필수).
- **P3-1 OffPeakWindow.zone invalid 가드**: `ZoneId.of(잘못된 문자열)` DateTimeException 이 매 틱 전파→스캔 중단(isOffPeak/ScanTier 폴백과 비일관) → try/catch 시스템기본 폴백+warn. 테스트 `invalidZoneFallsBackToSystemDefault` 추가.
- **P3-2 ScanTier 밴드 전제 문서화**: active 체크가 dormant 보다 먼저라 active-threshold>dormant-after 역전 misconfig 시 중간 age 가 active(과스캔, 안전쪽·크래시 없음) → 클래스 javadoc 전제 한 줄(코드 무변경, 검증 프레임워크는 과함).

### 결과
- `./gradlew build` BUILD SUCCESSFUL. 일반 빌드 + ★실 PG(podman) `./gradlew test` 모두 통과, 총 **408(+24) 실패 0 skip 2**(둘 다 -Dloki.live 게이트=운영 Loki 미호출). PostgresIntegrationTest **17건 실행(skip 0)** — 신규 null-first 가드 PASS. 신규 단위: ScanTierTest 9·OffPeakWindowTest 9(경계·자정wrap·zone·파싱실패·invalid zone 폴백)·ScanSelectorTest +2·DiscoverySchedulerTest +3. 전 Scan ctor 사이트(9) +10 인자. 단위 전부 mock — 운영 Loki 미호출.
- **무회귀**: tiering-enabled=false→effectiveInterval ZERO→due=now→LRS 동치(검증), off-peak-window blank=항상 peak, 코어(collectBounded·analyze·budget·watermark) 불변.

### 머지
- 매니저 검증(P1 수정 실 PG 직접 재현 — `findDueForScanOrdersNullsFirstOnRealPg` PASS) → 재리뷰(잔존 0·머지 승인) → **PR #29 squash 머지(6df371f)**. TASKS 부모 `[x]`(A–F 전체 완료·PR #26·#27·#29)·off-peak/intervalOverride 항목 동기, DECISIONS D48·doc/33 §12 체크리스트는 dev 가 머지 전 동기.

### 다음 단계
- 신규 컬럼 `domain_config.next_scan_due_at`(nullable·index)=ddl-auto 가산(기존 행 null→즉시 due 1회 후 정상화). doc/18 next_scan_due_at sync=technical_writer 후속. 실배포 티어 분산·off-peak 백필 가속은 PR2/PR3 VM 재배포 검증(7d 예산 고려·off-peak 선호).

## 2026-06-26 세션 33 — PR1.1 스캔 per-domain 폭주 수정 + 도메인 목록 CLI (doc/33 §14·§15, D46·D47)

### 한 일 — 커밋1: PR1.1 스캔 폭주 수정 (D46)
- **③ 스레드 격리**: `application.yml` `spring.task.scheduling.pool.size: 2` → scanTick·discover 별 스레드(단일 풀 블로킹 기아 해소).
- **② 윈도우 축소**: `scan.max-window` PT6H→PT30M + `scan.slice-window` PT10M 신규(미지정=loki.chunk-window).
- **① per-scan 하드캡 + 슬라이스 부분 watermark 전진(핵심)**: `runScan` 이 `collectBounded`(슬라이스 외부·hostname 내부 순회) 호출 — 슬라이스(slice-window)별로 **모든 hostname 완료 후에만** consumedUpTo 를 그 슬라이스 끝으로 전진(멀티-hostname gap-free). 슬라이스 경계에서 `max-queries-per-scan`(50) 하드캡 + `budget.hasBudget()` 체크 → 초과 시 마지막 완료 슬라이스까지만 analyze + watermark 전진하고 종료(부분 전진=resume 다음 틱, busy 도메인 기아 방지). `collect`(온디맨드)는 유지. `ApiDiscoverProperties.Scan` +sliceWindow·maxQueriesPerScan(끝), `DiscoveryJobService` +LokiBudget 주입.
- 무회귀: max-queries-per-scan=0 && 예산 무제한 → 전 슬라이스 수집 = 기존 collect 동치(consumedUpTo=window.to()). 슬라이스 순회는 윈도우 타일링이라 라인 동일(dedup). watermark=데이터 ts 결정적. pool=2 동작 불변.

### 한 일 — 커밋2: 도메인 목록 CLI `-domain -ls` (D47)
- `main().isListDomains`: 단일대시 `-domain` AND `-ls` 동시 감지(Spring non-option arg) → CLI 모드 + `--adc.cli.list-domains=true` 주입(외부 단일대시 UX·내부 프로퍼티 구동 분리). `CliProperties.listDomains` 가산.
- `CliListRunner`(@Profile cli): `domainRepo.findAll(Sort host)` → stdout(host·enabled·#hostnames·discovered_at·last_seen_at), 빈목록 exit0·DB오류 비0. Loki 무관(read-only). run() blank=no-op(export/scan 명령 공존). arg 혼재 허용(기존 `--adc.cli.X=` 불변).

### 결과
- `./gradlew build` BUILD SUCCESSFUL, 총 **382(+8) 실패 0 skip 2**(둘 다 -Dloki.live 게이트=운영 Loki 미호출). 신규: ScanSliceBoundedTest 3(캡/예산 부분전진·gap-free·무제한 현행)·CliListRunnerTest 3·MainArgModeTest 2. 단위 전부 LokiClient/repo mock — 운영 Loki 미호출.

### 다음 단계
- 커밋 금지(매니저, 분리 2커밋 scan-fix/list-cli 또는 2 PR). 실 Loki scanTick 다도메인 분산·discovered_endpoint 점증은 매니저 재배포 검증. doc/18 영향 없음(신규 컬럼 없음, slice/cap/pool 은 설정).

## 2026-06-26 세션 32 — PR1: 스캔 부하정책 B+A+E + 온디맨드 스캔 CLI (doc/33 D45)

### 한 일 (PR1 필수만 — C/D=PR2, F=PR3 미착수)
- **A(윈도우 상한)**: `windowFor` 5-arg(+maxWindow) — (end−start)>maxWindow 시 end=start+maxWindow 절단(백필 슬라이스, 미스캔 7일 일괄 pull 차단). `nextWindow` 가 `scan.max-window` 전달. 0/null=무제한(현행 무회귀). runScan line105 TODO 주석 해소.
- **B(틱 예산+라운드로빈)**: `DomainConfig.lastScanAttemptAt`(ddl-auto) + `findByEnabledIsTrue(Pageable)`(Sort asc NULLS FIRST=미스캔 우선) + `touchLastScanAttempt`(@Modifying 단일컬럼 UPDATE=lost-update 무관) + `ScanSelector`(LRS K, PR2 확장점) + `DiscoveryScheduler.scanTick()`(@Scheduled scan.tick-interval, 전수순회 대체) — K 슬라이스, attempt 마다 커서 전진(skip·실패 포함, runScan 전 별도 tx), 예산 소진 break 이월, 도메인 격리.
- **E(전역 레이트 가드+계측)**: `LokiBudget`(시간당 쿼리/바이트 하드캡, 초과=hasBudget false→틱 이월, 0=무제한, Clock 주입 롤오버) + `LokiClient` Micrometer(loki.queries·response.bytes·errors{status}) + 적응형 throttle(429/5xx level+1·성공 −1, ≤16×min-interval, throttle-on-error 게이트). LokiClient ctor +LokiBudget, requestWithRetry 가 응답마다 budget.record.
- **온디맨드 CLI(§7)**: `CliScanRunner`(@Profile cli, `--adc.cli.scan-domain`[/`--window`/`--edge`], scan()→exit code/run()→System.exit) + `scanOnDemand`(edge→runOnDemand/미지정→collect+analyze, ★watermark 미전진=임시 스냅샷) + `onDemandWindow`(상한=max-window). main() scan-domain 트리거 추가, CliExportRunner.run() blank=no-op(두 CLI 명령 공존). exit 0/2(미지정)/3(미존재·미enabled)/4(Loki).
- **설정**: `ApiDiscoverProperties.Scan`(tick-interval PT5M·domains-per-tick 100·max-window PT6H·max-queries-per-hour 3000·max-bytes-per-hour 0·throttle-on-error true) + application.yml. `Clock` @Bean(앱 클래스). 기존 설정 불변(schedule.default-interval 은 defaultWindow 잔여 용도 유지).

### 결과
- `./gradlew build` BUILD SUCCESSFUL, 총 **374(+12) 실패 0 skip 2**(둘 다 -Dloki.live 게이트=운영 Loki 미호출). 신규: LokiBudgetTest 4·ScanSelectorTest 1(@DataJpaTest nulls-first)·DiscoverySchedulerTest 2(커서 전진 skip/실패·예산 break)·CliScanRunnerTest 4·windowFor 캡 1. 단위 전부 LokiClient/응답 mock — 운영 Loki 미호출. doc/33 §0.1 운영자 요약의 PR1 노브(tick/domains-per-tick/max-window/max-queries-per-hour/throttle-on-error) = 구현 기본값 일치.

### 다음 단계
- 커밋 금지(매니저 PR1). 실 Loki 부하·온디맨드 실스캔 검증은 매니저 실배포(-Dloki.live·테스트서버). PR2=C(티어링·intervalOverride 배선)+D(off-peak), PR3=F(dormant). doc/18 영향: `domain_config.last_scan_attempt_at` 컬럼 sync=technical_writer 후속.

## 2026-06-25 세션 31 — 긴급 성능 수정: 디스커버리 LogQL 서버 coalesce → 클라이언트 coalesce (doc/30 §1, D42)

### 한 일 (DomainDiscoveryService + 테스트 + doc 만 — 매니저 인프라 수정분 wait-for-db/hostNetwork/Dockerfile/adc.yaml 미터치)
- **근거(실 Loki 측정)**: `count_over_time({job}|pattern [5m])`=2.2s 인데 `| label_format domain="{{coalesce}}"` 추가 시 20.2s(10배) → Go 템플릿 라인별 평가 과중 → 1h 부트스트랩·12m 롤링 모두 query-timeout(30s) 초과 → 디스커버리 실패·domain_config=0. (Loki 도달·hostNetwork·wait-for-db 는 정상.)
- `buildLogQL`: `sum by (domain, hostname)(... | label_format domain=coalesce | domain!="" | domain!="-")` → **`sum by (host, real_host, hostname)(count_over_time({job} | pattern [W]))`** (서버 label_format·domain 필터 제거, pattern 불변). `COALESCE_TEMPLATE` 상수 제거.
- discover 루프: `s.labels().get("domain")` → **클라이언트 coalesce** `firstNonEmpty(normalizeDomain(host), normalizeDomain(real_host))`(LogLineParser line83 동일 의미). 이후 FQDN 검증·rejected·hostnamesByDomain 합산·countByDomain 그대로. 같은 도메인이 여러 (host,real_host)·hostname 조합으로 와도 coalesce 후 domain 키 합산(합집합) 유지.
- 테스트: mock 벡터 라벨 {domain,hostname}→{host,real_host,hostname}, `sample(host,hostname,count)`(real_host="-" 편의)+`labeled(host,realHost,hostname,count)`. 신규 coalesce 테스트(host 빈/"-"→real_host)·동일도메인 합산 테스트 추가, LogQL 테스트는 label_format/domain 미포함·`sum by (host, real_host, hostname)` 로 갱신(성능 회귀 가드). 필드포지션 교차검증은 pattern 불변이라 유지. LiveIntegrationTest 도 새 쿼리·라벨로 갱신(미실행).

### 결과
- `./gradlew build` BUILD SUCCESSFUL, 총 **359(+2 신규 coalesce/합산 테스트) 실패 0 skip 2**(둘 다 -Dloki.live 게이트=운영 Loki 미호출). DomainDiscoveryServiceTest 10건 green. 운영 Loki 미호출(단위 mock, live 게이트 미실행).

### 다음 단계
- 커밋 금지(매니저). 실 Loki 디스커버리 성공·domain_config 적재는 매니저 실배포(feature/container-wait-for-db)로 검증. doc/30 §1·DECISIONS D42 coalesce 위치 정정 반영.

## 2026-06-25 세션 30 — PR2: CLI CSV 내보내기(B) + Docker/podman 배포(C) (doc/31 D43)

### 한 일 — B (CLI)
- `main()`: `--adc.cli.export-domain=` 인자 감지 시 `SpringApplicationBuilder.web(NONE).profiles("cli")` 분기(무인자=서버). `@EnableScheduling` 을 메인에서 떼어 `SchedulingConfig(@Configuration @EnableScheduling @Profile("!cli"))` 로 분리 → CLI 모드는 스캔·디스커버리 스케줄러·Loki 미기동, 서버 모드 동일 활성(무회귀).
- `CliExportRunner`(@Profile("cli"), CommandLineRunner): `export(stamp)`→exit code(System.exit 미경유=테스트 가능)/`run()`→`System.exit(SpringApplication.exit(...))`. forHost→CSV→파일. exit 0/2(도메인 미지정)/3(미존재·검출0)/4(IO).
- `DomainCsvWriter`(순수): 15컬럼(host·method·path_template·status·source·confidence·severity·estimated·spec_ref·preflight_ambiguous·low_confidence·param_query·param_path·first_seen·last_seen). source 파생(Shadow/WebPage→detected·Unused→spec·Active/Zombie→both), sealed Finding 패턴매칭, RFC4180 이스케이프, first/last_seen=discovered_endpoint (method,host,template) join(spec-only 공란), score 범위밖(헤더 미포함). `CliProperties`(adc.cli.export-domain/output-dir 기본 /exports).
- B 테스트 10건: DomainCsvWriter(헤더·5 status·source·이스케이프·join 공란·param 결합) + CliExportRunner(blank/empty→비0 exit·success→파일+0) + SchedulingProfile(구조: @EnableScheduling 이동·@Profile("!cli")).

### 한 일 — C (Docker/podman)
- `Dockerfile`(멀티스테이지: temurin:21-jdk `./gradlew bootJar -x test` → temurin:21-jre, `*-SNAPSHOT.jar` glob=bootJar만, ENTRYPOINT java -jar) + `.dockerignore`(build/.git/.gradle/*.csv).
- `application-container.yml`(container 프로파일: PG datasource localhost:5432/adc·env override·ddl-auto update·Loki LAN). 기존 application.yml(H2) 불변.
- `adc.yaml`(podman play kube: 2컨테이너 1 pod=app+postgres:16-alpine, pod netns 공유→app localhost:5432, PGDATA host /opt/adc→/var/lib/postgresql/data(pgdata 서브디렉터리), exports 분리). `doc/32-container-deploy-runbook.md`(빌드·기동·CLI·운영 Loki off-peak 주의·미수행 배포 체크리스트).

### 결과
- `./gradlew build` BUILD SUCCESSFUL, 총 **357(347+10) 실패 0 skip 2**(둘 다 -Dloki.live 게이트=운영 Loki 미호출). PostgresIntegrationTest podman 14건 green. **`podman build` 성공**(localhost/apidiscover:test 401MB, bootJar→JRE, glob 정확). ★컨테이너 미기동(운영 Loki 미호출 절대 준수) — play kube 기동·LAN Loki 도달·실 coalesce·CLI 실도메인 검증은 doc/32 §6 으로 배포 시 매니저/사용자 수행.

### 다음 단계
- 커밋 금지(매니저 PR2). doc/18 영향 없음(스키마 불변). 머지 시 B·C 부모 Done.

## 2026-06-25 세션 29 — 도메인 자동 디스커버리 Phase 1 (A, doc/30 D42)

### 한 일 (A만 — B CLI·C Docker 는 PR2 후속, 미착수)
- `LokiClient`: instant 벡터 쿼리 `queryInstant(logql, time)`(`/loki/api/v1/query`) 신설 + `MetricSample(labels,value)` record. `requestWithRetry` 를 **URL 인자형으로 추출** → query_range·instant 가 throttle/concurrency(max 2)/백오프/timeout 공유. **queryRange 동작 불변**(URL 빌드를 fetchChunk 로 이동).
- `ApiDiscoverProperties.Discovery`(enabled/interval/window/bootstrap-window/initial-delay/max-domains-per-run/host-pattern) + application.yml 기본값(enabled true·10m·12m·1h·2m·200·FQDN 정규식).
- `DomainDiscoveryService`: LogQL(`sum by(domain,hostname) count_over_time({job} | pattern <15 skip+host+real_host> | label_format domain=coalesce(host,real_host) | domain!=""!="-" [W])`) 빌드→instant→(domain→hostnames TreeSet)·도메인별 카운트 합산→FQDN 정규식 거름→카운트 desc max-domains 상한→**무삭제 업서트**(신규 INSERT/기존 hostnames 합집합+lastSeenAt 만, 사용자설정 basePathStrip·specMergeStrategy·enabled·intervalOverride 불변). coalesce 템플릿=LogLineParser line83 와 동일.
- `DomainConfig.discoveredAt`/`lastSeenAt` 가산(ddl-auto) + 접근자. `DomainDiscoveryScheduler`(@Scheduled fixedDelay+initialDelay stagger, enabled 토글, 예외 격리) — 기존 per-domain 스캔과 분리.
- 필드포지션 단일근거: `LogLineParser.DELIM/F_HOST=15/F_REAL_HOST=16` 를 public 으로 widen → LogQL pattern 을 이 상수로 빌드(드리프트 구조적 차단) + 교차검증 테스트.

### 결과
- `./gradlew build` **BUILD SUCCESSFUL, 총 346(332+14) 실패 0 skip 2**. skip 2 = **둘 다 `-Dloki.live` 게이트**(기존 LokiLive + 신규 `DomainDiscoveryLiveIntegrationTest` coalesce 확인) → **기본 빌드는 운영 Loki 미호출**. PostgresIntegrationTest podman 13건 green(DomainConfig 신규 컬럼 ddl-auto 반영). A 신규 단위테스트 13건 실행 green(벡터 파싱·FQDN 거름·상한·업서트 머지·무삭제·설정보존·부트스트랩 vs 롤링·LogQL coalesce·필드포지션 교차검증).
- 운영 Loki 보호 준수: 서비스 테스트는 LokiClient mock, 실 Loki 확인은 게이트 분리(미실행 — 매니저가 배포 C 시점 조율).

### 다음 단계
- 커밋 금지(매니저 리뷰 후 PR1). doc/18 `domain_config.discovered_at`/`last_seen_at` sync=technical_writer 후속. A 머지 후 매니저가 PR2(B CLI·C Docker, doc/31 D43) 지시 예정.

## 2026-06-25 세션 28 — 엔티티 캡슐화 (P2 마무리, doc/29 D41)

### 한 일
- 7 `@Entity`(약 63 public 필드)를 private + getter/setter 로 캡슐화. **블래스트 반경 오름차순 5스테이지**, 각 스테이지 끝 `./gradlew build` green 확인: ① Watermark(2) ② ClassificationConfig(6)·DomainClassificationConfig(6) ③ DomainConfig(8) ④ SpecRecord(11)·ScanResult(14) ⑤ DiscoveredEndpointRecord(16).
- **JPA 불변 핵심(§2)**: 애너테이션을 **필드에 그대로 유지**(getter 이동 금지) → field access 유지 → 매핑/DDL/ddl-auto/컬럼타입·`@ElementCollection`·`@Lob byte[]`·`columnDefinition="text"` 9필드·`@GeneratedValue`·`@Enumerated`·필드 초기화자 전부 불변. boolean=`isX()`(isEnabled/isActive/isHadQuery/isNonBrowserUa). 자동생성 id 2개(SpecRecord·DiscoveredEndpointRecord) **setter 미노출**(getter만). equals/hashCode 무신설. Lombok 미도입(신규 의존 0).
- call-site 치환: main·test 다수 파일. 컴파일러 에러(파일·라인·필드·엔티티)를 파싱해 해당 위치만 getter/setter 로 바꾸는 보조 스크립트로 안전 치환(메서드콜·타 엔티티 동명필드 오변환은 컴파일러가 검출). 쓰기=setX/읽기=getX 구분, 쓰기+읽기 혼재 라인도 정확 처리.

### 결과
- 최종 `./gradlew build` **BUILD SUCCESSFUL, 총 332 실패 0 skip 1**(=LokiLive 불변), `PostgresIntegrationTest` podman **13건 실행·통과 skip 0**(=PG DDL 생성 동일 = 매핑 불변 증거). 엔티티 public 필드 잔존 0, `setId` 0건 확인.
- **테스트 기대값/단언 변경 0**(대입 구문 형태만 getter/setter 로). 단 `SpecStoreTest` stateful mock helper 1곳은 생성 id setter 제거(D41 §4)에 따라 가짜 id 부여 라인만 제거 — identity 기반 add-once 유지, id 값은 어디서도 미단언.

### 다음 단계
- 커밋 금지(매니저 리뷰 후 일괄 커밋·PR·머지). build green 까지. 머지 시 부모 항목 Done. doc/18 무영향(스키마 불변). P2 품질/테스트 버킷의 마지막 항목.

## 2026-06-25 세션 27 — Testcontainers(PostgreSQL) 통합 테스트 + @Lob String→text 실결함 수정 (doc/28, D40, D37)

### 한 일
- `build.gradle.kts`: Testcontainers 3종(spring-boot-testcontainers/junit-jupiter/postgresql, 버전 미명시=Spring Boot BOM) + `tasks.withType<Test>` 에 rootless podman 소켓 가드 주입(`DOCKER_HOST`=`unix://$XDG_RUNTIME_DIR/podman/podman.sock`, `TESTCONTAINERS_RYUK_DISABLED`; DOCKER_HOST 기설정·소켓 부재 시 미개입).
- `PostgresIntegrationTest`(`@SpringBootTest @AutoConfigureMockMvc @Testcontainers(disabledWithoutDocker=true)`, `@Container @ServiceConnection postgres:16-alpine`, `ddl-auto=create-drop`, `@MockBean LokiClient`, `@BeforeEach` 상태 리셋) 신규 13건: ① @Lob String 9컬럼 `information_schema.data_type='text'` 단언(파라미터화) ② 대용량(~60KB) round-trip ③ raw_doc 실타입 기록 ④ `GET /discovery` 실 PG e2e ⑤ `GET /result` 조건부 GET 304.
- **실결함 발견·수정(D37 원칙)**: @Lob String 9컬럼 전부 PG `oid`(large object) 매핑 → 비트랜잭션 read(`/discovery`)에서 `Unable to access lob stream` 발생. 테스트 느슨화 대신 5엔티티(ClassificationConfig·DomainClassificationConfig·ScanResult·SpecRecord·DiscoveredEndpointRecord) 9 String 필드 `@Lob`→`@Column(columnDefinition="text")` 수정 → PG `text`·정상 setString·H2 호환. `raw_doc`(byte[])은 범위 밖 `@Lob` 유지(실측 oid).

### 결과
- BUILD SUCCESSFUL. PG 통합테스트 **13건 실행·통과(skip 0, 컨테이너 실기동 로그 확인)**, 총 **332건 실패 0 skip 1**(=LokiLive, 기존 319 무영향). bogus `DOCKER_HOST` 로 클래스 **auto-skip**(5메서드 skipped, build green 3s) = 무회귀 게이팅 검증.
- 수정 방식 근거(D40 갱신): `@JdbcTypeCode(LONGVARCHAR)`=varchar(32600) 유한·`LONG32VARCHAR`=드라이버 `Unknown Types` 부팅실패 → 둘 다 부적합, `columnDefinition="text"` 만 충족.

### 다음 단계
- 커밋 금지(매니저 git 담당), build green 까지. L52/L53 묶음 단일 PR. 머지 시 부모 항목 Done. doc/18 스키마 text 반영=technical_writer 후속.

## 2026-06-25 세션 26 — catch-all {var+} dead code 제거 (D39, D37 F2, doc/04 §1.1)

### 한 일
- `EndpointMatcher.compile` 의 `isCatchAll(seg)→rx.append(".+")` 분기 + `isCatchAll` 헬퍼 삭제. `{var+}` 류 토큰은 이제 `isVariable`→`[^/]+`(단일 세그먼트 변수)로 일관 처리(catch-all 특별취급 제거).
- doc/04 §1.1 은 이미 catch-all '현재 미지원(D37 F2/D39)' 명시 + 단일 세그먼트=`{var}`([^/]+) 동치 기술 — 코드 제거 후 상태와 일치(추가 편집 불요).

### 결과
- build BUILD SUCCESSFUL, **tests=319 failures=0 skipped=1**(불변). 무회귀 근거: 3종 파서가 `{var+}` 미생성→`isCatchAll` 항상 false(분기 dead) / segCount 버킷팅이 다중 세그먼트 `.+` 구조적 차단 / 도달 가능(동일 segCount) 케이스에선 `.+`≡`[^/]+`(단일 세그먼트엔 슬래시 없음) → 제거해도 매칭 결과 불변. 타 사용처·전용 테스트 0(grep 확인).
- vestigial half-implemented 제거(코드 −9줄). 진짜 catch-all 지원은 segCount 버킷팅 재설계 필요한 별도 기능(실수요 시 별도 항목, doc/04 §1.1 보존).

### 다음 단계
- 커밋 보류(리뷰 후). 리뷰 후 커밋·PR.

## 2026-06-25 세션 25 — base-path-strip: false Shadow/Unused 방지 (doc/27, D38, D37 F1)

### 한 일
- **at-match additive strip**(파싱/canonical 불변, SoT 보존, 기본 off=무회귀):
  - `EndpointMatcher.match(method,host,path,stripPrefix)` 4-arg 오버로드 — as-is 우선, 미매칭 && stripPrefix(non-blank) 시 `stripPrefix+path` **재부착(prepend)** 1회 재시도. 기존 3-arg 불변.
  - `DomainConfig.basePathStrip`(String nullable, 기본 null=off) + `DomainDtos`/`DomainController` 가산(null=off, intervalOverride 동형 직접 대입). ddl-auto 컬럼.
  - `Classifier.classifyWithMetrics`(6→7-arg, null 위임)/`classify`(5→6-arg) +stripPrefix → 1차 패스 matcher.match 2곳 전달. `DiscoveryJobService`·`CombinedDiscoveryService` 가 `DomainConfig.basePathStrip` 로드·주입.
- **방향 확정**: doc/27 §3·D38·TASKS 는 **prepend**(관측=strip됨, 스펙=basePath 결합 → 관측에 prefix 재부착). 구현 요청 prose 의 "stripPrefix 제거" 와 반대였으나 F1 근본원인(관측이 이미 strip됨)상 prepend 가 정답 — 설계대로 구현, 보고서에 prose 불일치 명시.
- doc/03 §2.2 갱신(parse-time 결합 토글 → at-match strip 으로 정제), doc/27 §6·TASKS subitem [x](doc/18 sync 만 잔여=technical_writer).

### 결과
- build BUILD SUCCESSFUL, **tests=319 failures=0 skipped=1**(+5: matcher 4건 strip재부착/as-is우선/null현행/잘못prefix무오판, e2e 1건 strip→Active·false Shadow/Unused 해소·null 대조·ETag bump·재스캔 동일).
- 무회귀: 기본 null=as-is=현행 100%, as-is 우선(double-prefix·기존 매칭 불변), canonical/specVersion 불변(파싱 무변경), matcherCache(host,specVersion) 불변(strip=match 파라미터→무효화 불요). ETag: 설정 시 findings 변화 bump(정당)·재스캔 동일(결정적·시간非의존).

### 다음 단계
- 커밋 보류(리뷰 후). 리뷰 후 커밋·PR. doc/18 sync(`domain_config.base_path_strip`)=technical_writer 후속.

## 2026-06-25 세션 24 — 매칭 엣지 케이스 회귀 테스트 (doc/04 §7.1, D37)

### 한 일
- **순수 테스트 3건 추가**(프로덕션 무변경, 기존 클래스 확장 + `// doc/04 §7 caseN` 태그):
  - case3 `EndpointMatcherTest.sameTemplateDistinctMethodsMatchSeparately` — 동일 템플릿(/orders/{id}) GET·POST 정의 → 각 관측 method 가 자기 operation 에만 distinct 매칭(기존 methodMustMatch 는 mismatch 만).
  - case4 `EndpointMatcherTest.specificityFrontSegmentPriorityAndTie` — (a) 앞 세그먼트 우선: `/api/{key}`[1,0] vs `/{tenant}/config`[0,1] 가 `/api/config` 둘 다 매칭·staticCount 동률(1=1)이나 앞 세그먼트 정적 우선→`/api/{key}` (staticCount-only 면 안 갈림). (b) 동률: 동일 specificity 두 변수 템플릿→먼저 정의된 것 결정적 승리(무crash).
  - case5 `ClassifierTest.inferredOnlyShadowLosesExactlyPointOneConfidence` — INFERRED 단독 −0.1 격리. control=healthyShadow(SPEC=1.0), 동일 신호 source 만 INFERRED→0.9(다른 감점/가점 0).
- **doc/04 §7.1 표 갱신**: case3/4/5 상태 🔶→✅ + 신규 테스트명 기입. 기존 커버 5케이스(1·6·7·8·9)는 잠그는 테스트 명시만(중복 테스트 추가 안 함). TASKS subitem(표·신규 3건·확인) [x].
- F1(base-path-strip 미구현)·F2(catch-all `{var+}` dead code)는 회귀로 고정 안 함(현행 미구현/버그) — TASKS 후속 [ ] 유지. 작성 중 신규 코드-문서 불일치 미발견(F1/F2 외).

### 결과
- build BUILD SUCCESSFUL, **tests=314 failures=0 skipped=1**(+3). 프로덕션 코드 무변경(테스트·문서만).
- **커밋 보류**(리뷰 후). 브랜치 `feature/matching-edge-regression-tests`(현 main=멀티스펙 PR#16 머지 위).

### 다음 단계
- 리뷰 후 커밋·PR. F1/F2 는 별도 후속(P2).

## 2026-06-25 세션 23 — 멀티스펙 3단계: 결합 Discovery 뷰 + 버전 그룹 (doc/26 §4/§6/§7, D35/D36)

### 한 일
- **결합 Discovery 뷰**: `batch/CombinedDiscoveryService.forHost(host)` — 누적 `discovered_endpoint` 행을 `DiscoveredEndpoint` 로 재구성(∪) + active spec canonical → Classifier(불변 5-arg classify, 게이트 동일)로 Shadow/Active/Zombie/Unused 산출. per-scan /result(최근 윈도우)와 별개의 누적 카탈로그 뷰(둘 다, doc/26 §6 권장). `model/CombinedDiscovery`(host/specVersion/mode/findings/versionGroups/specSource) + `api/CombinedDiscoveryController` `GET /api/v1/domains/{host}/discovery`.
- **버전 그룹(VERSION_GROUPED)**: 매칭은 union(MERGE 동일) 그대로, 결합 뷰만 version 라벨별 분리. `model/VersionTag.ofPath`(path `^v\d+$`, 1단계 deriveVersion 도 이 util 로 통일) ∪ spec endpoint version → `CombinedDiscovery.VersionGroup`(라벨 정렬). 그 외 모드 flat(versionGroups 빈 list).
- **SpecSource 멀티문서 확장(2단계 이월)**: `SpecSource` +`documents[]`(SpecDocument: specName/format/specVersion) + 3-arg 하위호환 ctor. `SpecStore.specSourceFrom`(specName 정렬 결정적, format 단일/혼합 null, warnings union, documents) + `activeRecords`/static `parseWarnings`. per-scan analyze 도 specSourceFrom 사용(activeRecords 미가용 시 activeMeta 단건 폴백 → 테스트 무변경).

### 결과
- build BUILD SUCCESSFUL, **tests=310 failures=0 skipped=1**(+5: 카탈로그 분류·무스펙 Shadow·VERSION_GROUPED 그룹분리·flat 무그룹·SpecSource documents). 컴파일·@SpringBootTest 정상.
- 무회귀: Classifier 로직 무변경(결합 뷰는 입력만 누적 카탈로그), 기본 MERGE+단일=현행 결합 동치, VERSION_GROUPED 외 flat, ETag 결정적·시간非의존(결합 뷰에 lastSeen 비노출·findings severity→band·params 투영 유지). 기존 SpecSource 3-arg 호출부(EMPTY·ReportBuilderTest) 하위호환 ctor 로 무변경.
- 한계: 카탈로그가 distinctClients/p50·p95/acrm 미보유(§2 카탈로그 경량)→결합 뷰 Shadow confidence 근사(분류 자체 무영향). 원 카탈로그 list REST(/discovered·/spec)=결합 뷰로 충족·중앙 노출 P4 생략.

### 통합 최종 리뷰 P3 2건 반영(2026-06-25)
- **P3-2(ETag 정밀)**: per-scan ETag 의 specSource 입력에서 `documents[].specVersion`(per-record monotonic) 제외 → `specSourceEtagView`(format/warnings/문서 name·format 만). 합성 content 버전은 `report.specVersion`(별도 입력)이 반영 → 동일 콘텐츠 재업로드 무bump(2단계 content-stable 복원). persist 주석 정정. 회귀 테스트 `sameSpecContentReuploadDoesNotBumpEtagDespitePerRecordVersion` 추가(tests=311).
- **P3-1(acrm 한계 문서화)**: doc/26 §11 에 '결합 카탈로그 뷰 OPTIONS confidence=M3 dormant 가정, M3-ACTIVE 충실성 위해 `discovered_endpoint.acrmPresentCount` 저장은 후속' 명시(코드 무변경, 과한 선구현 회피).

### 다음 단계
- 브랜치 `feature/multi-spec-merge` 커밋(누적, 1~3단계+최종 P3 완료). **이후: doc/18 sync(technical_writer) + 최종 빌드 + 1 PR 머지**(D28 — 머지 시 parent TASKS·설계문서 완료 동기).

## 2026-06-25 세션 22 — 멀티스펙 2단계: 멀티 문서 + 병합 모드 (doc/26 §3/§5/§8, D35)

### 한 일
- **병합 전략 설정**: `model/SpecMergeStrategy{MERGE,SEPARATE,VERSION_GROUPED}` + `DomainConfig.specMergeStrategy`(@Enumerated STRING, 기본 MERGE, ddl-auto null→읽을 때 MERGE) + `DomainDtos.DomainUpsert/DomainView` 가산 필드 + `DomainController.apply/toView`(null→MERGE 유지, 미지정 PUT 이 모드 안 지움).
- **SpecStore 모드 분기**: `upload(host,name,content)` 신설(+`upload(host,content)`=default 위임=현행 무회귀). 모드별 기존 active 비활성화 — SEPARATE=host 전체 교체, MERGE/VERSION_GROUPED=같은 specName 만(형제 문서 유지). null specName(기존행)=default 정규화. `DomainConfigRepository` 주입(mode 조회).
- **결정적 merge**: `SpecCanonicalizer.merge(List<VersionedCanonical>)` — (method,host,template) dedupe + deprecated OR + 비-deprecated latest-upload-wins(최신 specVersion, tie sourceRef 큰 값). group+max+OR 교환법칙→업로드/문서 순서 무관 동일 SET. 단일 문서=canonicalize 동치(무회귀). `loadActiveCanonical`=∪ active docs merge(findByHostAndActiveIsTrue).
- **합성 spec 버전**: `SpecStore.syntheticVersion(canonical, om)`=merged canonical SHA-256 해시(EtagUtil 앞 16hex=64bit→long, ETag 와 동일 알고리즘·코드베이스 일관). `DiscoveryJobService` 가 per-record specVersion 대신 합성버전을 report.specVersion/SpecSource/matcherCache 키로 사용 — 동일 콘텐츠=동일 버전(안정), 콘텐츠 변화 시만 bump. 무스펙=0. (P3-1: CRC32→SHA-256 통일.)

### 결과
- build BUILD SUCCESSFUL, **tests=305 failures=0 skipped=1**(+7: 모드 3종·재업로드 교체·merge 순서무관/deprecated OR·합성버전 안정). 기존 SpecStore/JobService default 경로 무변경(단일=현행). @SpringBootTest 컨텍스트 정상(SpecStore +DomainConfigRepository 주입).
- 무회귀: 기본 MERGE+단일 문서=현행 동치, SpecCanonicalizer 단일 경로 보존, ETag 결정적·시간非의존(합성버전=콘텐츠 해시). DiscoveryJobServiceTest specVersion 단언 1건(per-record 5L→non-zero 합성)으로 갱신.
- 한계(stage 3): 멀티문서 SpecSource format/warnings **union·documents[]** 미구현(현재 합성버전+latest active 메타). SpecController name 파라미터 REST 노출 미포함(서비스 계층만). 버전그룹 뷰=3단계.

### 다음 단계
- 브랜치 `feature/multi-spec-merge` 커밋(누적)·리뷰 대기. 리뷰 후 3단계(결합·버전그룹 C/D) 별도 지시.

## 2026-06-25 세션 21 — 멀티스펙 1단계: 검출 SoT 데이터 모델 (doc/26 §2/§4/§8, D35/D36)

### 한 일
- **신규 검출 SoT**: `domain/DiscoveredEndpointRecord`(@Entity `discovered_endpoint`, host index+unique(host,method,path_template)+(host,version) index, id PK=spec_record 스타일) + `DiscoveredEndpointRepository`(findByHost/findByHostAndVersion/findByHostAndMethodAndPathTemplate/deleteByHostAndLastSeenBefore). 컬럼: identity+templateSource+endpointKind+kindConfidence+version+firstSeen/lastSeen/lastScanAt+hits+statusDistJson+hadQuery/nonBrowserUa/paramsJson(@Lob).
- **누적 upsert**: `DiscoveryJobService.analyze` — 스캔 전 `loadDiscovered`(retention prune 180d=데이터 ts 기준 + findByHost→signature 맵), persist 후 `upsertDiscovered`(firstSeen min/lastSeen max/최신 윈도우 스냅샷, cap 5000=신규 identity 제한). version 도출(path `^v\d+$` 세그먼트→매칭 spec.version→null).
- **EndpointHistory 흡수(D36)**: severity recency 를 `discovered_endpoint.firstSeen`(검출 signature 키)로 전환 — `Evidence.entrenchedFirstSeen`(add 시 prior 누적 min), Classifier 2차 Zombie 가 ev.entrenchedFirstSeen 사용. `EndpointHistory` 엔티티/repo·`model/EndpointObservation`·`ClassificationResult.observedTimes` 제거(orphan).
- **spec_record**: +`specName` 컬럼(null→"default" 해석, 스키마/컬럼만 — 멀티문서 upsert 는 2단계).

### 결과
- `JAVA_HOME=…/java-21 ./gradlew build` BUILD SUCCESSFUL, **tests=297 failures=0 skipped=1**(observedTimes 단위 테스트 1건 제거=298→297). @SpringBootTest 컨텍스트가 ddl-auto 로 `discovered_endpoint` 생성→엔티티 매핑 H2 검증.
- 무회귀: 콜드스타트(빈 discovered_endpoint)=현행 severity. ETag 무영향(discovered_endpoint 는 ETag 입력 아님 → lastSeen 자동 제외, doc/26 §8). 재스캔 동일 데이터→lifespan 0→동일 version 유지.
- 문서: doc/26 §10 1단계 [x], doc/24 §3 EndpointHistory 흡수 갱신 주석, TASKS 1단계 subitem [x](부모는 2·3단계 잔여로 [ ]), DECISIONS D36 진행 기록.

### 다음 단계
- 브랜치 `feature/multi-spec-merge` 커밋(누적)·리뷰 대기. 리뷰 후 2단계(멀티스펙+모드 B/D35) 별도 지시. doc/18 sync(discovered_endpoint·spec_name·endpoint_history 제거)=technical_writer 후속.

## 2026-06-24 세션 20 — $type taxonomy 실 Loki 샘플링 (research 0.4, doc/21 §A)

### 한 일
- **도구**: `sample/type_taxonomy_sample.py` 신규(부하보호 내장 — limit=2000·창=10분·`direction=forward`·페이지 1·순차, `limit=1e8` 우회 금지). raw 라인 로컬 `^|^` split→field 19($type)/9(status)/5(request→method)/8(uri→path) 교차 집계(LogLineParser 인덱스 동일).
- **샘플(총 쿼리 3회, 한 자리·D7 준수)**: W1 `AORV1`/api.weble.net @09:00(2000줄, 포화), W2 `AOKD1`/www.dreampark-sporex.com @09:00(381줄 전량), W3 `AORV1`/api.weble.net @03:00 off-peak 교차검증(2000줄). status 필터 없이 받아 method·status-class 교차. skip=0.

### 결과
- **vocabulary = {document, library} 뿐**. `API_TYPES{xhr,fetch,json,api,ajax}` **실관측 0**(3윈도우·2호스트·peak/off-peak·write/4xx/OPTIONS 포함 — status=200 GET 편향 제거 후에도 0).
- **document 트랩 ≈100% 확정**: api.weble.net 은 OPTIONS=2066(CORS)·POST write·RESTful path(`/users/{id}` 등) 전부 `$type=document`. web_page 신호 신뢰 불가 재확인. 시간대 무관(peak/off-peak 동일).
- 웹 호스트: library 86.6%(전부 GET·정적확장자)→STATIC, document 13.4%(확장자 없는 page path)→WEB_PAGE 약신호. 확장자 1순위라 library 집합 추가는 무영향.
- **분석 권고(§B)**: Tier0 API_TYPES **무변경**(신규 api성 0, 보수적 비대칭 기준 미충족), ApiScorer 무변경(자동 정합). `$type→API_CANDIDATE`/responseTypeApi **dormant 확정**(무해·무감점). Tier1 corpus 히스토그램 노출 가치 상향(앱별 vocab·트랩 self-reporting). 증거표 doc/21 §A-결과 기록.

### 다음 단계
- (분석/architect·dev) §A-결과 → DECISIONS 정식 기록(API_TYPES 무변경+근거 주석), Tier1 히스토그램 구현 여부 확정. TASKS '$type 전체 taxonomy 샘플링' subitem 진행.

## 2026-06-24 세션 19 — endpoint_kind referer 보조 신호 (doc/20 §7, DECISIONS D29)

### 한 일
- **신규**: `model/SignalStatus{ACTIVE,DORMANT}`·`model/RefererSignal`(internal corpus: pageUrls·ratios·`dormant()`·`active()`)·`model/EndpointKindSignal`(노출: status·ratios·`NONE`), `normalize/RefererSignalExtractor`(@Component).
- **신호 구축(corpus pre-pass)**: `RefererSignalExtractor.build(requests)` — static 요청(확장자/`$type=library`)의 referer→path 추출(scheme/host·query·fragment 제거)→`PathNormalizer.inferTemplate`→PAGE_URLS 빈도. 커버리지 게이트 `static_ratio≥0.05 AND referer_present_ratio≥0.20`(원시 ratio 비교)→ACTIVE, 미달→DORMANT.
- **분류 통합**: `EndpointKindClassifier` 3-arg `classify(template, typeDist, RefererSignal)` — `$type+확장자` 우선, 결과 `UNKNOWN && active && pageUrls≥2 → WEB_PAGE conf 0.6`(비대칭 양성, 부재→UNKNOWN 무감점). 2-arg 는 `dormant()` 위임 하위호환. `isStaticPath()` public static 노출(DRY).
- **배선**: `InventoryBuilder.buildWithLimits` corpus pre-pass 1회→classify 3-arg→`InventoryResult` 에 `EndpointKindSignal`. `DiscoveryReport` top-level + `ReportBuilder.build` 인자 + `DiscoveryJobService` ETag 입력(ratios round3, 노출용에만).
- **무회귀**: `$type` 결정 케이스 referer 분기 미진입, DORMANT 환경(정적 미경유/referer 부재) 전 endpoint 현행 UNKNOWN, 2-arg 오버로드로 기존 EndpointKindClassifierTest 무영향, WEB_PAGE⊕API_CANDIDATE 배타로 doc/17 responseTypeApi 무충돌.
- **리뷰 2라운드**: ① 구현 6건 → ② P3 2건(게이트 원시 ratio 분리 정합성, 경계 포함성 `>=` 테스트). P1=0/P2=0/P3=0. D26/D28 규칙대로 TASKS subitem 6건·doc/20 §7 [x] 동기.
- 마무리: GitHub PR 워크플로(push → `gh pr create` → 팀장 머지).

### 결과
- BUILD SUCCESSFUL, **tests=256 skipped=1(라이브) failures=0**. RefererSignalExtractorTest 6·EndpointKindClassifierTest +4·InventoryBuilderTest +2·DiscoveryJobServiceTest +1.

### 다음
- 후속(범위 밖): api_candidate 약가점(미채택)·referer 동적 임계 중앙 API.

## 2026-06-24 세션 18 — 설계문서 체크리스트 완료 백필 + 동기갱신 프로세스 (CLAUDE.md, DECISIONS D28)

### 한 일
- **코드 변경 0** (문서 전용, 브랜치 `docs/checklist-sync-and-process`). doc/09~19 점검에서 구현 완료된 dev 체크리스트가 `[ ]` 로 남아 미구현 오해 소지 발견 → 일괄 정정.
- **체크리스트 백필**: doc/09~17,19 의 'dev 구현 체크리스트' **106항목**을 실제 머지 코드와 대조 후 `[x]` 표기. 각 헤더에 historical 안내 줄(2026-06-24 코드 대조, 잔여는 §범위 밖·TASKS 참조) 추가. ('범위 밖/후속/한계'는 미완료로 두고 TASKS 후속 추적.)
- **doc/19 보강**: §2 승격/상한 전 제거 근거 문구 정밀화(전부-404 클러스터=동치 / 혼합 클러스터=비실재 noise 를 승격 stats 에서 배제하는 의도), §6 헤더 historical 줄을 09~17 과 동일 문구('잔여는 §범위 밖/후속·TASKS 참조')로 일관화(P3).
- **프로세스 코드화(D28, D26 보완)**: CLAUDE.md '작업 항목 관리' 섹션에 "구현 완료 PR 머지 시 TASKS subitem/부모 갱신과 함께 **해당 설계문서 dev 체크리스트도 `[x]` 동기 갱신**" 규칙 추가. TASKS=우선순위 단일 기준, 설계문서 체크리스트=그 문서 범위 구현 상태 — 같은 PR 에서 동기.
- **리뷰**: P1=0/P2=0, P3(doc/19 헤더 문구 일관화) 반영. 마무리 GitHub PR 워크플로(팀장 지시 머지).

### 결과
- 문서만 변경(doc/09~17,19·CLAUDE.md·DECISIONS·PROJECT_LOG), 빌드/테스트 영향 없음(tests=243 유지). 설계문서가 자기 구현 상태를 정확히 반영.

### 다음
- 신규 작업 구현 PR 머지 시 D28 규칙대로 설계문서 체크리스트 동기 체크.

## 2026-06-24 세션 17 — 실재성 404-only 필터 (인벤토리 단계, doc/19 §6, DECISIONS D27)

### 한 일
- **신규**: `model/DroppedNonExistent(int notFound)`(+`NONE`, DiscoveryReport top-level·ETag 포함). 게이트 탈락(DroppedNonApi)·상한(DroppedByLimit)과 성격 다른 실재성 형제 record.
- **수정**: `Acc` 에 `status404` 전용 카운터(`add` 에서 `status==404` 만 증가, `mergeFrom` 합산) + `isNonExistent()`(hits>0 && status404==hits) + `source()`. **통합 4xx 버킷이 아닌 404 전용**이라 401/403-only(인증벽 뒤 실재) 보존.
  `InventoryBuilder.buildWithLimits` 에 Acc 집계 후·승격/상한 전 `source==INFERRED && isNonExistent()` 제외+카운트(SPEC 보호, 스캐너 noise 를 상한 예산 전에 제거) + `InventoryResult` 에 droppedNonExistent. `DiscoveryReport`/`ReportBuilder.build` 인자 추가, `DiscoveryJobService` 전달 + ETag 입력 포함.
- **역할 분리**: hard-drop(100%-404, INFERRED, 인벤토리) ⊂ soft(4xx≥90%, Classifier -0.7) — hard-drop 이 먼저 제거 → soft 와 중복 없음. 회색지대(mostly-4xx ≠100%)는 보존→저신뢰 Shadow 보고.
- **무회귀**: 401·403-only 보존·2xx/3xx/5xx 혼재 보존·mostly-4xx 보존(soft -0.7 유지)·SPEC 보존. 기존 InventoryBuilderTest(404 혼재) 무영향, ClassifierTest(DiscoveredEndpoint 직접 생성→인벤토리 미경유) 무영향.
- **리뷰 2라운드**: ① 구현 5건 → ② P3 doc/19 문구 보강. P1=0/P2=0. D26 규칙대로 TASKS subitem 5건 [x]→부모 Done 이동.
- 마무리: GitHub PR 워크플로(push → `gh pr create` → 팀장 지시 `gh pr merge --merge --delete-branch`).

### 결과
- BUILD SUCCESSFUL, **tests=243 skipped=1(라이브) failures=0**. InventoryBuilderTest +5·DiscoveryJobServiceTest +1.

### 다음
- 후속(P1): "문서화됐는데 404-only=미배포" 경고(SPEC 매칭분, 별도 신호)·401/403 status 세분(현재 404 만 별도 버킷).

## 2026-06-24 세션 16 — 작업 항목 subitem 추적 관리규칙 코드화 (CLAUDE.md, DECISIONS D26)

### 한 일
- **코드 변경 0** (문서 전용, 브랜치 `docs/subitem-tracking-policy`).
- **CLAUDE.md** 신규 섹션 '작업 항목 관리 — 설계 도출 항목의 subitem 추적' 추가: architect 가 설계를 완료하면 도출된 **dev 구현 체크리스트 + 후속/한계** 를 `doc/TASKS.md` 의 **해당 부모 항목 아래 subitem(들여쓴 하위 체크박스)** 으로 추가, subitem 완료 시 `[x]`, **모든 subitem 완료 시 부모를 `[x]` 로 바꿔 Done 이동**. TASKS = 단일 권위, 설계문서 = 근거·상세. P3 보강으로 진행중/완료 2상태 예시 블록 포함.
- **DECISIONS D26**: 위 규칙을 영구 결정으로 기록(D25 '설계문서↔TASKS 매핑·P1~P4 우선순위' 연계 — 새 subitem 은 부모의 P 버킷을 따름). 근거 = 항목 단위 완료 확인으로 추적성·설계↔실행 싱크 유지.
- **TASKS 헤더**: subitem 추적·부모 Done 이동 규칙 한 줄 노트(D26 참조) 추가.
- **리뷰**: P1=0/P2=0, P3 보강(예시 블록) 반영. 마무리 GitHub PR 워크플로(팀장 지시 머지).

### 결과
- 문서만 변경(CLAUDE.md·DECISIONS·TASKS·PROJECT_LOG), 빌드/테스트 영향 없음(tests=237 유지). 다음 세션부터 설계 도출 dev 항목은 TASKS subitem 으로 추적.

### 다음
- 신규 작업 설계 시 architect 가 도출 항목을 부모 subitem 으로 등록 → dev 가 항목 단위 완료 표기. P1 자체 분석기능 착수는 별개 진행.

## 2026-06-23 세션 15 — 문서 정합화: TASKS↔설계문서 싱크·우선순위 재정렬 + DB 스키마 문서 (DECISIONS D25, doc/18)

### 한 일
- **코드 변경 0** (문서 전용, 브랜치 `docs/tasks-sync-and-db-schema`).
- **TASKS 정합화**: doc/00~17 의 dev 항목·'범위 밖/후속'을 전수 추출해 TASKS 와 교차대조(누락 0). 미반영 후속 추가 — cross-scan recency(doc/16)·Active/Zombie param 노출(doc/13)·scan-status total_dropped(doc/12 선택)·분석 파라미터 중앙 API 확장 묶음(repeatMinCount/sensitive·상한/severity 임계). 완료된 "API 점수화 모델"(전부 [x]) Done 이동, 보류 섹션의 response_type_api 중복 제거.
- **우선순위 재배열(사용자 결정, D25)**: 기본/자체 기능 먼저·외부(중앙) 연동 나중. TODO 를 **P1 자체 분석기능 → P2 품질/테스트 → P3 운영 → P4 외부연동 → 보류** 로 재배열(섹션 순서로 우선순위 표현 + `→ 의존:` 선행조건 메모). P4=서비스간 인증·완료 웹훅·분석 파라미터 중앙 API.
- **TASKS 상단 '설계문서↔TASKS 매핑' 표** 추가(doc 09~17 ↔ 완료/후속 단일 기준).
- **doc/18(DB 스키마)** writer 작성분(d778cc6, 엔티티 6종→7테이블·ddl-auto·H2/PG 컨벤션)에 P3 보강: `@Lob String` 의 PostgreSQL 기본 매핑이 `text` 가 아니라 **`oid`(large object) 함정** — `@Column(columnDefinition="text")`/`@JdbcTypeCode(LONGVARCHAR)` 명시 필요. TASKS "PG TEXT 매핑 실검증"(P2) 항목과 연결.
- **리뷰**: P1=0/P2=0, P3 보강 반영. 마무리 GitHub PR 워크플로(d778cc6 + 신규 커밋 포함 PR → 팀장 지시 머지).

### 결과
- 문서만 변경(doc/18·TASKS·DECISIONS·PROJECT_LOG), 빌드/테스트 영향 없음(tests=237 유지). 다음 세션은 TASKS 매핑표+P1~P4 버킷을 단일 기준으로 사용.

### 다음
- P1 자체 분석기능부터 착수(예: $type taxonomy 샘플링·cross-scan recency·Active/Zombie param 노출). 외부연동(P4)은 P1 안정 후.

## 2026-06-23 세션 14 — response_type_api 양성 가중치 ($type API성 신호 채택, doc/17 §5, DECISIONS D24)

### 한 일
- **결정적 발견**: API성 `$type` 신호는 이미 `EndpointKind.API_CANDIDATE`($type∈{xhr,fetch,json,api,ajax} dominant)로 존재 → **신규 필드/플래그/Acc 불요**, `endpointKind==API_CANDIDATE` 재사용.
- **수정(ApiScorer 1파일, 5건)**: `Weights` 14번째 `responseTypeApi`(pathHint 뒤·threshold 앞), presets MIDDLE 0.25/HIGH 0.18/LOW 0.32(§9 보정전 1차값 캐비엇), `WEIGHT_KEYS` 14, `applyOverrides` ov 반영, `score()` 공통 섹션 `API_CANDIDATE → += responseTypeApi`(양성-only).
- **비대칭/충돌**: document(WEB_PAGE)/UNKNOWN/$type 부재 무가산·무감점, STATIC 은 penalty 만(API_CANDIDATE 와 상호배타·동시 발화 불가). path 신호와의 동시 발화는 의도된 독립 증거 가산.
- **무변경 확인**: funnel 구조 — resolver(applyOverrides 경유)·`ClassificationDtos`(Weights record 재사용→JSON 자동)·controller(WEIGHT_KEYS 검증→customWeights 자동 수용) 무변경.
- **무회귀**: 비-API endpoint 점수 무변경, API_CANDIDATE만 상승(보류 해제 목적). `scoreClampsToUpperBound`(유일 API_CANDIDATE exact-score)는 1.0 clamp 유지로 무영향. `DiscoveryJobServiceTest` 2건은 `line()` 헬퍼 `$type=api`→API_CANDIDATE라 `/page` x3→x2(repeat 끔)로 0.65<0.70 DROP_LOW_SCORE 의도 보존.
- **리뷰 2라운드**: ① 구현 8건(테스트 5: 가산·비대칭·STATIC penalty만·customWeights / Controller effective 반영) → ② P3 보강(테스트 2, 코드 무변경: explicit-hint+API_CANDIDATE 독립 가산 0.55+0.25=0.80, HIGH 0.18·LOW 0.32 preset exact-score). P1=0/P2=0.
- 마무리: doc/08 §9 보류 항목 활성화(보류→채택). GitHub PR 워크플로(push → `gh pr create` → 팀장 지시 `gh pr merge --merge --delete-branch`).

### 결과
- BUILD SUCCESSFUL, **tests=237 skipped=1(라이브) failures=0**. ApiScorerTest +6(신호 4 + P3 2)·ClassificationControllerTest +1.

### 다음
- 후속(TODO): `$type` 전체 taxonomy 샘플링 확정(API_TYPES 정제 시 자동 수혜)·responseTypeApi 가중치 실데이터 보정.

## 2026-06-23 세션 13 — 버전 기반 Zombie 추정 + Zombie severity (doc/16 §5, DECISIONS D23)

### 한 일
- **신규**: `model/Severity(score)`+`@JsonProperty("band")` 파생·`model/SeverityBand`(≥0.66 HIGH/≥0.33 MEDIUM/LOW),
  `classify/ZombieSeverity.of(Evidence)`(결정적 score=0.5·hitsScore(log10)+0.3·successScore(2xx/total)+0.2·spanScore(lastSeen−firstSeen log10), 외부 시계 미사용),
  `classify/VersionZombieInference`(첫 `^v(\d+)$` 버전·resourceKey={method|host|버전위치 {V} 치환 template} 페어링, 그룹 active Vmax 미만 active→추정, parseInt 오버플로 비버전 폴백),
  `classify/Evidence`(hits/2xx/total/firstSeen/lastSeen 누적).
- **수정**: `Finding.Zombie` 에 `Severity severity`+`boolean estimated` 가산. `Classifier` 의 `observedSpecKeys: Set→Map<String,Evidence>`
  (1st pass 매칭 d 메트릭 누적, host-agnostic spec 다중 host 합산), 2nd pass 버전 추정+severity 배선(명시 deprecated 1.0·estimated=false, 추정 0.6·estimated=true, 모든 Zombie severity).
- **역할 분리**: confidence(진짜 Zombie 인가)↔severity(조치 시급성) 직교. 추정 0.6·가중치/임계는 코드 상수(1차, 튜닝 시 @ConfigurationProperties seam).
- **무회귀**: 명시 deprecated Zombie confidence 1.0·reason 보존, 버전 페어 없는 spec 전부 현행. findings 라 reportJson·ETag 자동 반영.
- **리뷰 2라운드**: ① 구현 9건 → ② P3 보강(parseInt 오버플로 가드+테스트, Classifier 엣지 2건: 신버전 Unused→구버전 미추정·구버전 명시 deprecated→명시 1.0 유지). P1=0/P2=0.
- 마무리: GitHub PR 워크플로(push → `gh pr create` → 팀장 지시 `gh pr merge --merge --delete-branch`).

### 결과
- BUILD SUCCESSFUL, **tests=230 skipped=1(라이브) failures=0**. VersionZombieInferenceTest 8·ZombieSeverityTest 5·Classifier 통합 5(+).

### 다음
- 후속(TODO): 절대 cross-scan recency(스캔 히스토리)·추정 임계/severity 가중치 중앙 API 설정.

## 2026-06-23 세션 12 — 매처 캐시 무효화 (doc/15 §5, DECISIONS D22)

### 한 일
- **신규**: `match/EndpointMatcherCache`(@Component) — `ConcurrentHashMap<String,VersionedMatcher(specVersion,matcher)>`,
  `get(host,specVersion,Supplier)`=`compute`(버전 일치 재사용/불일치 supplier 재빌드+슬롯 교체), `invalidate(host)`/`invalidateAll()`.
  (host,specVersion) 키·host당 1슬롯 → 새 버전이 덮어써 무누수, version 키로 stale 서빙 구조적 불가, poisoning-free(build throw→미저장).
  `compute` per-host 락으로 동일 host 동시 빌드 직렬화(중복 빌드 방지, 의도 주석).
- **수정**: `SpecStore` 생성자 캐시 주입 + upload save 후 `invalidate(host)`(기존 :81 TODO 대체).
  `DiscoveryJobService` 생성자 캐시 주입(specStore 뒤) + analyze 의 `new EndpointMatcher(spec)`→`matcherCache.get(host,specVersion,()->new EndpointMatcher(spec))`. specVersion=0(스펙 없음) 균일 캐시.
- **순환 회피**: 캐시 무의존 — 스펙 로드 안 하고 build supplier 를 호출측 제공(writer 무효화/소비자 빌드 원칙, doc/11 동일). EndpointMatcher 불변→공유·동시 read 안전.
- **무회귀**: 동일 spec→동일 matcher→findings/리포트/ETag 불변(재생성만 제거). 기존 SpecStore/DiscoveryJobService 테스트가 real 캐시 경유 green, 수동 생성자 인자만 추가(가산).
- **리뷰 2라운드**: ① 구현 7건 → ② P3 정보성 주석(compute per-host 직렬화 의도, 코드/테스트 무변경). P1=0/P2=0.
- 마무리: GitHub PR 워크플로(push → `gh pr create` → 팀장 지시 `gh pr merge --merge --delete-branch`).

### 결과
- BUILD SUCCESSFUL, **tests=212 skipped=1(라이브) failures=0**. EndpointMatcherCacheTest 5(+SpecStore invalidate 검증·DiscoveryJobService v1→v2 stale없음 통합).

### 다음
- 후속(TODO): 멀티 인스턴스 cross-instance 캐시 무효화(HA, ShedLock 도입 시 — doc/11 §3 한계와 동일)·멀티 스펙 병합·spec_source.warnings 채널.

## 2026-06-23 세션 11 — 스펙 파서 Postman/CSV 실구현 + 공유 정규화 (doc/14 §6, DECISIONS D21)

### 한 일
- **공유 신규**: `SpecNormalize`(template: `:var`/`{{var}}`→`{var}`·슬래시 규칙, host: 소문자/null) — 3종 동일성 단일 진실원.
  `SpecCanonicalizer.canonicalize`(dedupe(method,host,template)+deprecated OR+안정정렬) — SpecStore.upload parse 직후 **전 포맷 균일** 적용(ETag 결정성).
- **PostmanSpecParser**: ObjectMapper 주입, item 트리 DFS(폴더 name·deprecated 자식 전파), url object(path 배열/문자열)/string, host 배열 `.`join+`{{baseUrl}}` 변수 치환(실패→null),
  path 변수→`{x}`, deprecated=`[DEPRECATED]`/`(deprecated)`/description, sourceRef `postman#이름경로`. 루트/item 부재→IAE, method/url 누락 leaf→skip+warn.
- **CsvSpecParser**: univocity(header 추출·BOM strip·따옴표/내장콤마), 필수 헤더(method/path) 누락→fatal, deprecated 토큰(true/false/1/0/y/n/yes/no·빈값 false·미인식 warn),
  `:var`→`{var}`, host 빈값→null, 불량행 skip+warn(row n), sourceRef `csv#row{n}`.
- **배선**: SpecStore.upload canonicalize 적용, SpecFormatDetector `schema.postman.com` 추가. **신규 의존성 0**, `SpecParser`/`SpecRecord`/`OpenApiSpecParser` 시그니처 무변경.
- **오류 처리**: fatal→IllegalArgumentException(→400), recoverable→skip+log.warn(유효분 반환). 구조화 spec_source.warnings 채널은 범위 밖.
- **무회귀**: 기존 SpecStore/OpenApi 테스트 green(canonicalize 정렬은 순서무관 단언에 영향 없음, 유효 endpoint 집합 동일).
- **리뷰 2라운드**: ① 구현 11건 → ② P3 보강(다중요소 host 배열, url 누락 leaf skip·빈 deprecated 셀, (deprecated)/[deprecated] 마커 변형). 전건 해소.
- 마무리: GitHub PR 워크플로(push → `gh pr create` → 팀장 지시 `gh pr merge --merge --delete-branch`).

### 결과
- BUILD SUCCESSFUL, **tests=205 skipped=1(라이브) failures=0**. 3종 포맷 Canonical 동일성(`ThreeFormatEquivalenceTest`) 검증. 내부 리뷰 P1=0/P2=0.

### 다음
- 후속(TODO): 매처 캐시 무효화(SpecStore 업로드 시 `(host,specVersion)` evict)·멀티 스펙 병합·구조화 spec_source.warnings 채널(seam=SpecParseResult).

## 2026-06-23 세션 10 — 정규화 고카디널리티 방지 (T1 승격+상한 / T2 param 후보 / T3 sensitive) (doc/13 §5, DECISIONS D20)

### 한 일
- **T1**: `CardinalityNormalizer`(@Component) — 통계 {var} 승격(클러스터·distinct≥20·ratio≥0.3·수렴≥0.7 비지배+형제 재병합) +
  host template 상한(5000, hits 낮은순 drop)→`DroppedByLimit`. `Acc` 를 package-private 톱레벨로 추출해 공유. `InventoryBuilder` 패스 3·4 통합.
- **T2**: `ParsedRequest.queryKeys→queryParams`(값 폐기, `ValueLenBucket` 길이버킷만 — privacy-preserving 내부필드), `LogLineParser` 버킷화.
  `ParamCandidates(query/path)`·`QueryParamObs`·`DiscoveredEndpoint.params`·`ParamCandidateExtractor`(per-endpoint param 상한 50→DroppedByLimit.params).
- **T3**: `SensitiveKeyProperties`(yml 기본값 내장)+`SensitiveKeyMatcher`(대소문자무시) — 정책: 이름 보존+sensitive flag+값 길이 버킷 억제(REDACTED, 보안신호).
- **배선**: `Finding.Shadow.params`(Classifier 가 d.params() 전달), `DiscoveryReport` top-level `droppedByLimit`+ETag 입력 포함,
  `ReportBuilder.build` 파라미터, `InventoryBuilder.buildWithLimits(InventoryResult)`+`build` 위임(하위호환), `NormalizationProperties`(@ConfigurationProperties+yml).
  파이프라인: 파스→1차템플릿→T1승격→T1상한→T2후보→T3마스킹→방출.
- **무회귀**: 승격 보수적(≥20)·상한 높음(5000/50)→기존 입력 미발동(템플릿 동일·(0,0)), queryParams 내부한정. ScanResult 스키마 무변경.
- **리뷰 2라운드**: ① 구현 22건 → ② P3 보강(Shadow params reportJson e2e, 재병합 metrics 합산 단언, 승격 경계 distinct=20/19). 전건 해소.
- 마무리: GitHub PR 워크플로(브랜치 push → `gh pr create` → 팀장 지시 `gh pr merge --merge --delete-branch`).

### 결과
- BUILD SUCCESSFUL, **tests=184 skipped=1(라이브) failures=0**. 기존 테스트 전건 보존(하위호환). 내부 리뷰 P1=0/P2=0.

### 다음
- 후속(TODO): sensitive/상한 도메인 override·중앙 REST/대시보드, Active/Zombie param 노출, distinct/분위수 HLL/t-digest 근사.

## 2026-06-23 세션 9 — non_api dropped observation 메트릭 (doc/12 §5, DECISIONS D19)

### 한 일
- **신규**: `model/DroppedNonApi`(record excluded/webForm/lowScore + `@JsonProperty("total")` 파생 — JSON 에 total 출현),
  `classify/ClassificationResult`(record findings + dropped).
- **수정**: `Classifier.classifyWithMetrics(5-arg)→ClassificationResult`(게이트 switch: ADMIT→Shadow, DROP_EXCLUDED/WEB_FORM/LOW_SCORE→사유별 ++, default→fail-fast);
  5-arg `classify→List` 는 `.findings()` 위임(3/4-arg 도 위임 유지=하위호환). `DiscoveryReport` top-level `droppedNonApi`(가산적·항상 non-null),
  `ReportBuilder.build` 파라미터 추가(null→(0,0,0)), `DiscoveryJobService.analyze` classifyWithMetrics 전환 + ETag 입력에 droppedNonApi 포함.
- **카운트 대상**: non-OPTIONS·spec 미매칭·게이트 DROP_*(OPTIONS·spec 매칭·ADMIT 제외). 불변식 `discovered(non-OPTIONS)=specMatched+shadow+dropped.total`.
- **노출/영속**: DiscoveryReport 임베드 → reportJson(@Lob) 자동 포함, `/result` 노출. ScanResult 스키마 변경 0. ETag 에 droppedNonApi 포함(분포 변화 반영, 304 미노출 버그 방지).
- **리뷰 2라운드**: ① 구현 10건 → ② P3 마무리(게이트 switch default fail-fast, 불변식 host-agnostic 근사 캐비엇 주석). 전건 해소.
- 테스트: Classifier 사유별 카운트+불변식, ReportBuilder 임베드+빈(0,0,0), reportJson 임베드+ETag dropped 분포 반영. 하위호환(기존 classify→List·LokiLive build 시그니처만).

### 결과
- BUILD SUCCESSFUL, **tests=167 skipped=1(라이브) failures=0**. 하위호환 유지(기존 테스트 전건 보존).

### 다음
- 후속(TODO): Actuator/Micrometer 노출·알람(동일 카운트 재사용)·scan-status/ScanResult total 비정규화(선택).

## 2026-06-23 세션 8 — 분류 설정 중앙 REST API + effective 캐시 활성화 (doc/11 §6, DECISIONS D18)

### 한 일
- **신규**: `api/dto/ClassificationDtos`(5 record — ClassificationUpsert/GlobalClassificationView/DomainClassificationView/OverrideView/EffectiveView, MatcherConfig·ApiScorer.Weights 재사용),
  `api/ClassificationController`(`@RequestMapping("/api/v1")`, 4 엔드포인트 + 컨트롤러-로컬 `@ExceptionHandler(IAE)→400`/`@ExceptionHandler(ISE)→500` + DTO↔엔티티 JSON 왕복 + `DomainConfigRepository.existsById` 404 가드 + PUT 후 invalidate).
- **수정**: `EffectiveClassificationResolver` — `ConcurrentHashMap` 캐시 + `resolve()=computeIfAbsent(host, build)`(본문 `build()` 추출),
  `invalidate(host)=remove`/`invalidateAll()=clear` 실구현. `build()` 가 저장 설정 손상 IAE 를 `IllegalStateException`(cause 보존)으로 래핑(요청 검증 IAE→400 과 분리, 저장 손상→500).
- **계약**: PUT=전체 교체(null=clear), 단일행 upsert. 전역 부재 GET→200 default, 도메인 override 부재→200 effective, 도메인 미등록→404. 스캔경로(DiscoveryJobService) 무변경(resolve 캐시 자동 경유).
- **리뷰 2라운드**: ① 구현 9건 → ② P3 보강(저장 손상 500 매핑—Spring `@ExceptionHandler` cause-체인 매칭이 IAE핸들러로 400 오매핑하던 것을 직접 ISE 핸들러로 차단, PUT clear 회귀, 전역 GET round-trip). 전건 해소.
- 테스트: `ClassificationControllerTest`(@SpringBootTest+MockMvc, 운영 Loki 는 `@MockBean LokiClient` 로 차단) 15건 + resolver 캐시 단위 2건.

### 결과
- BUILD SUCCESSFUL, **tests=164 skipped=1(라이브) failures=0**. 하위호환 유지(기존 테스트 전건 보존).

### 다음
- 후속(TODO): 서비스간 인증(permitAll)·non_api dropped 메트릭·repeatMinCount override·HA cross-instance 캐시 무효화(pub-sub/TTL).

## 2026-06-23 세션 7 — 분류 설정 DB 저장 + effective 병합 (doc/10 §7, DECISIONS D17)

### 한 일
- **신규**: 전역 `ClassificationConfig`(고정 PK=1L)+`DomainClassificationConfig`(host PK) 엔티티·리포지토리,
  `ClassificationProfile`(HIGH/MIDDLE/LOW/CUSTOM), `EffectiveClassification` record, `EffectiveClassificationResolver`(@Service),
  idempotent `ClassificationConfigSeeder`. 저장은 `@Lob String` JSON(매처/custom weights)+`Double`(threshold), JSONB 미사용(H2/PG 이식).
- **수정**: `ApiScorer`(Weights ctor·`weights()`·`presetWeights`·`applyOverrides`·`validateWeightOverrides`/`validateThreshold`),
  `Classifier` 5-arg classify(레거시 3/4-arg→위임 보존), `DiscoveryJobService` resolver 배선(analyze §6).
- **병합(§3)**: profile=도메인??전역??MIDDLE, weights=preset 또는 CUSTOM(MIDDLE+global+domain 키별 domain승),
  threshold=도메인>전역>preset, matcher=`MatcherConfig.merge`(무변경) 전역∪도메인, webForms=전역 null→TRUE 정규화 후 merge=억제 opt-in.
- **무회귀(§5)**: 설정 부재/기본 seed=`ApiScorer(MIDDLE)`+`ApiHintMatcher.NONE`(무억제)와 100% 동치.
- **리뷰 2라운드**: ① 구현 16건 → ② fail-fast 보강(unknown weight 키·threshold[0,1]·비유한 reject, customWeights always-validate(profile 무관, 적용만 CUSTOM), Seeder 단위테스트+exclude e2e, doc/10 §4·TASKS 보완). 전건 해소.
- 태스크별 브랜치 워크플로(`feature/classification-config-store`) → main `--no-ff` 머지.

### 결과
- BUILD SUCCESSFUL, **tests=147 skipped=1(라이브) failures=0**. 하위호환 유지(기존 테스트 전건 보존).

### 다음
- 후속(TODO): 중앙 REST `GET/PUT /classification`(전역·도메인 effective)·캐시 invalidate 배선·non_api dropped 메트릭.

## 2026-06-22 세션 6 — explicit-hint 매처 + 매처 설정 구현 (doc/09 §6, DECISIONS D16)

### 한 일
- **신규**: `model/MatcherConfig`(record — api/exclude prefixes·regexes + nullable `includeWebForms` + `NONE` + `merge`(전역∪도메인 dedup, includeWebForms 상속→기본 false)),
  `match/ApiHintMatcher`(세그먼트경계 prefix·full-match regex, 컴파일 캐시, 개수200/50·길이200/256 상한 fail-fast,
  prefix 비공백·'/'시작·regex 비공백 검증, ReDoS deadline 50ms + 입력상한 4096 + `DeadlineCharSequence` + 타임아웃 카운터·WARN 1회).
- **수정**: `ApiScorer`(`Gate` enum + `evaluate` 게이트 exclude→hint admit→web-form→score, explicit-hint 모드 pathHint weight, 2-arg→NONE 위임),
  `Classifier`(4-arg classify evaluate 게이트·ADMIT만 Shadow, include_web_forms = write-to-WEB_PAGE hard-drop·강신호 override, 3-arg→NONE 위임),
  `DiscoveryJobService`(설정 저장 전까지 NONE 주입 + TODO).
- **테스트**: `ApiHintMatcherTest`(19 — 세그먼트경계·full-match·상한 경계 4종·비공백/'/'검증·다패턴 ReDoS deadline·exclude-regex timeout fallback·NONE),
  `MatcherConfigMergeTest`(7 — prefix/regex 합집합 dedup·includeWebForms 상속), `ApiScorerTest`(+7 explicit-hint/web-form 게이트), `ClassifierTest`(+6 spec 우회·web-form·레거시 불변).
- **리뷰 2라운드**: ① 구현 직후 P2x2/P3x6 → ② blank/'/'검증·상한 경계·ReDoS 견고화·merge regex-union 보강(P2x1/P3x4). 전건 해소.
- 발견: JDK21 이 고전 `(a+)+$` 류 catastrophic 패턴을 최적화 → ReDoS 테스트를 `(.*X){1,N}` 족 다패턴 + deadline 동작(카운터·bounded 시간) 검증으로 견고화.

### 결과
- BUILD SUCCESSFUL, **tests=110 skipped=1(라이브) failures=0**. 하위호환 유지(기존 테스트 전건 보존).

### 다음
- 후속(TODO): 설정 저장(전역 classification DB + 도메인 override)·중앙 튜닝 API(`GET/PUT /classification`)·non_api dropped 메트릭 배선.

## 2026-06-22 세션 5 — ApiScorer(3c3c07d) 리뷰 이슈 수정 (P2x2/P3x6)

### 한 일
- 리뷰 지적 전건 해소. 커밋/푸시 없이 작업 트리만 정리(팀장 재리뷰 후 커밋).
- **P2-1** `InventoryBuilderTest`: nonBrowserUa 집계 테스트 2건 추가 — SDK 다수(true) + 경계
  `sdkUaCount*2 == hits`(정확히 50%, 포함 true) + 50% 미만(false). userAgent 지정 `reqUa` 헬퍼 추가.
- **P2-2** `ApiScorerTest`: null host/template/metrics 각 1케이스 — `score()` 가 throw 없이 안전 처리 확인.
- **P3-1** `ApiScorerTest`: clamp 경계 단언 — 상한 `isEqualTo(1.0)`, 하한 `isEqualTo(0.0)`.
- **P3-2** `ApiScorerTest`: 개별 신호 단위검증 — query/sdk/version/graphql/machine 각 가중치 기여(MIDDLE) 확인.
- **P3-3** `Classifier.classify()` OPTIONS skip 지점에 한계 주석(스펙 OPTIONS operation Unused 오판 가능) +
  `doc/TASKS.md` 분류(04) 섹션에 한계 항목 추가.
- **P3-4** `DiscoveryJobService.analyze()`: discovered 카운트를 OPTIONS 제외(보고 대상) 기준으로 수정 — 인벤토리 수 과대집계 방지.
- **P3-5** locale 통일: `InventoryBuilder`(SDK-UA toLowerCase)·`Classifier.key()`(host/method)를 `Locale.ROOT` 로.
- **P3-6** `ApiScorer.score()` 의 `d.method()` null 가드 추가 (host/template/metrics 와 일관).

### 결과
- BUILD SUCCESSFUL, 전 테스트 통과(아래 빌드 출력 참조). 신규 테스트로 ApiScorer/InventoryBuilder 커버리지 보강.

### 다음
- 팀장 재리뷰 후 커밋. 점수모델 잔여 TODO(explicit hint 매처 설정·전역/도메인 classification 저장·중앙 튜닝 API·not_api dropped 메트릭)는 그대로.

## 2026-06-22 세션 4 — ApiScorer 구현 + Classifier 게이트 연동

### 한 일
- `ApiScorer`(classify/) 구현: 보정 가중치(host_api/cors/path-shape/method/query/ua/static/repeat),
  프로파일 HIGH/MIDDLE/LOW preset, `score()`/`isApiCandidate()`. html penalty 미사용.
- `DiscoveredEndpoint` 에 `hadQuery`·`nonBrowserUa` 신호 필드 추가, `InventoryBuilder` 가 집계(SDK UA 다수 판정).
- `Classifier` 게이트 연동: unmatched → ApiScorer 통과만 Shadow, 미달은 미보고. 스펙 매칭은 게이트 우회(스펙 권위).
  OPTIONS 는 host+template CORS 신호로만 쓰고 그 자체는 미보고(sibling 메서드에 신호 전파).
- 테스트: ApiScorerTest 6, ClassifierTest 게이트 기준 재작성. DiscoveryJobService/LokiLive 테스트 ctor 갱신.

### 결과
- BUILD SUCCESSFUL, **tests=65 skipped=1(라이브) failures=0**. (OpenApiSpecParserTest 의 invalid-doc 로그 스택은 정상)

### 다음
- 남은 점수모델 TODO: 매처 설정(explicit hint)·전역/도메인 classification 설정 저장·중앙 튜닝 API·dropped(not_api) 메트릭.

## 2026-06-22 세션 3 — 점수 모델 가중치 실데이터 보정

### 한 일
- 실 Loki 샘플로 가중치 보정: **api.weble.net**(AORV1, API 호스트=양성) vs **www.dreampark-sporex.com**(AOKD1, 웹=음성).
  (사용자 주: 도메인은 `api.webie.net`→실제 `api.weble.net` 오타였고, AORV1 에 데이터 많음 / 최근 시간대 사용.)
- 발견: ① API 응답도 `$type=document` → html penalty 가 진짜 API를 0점화(100% 미탐). ② 깨끗한 REST+browser fetch 라
  path/method/query 만으론 약함. ③ 강한 가용 신호 = **host=api 서브도메인 + CORS(OPTIONS) preflight**.
- 보정: html penalty 제거 + `host_api_subdomain`(0.40)·`cors_preflight`(0.30) 추가 + static penalty -0.60.
- 결과: API 호스트 0.82~1.00 / 웹 호스트 ≤0.27 (임계 0.70) 깨끗 분리. doc/08 §3·§4·§8, DECISIONS D15, TASKS 반영.

### 결과/다음
- 코드 변경 없음(설계·보정 단계). 다음: `ApiScorer` 구현(보정된 weight + host/CORS 신호) → Classifier 게이트 연동.
- 부하 주의: 실 Loki 탐색은 단일 쿼리 limit·짧은 창으로 제한. job-wide 라인필터 전구간 조회는 타임아웃/부하 → 금지.

## 2026-06-22 세션 2 — 참고 설계 평가 → 린(lean) 채택 결정

### 한 일
- 타 프로젝트(body·헤더 있는 환경) API Discovery 설계 문서를 검토.
- **무조건 채택 대신 비판적 평가**(장단점) 수행 → **린 채택**으로 결정.
  - 채택: 프로파일(high/middle/low/custom 임계), 중앙 API 전역·도메인 튜닝, 가용 신호만의 린 점수 모델.
  - 미채택(불가): Content-Type/Accept/AJAX/body(로그 부재) → `$type`+UA 클래스로 부분 대체.
  - 보류: endpoint decision cache, 참고의 정확한 가중치 값(보정 전 임의값).
  - 핵심 판단: 참고 설계의 판정력은 우리가 없는 강신호에서 나옴 → 점수화는 정보량 증가 없이 복잡도만 ↑.
- `doc/08` 을 "평가 → 린 채택 + 보류 + 보정 선행" 구조로 (재)작성. api_confidence vs shadow confidence 역할 분리 명시.
- 연결: doc/00 인덱스, doc/04 ApiScorer 게이트 전제, doc/07 튜닝 API. `DECISIONS.md` D15, `TASKS.md` 디스코프.

### 결과
- 코드 변경 없음(설계+계획). 가중치는 **잠정값**, **실데이터 보정이 선행 작업**.

### 다음 단계
- 적용 전 **가중치 보정**(샘플 라벨링) 선행. 이후 `ApiScorer`(린 점수 게이트)+프로파일+중앙 설정 API 순.

## 2026-06-22 세션 1 — 설계부터 수집·분석 파이프라인 구현까지

### 한 일
1. **개념·설계**: WAAP API Discovery 개념 정리, 주어진 nginx 로그로 가능/불가 항목 구분.
   문서 업로드 기반 Shadow/Zombie 탐지로 범위 확정. 설계 문서 `doc/00~07` 작성.
2. **스택 결정**: Java 21 + Spring Boot 3.3 (사내 표준), 상주 서비스 + Spring Batch + `@Scheduled`.
   MSA Worker 서비스로 중앙 서버와 REST 연동(Pull + 조건부 GET).
3. **스캐폴딩**: Gradle 프로젝트, 패키지 구조(api/ingest/parse/normalize/spec/match/classify/report/domain/batch/config).
4. **컴포넌트 구현(테스트 동반)**: OpenApiSpecParser → SpecStore → EndpointMatcher → Classifier →
   InventoryBuilder → ReportBuilder → DiscoveryJobService → LokiClient → watermark/dedup.
5. **실데이터 검증**: `sample/loki_sample.py` 기반으로 실 Loki(192.168.8.100:3200) 조회.
   로그가 **24필드**(문서 20 + geo/`0`/`request_id` 32hex)임을 확인, `$type` 필드(document/library) 발견.
6. **실 e2e 검증**: 우리 Java 파이프라인을 실 Loki에 직접 호출. `.js/.css`가 `$type=document`로 찍혀
   WEB_PAGE 오분류되는 버그 발견 → **확장자 판정을 `$type`보다 우선**하도록 수정.

### 결과
- 58 tests green (라이브 1건은 `-Dloki.live=true` 가드). 풀 빌드(bootJar) 성공.
- 수집(증분·dedup·부하보호) → 파싱 → 정규화/인벤토리 → 매칭 → 분류 → 리포트 → 영속 전 구간 동작.
- REST API 4종(도메인 CRUD, 스펙 업로드, 스캔상태/결과 조건부GET, hostname→domains/온디맨드 query).

### 환경 메모
- 이 분석 환경엔 JDK/Gradle 미설치였음 → JDK 21은 dnf로 설치, Gradle 8.10.2는 스크래치패드에 받아 사용.
  빌드: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build`.
- 실 Loki 라이브 테스트: `./gradlew test --tests "*LokiLiveIntegrationTest" -Dloki.live=true`.

### 다음 단계
- `doc/TASKS.md` 의 TODO 참고. 우선순위 후보: Postman/CSV 파서 실구현, 서비스 간 인증,
  운영 메트릭/off-peak, low_confidence/warnings 리포트 노출.
