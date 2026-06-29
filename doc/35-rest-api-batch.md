# REST API 대규모 변경 배치 — 삭제·수정·신규 종합 설계 (사용자 요청)

> 브랜치 단계별 배정(매니저). 근거: doc/07(REST)·doc/26(멀티스펙·CombinedDiscovery)·doc/10·11(분류설정)·doc/34(rationale 재사용)·doc/30(DomainRegistrar). 근거 결정 **DECISIONS D50**.
> **진행 상태**: PR1(D1+M1+M3) 머지(#39). **PR2(spec-meta 노출: spec_record.filename + M2+M4+M6) 구현 완료**(브랜치 feat/api-batch-p1-specmeta, build green 472·커밋 보류). 나머지 P1(M5·A2)·P3(A1)=후속 PR, **P2(M7)=이번 보류**(§P2). 운영 Loki 미호출(정적/mock).
> 사용자 확정: **GET /domains/{host}=유지+보강**(삭제 아님), **/result rationale=조회시 재계산**(report_json·ETag·중앙계약 불변, /discovery 메커니즘 재사용).

## 0. 단계 분할(phase) — 위험·의존도 기준

| Phase | 항목 | 성격 | 스키마 | 재배포 |
|---|---|---|---|---|
| **P1** | D1·M1·M2·M3·M4·M5·M6·A2 + `spec_record.filename` | 읽기/additive·저위험 | filename 1컬럼(ADD) | 필요(컬럼) |
| **P2** | M7(멀티문서 관리 + API 상태추적 ADDED/DELETED/UPDATED) | 고위험·diff 로직 | **없음(compute-on-read 권장)** | 불요(P1 컬럼이면) |
| **P3** | A1(즉시 스캔, Loki 동기) | Loki 동기 호출 | 없음 | 불요 |

- **각 phase = 독립 PR**. P1 내 항목도 항목별 커밋 권장(리뷰·롤백).
- **★스키마 변경 = `spec_record.filename` 단 1건**(P1, ADD-only·ddl-auto update·기존행 null·데이터 무손실). **재배포 필요**. M7 상태추적은 **compute-on-read(신규 테이블 없음)** 권장(§M7).

## 1. 중앙연동·호환 영향 (★먼저 확인)

| 항목 | 영향 | 안전성 |
|---|---|---|
| **M1** GET /domains | body **배열 유지** + 페이지 정보 **헤더**(X-Total-Count/Pages·X-Current-Page) | **non-breaking**(사용자 확정) — body shape 불변, 헤더만 가산(§M1). |
| **M5** GET /result | body 에 `rationale` 필드 **가산** | additive-safe — 기존 report_json 필드 불변, 신규 필드만(중앙 파서 무영향). ETag=report version 유지. |
| **M3** PUT /domains | 미전달 항목 = **기존 유지**(전엔 clear) | 의미 변경 — 전 항목 전달 시 무회귀. "omit=clear" 의존 소비자만 영향(희소). |
| **D1** /hostnames/** | 엔드포인트 **제거** | Breaking — 해당 소비자 있으면 영향(현 자체용, 중앙 미사용 추정). |
| **M6** GET /spec | 응답 **단일 객체 → 배열**·404 폐지(무스펙=`[]`) | **★Breaking** — 단일 객체/404 의존 소비자 깨짐. 중앙/소비자 동시 갱신 또는 소비 여부 확인(§M6). |
| **A1·A2** | 신규 엔드포인트 | 영향 0(가산). |
| **M7** PUT /spec | `filename` 옵션 파라미터 가산·멀티문서 | additive(filename 미전달=현행 default). |

---

# P1 — 저위험·additive

## D1. HostQueryController 제거

- 제거: `GET /api/v1/hostnames/{hostname}/domains`, `POST /api/v1/hostnames/{hostname}/domains/{domain}/query` (컨트롤러 통째).
- **orphan 정리**: `DomainConfigRepository.findByHostname`(이 컨트롤러 전용 → orphan, 삭제). `ScanStatusView`/`SummaryView`(ScanController 도 사용 → **보존**), `DiscoveryJobService.runOnDemand`(CliScanRunner·A1 사용 → **보존**). 선존 dead code 미터치.
- 매뉴얼: `/hostnames` 절·링크 정리(technical_writer).

## M1. GET /api/v1/domains 페이지네이션 (★구현 = 배열 body + 헤더, 사용자 확정)

- **★사용자 확정 — body=JSON 배열 유지**(`List<DomainView>`, ≤1000건). 페이지 객체로 감싸지 않는다(기존 배열 소비자 무파괴). 페이지 정보는 **응답 헤더**로 노출.
- **헤더**: `X-Total-Count`(전체 도메인 수)·`X-Total-Pages`(전체 페이지 수)·`X-Current-Page`(현재 페이지, 0-based).
- **현 N+1**: `list()` 가 `repo.findAll()` 후 도메인마다 `specStore.activeMeta(host)` → 14k 도메인서 14k 쿼리. → **page 당 1000건**으로 page-bounded(≤1000회/page). batch spec meta 로드(host IN 1쿼리)는 SpecStore 배치 API 신설 필요 → **후속**(page 상한으로 폭주 이미 차단, ponytail 주석).
- 파라미터: `?page=`(0-based, 기본 0·음수→0)·`size`(기본 1000, [1,1000] clamp). `repo.findAll(PageRequest.of(page, size, Sort.by("host")))` — host asc 결정적 정렬.
- 응답: `ResponseEntity<List<DomainView>>` — body 는 기존 `DomainView` 배열 그대로(원소 shape 불변). page 초과 시 빈 배열 + 헤더.
- **★중앙영향**: body 배열 유지라 **non-breaking**(헤더는 가산). 페이지네이션 인지하는 소비자만 `?page` 순회·헤더 참조.
- (최초 설계안의 페이지 객체 `{content,totalPages,...}` 는 사용자가 배열+헤더로 변경 — breaking 회피.)

## M2. GET /domains/{host} 보강(유지)

- 추가 필드(additive, `DomainView` 확장 또는 신규 `DomainDetailView`):
  - `lastScanAt` — `scan_result.lastScanAt`(ScanResultRepository.findById(host)).
  - `latestSpec` — 최근 업로드 spec `{filename, uploadedAt, format, endpointCount, specVersion}`(activeMeta + **filename**).
  - `effectiveClassification` — 도메인 현재 설정`{profile, threshold, weightsSource, weights}`(resolver.resolve(host), doc/34 재사용).
- **★filename**: `spec_record` 에 원본 파일명 **미저장**(specName=논리 merge 키, 기본 "default"·파일명 아님). PUT /spec 도 `@RequestBody byte[]` 라 파일명 미수신. → **`spec_record.filename` 컬럼 추가(P1, ADD-only)** + PUT /spec 옵션 파라미터(아래 M7 와 공유). 기존행/미전달=null→표시 폴백(specName 또는 "—").
- DTO: `DomainView` 에 위 3필드 가산(또는 list 용 `DomainView` 와 분리해 상세 전용 `DomainDetailView`). list(M1)는 경량 유지·상세(M2)만 보강 권장.

## M3. PUT /domains/{host} 부분수정(PATCH 의미)

- **현 `apply()`=전체치환**: `enabled`(primitive boolean, 미전달→false)·`hostnames`(미전달→[]) 강제 덮어씀.
- 수정: `DomainUpsert` 필드 **nullable 화**(`Boolean enabled`·`List hostnames`·기타) → **present 항목만 적용**(미전달=기존 유지). 이미 `intervalOverride`·`specMergeStrategy`·`basePathStrip` 은 null-skip/직접대입 혼재 → **전 필드 null=skip 일관화**.
- **무회귀**: 전 항목 전달 시 현행과 동일. POST(create)는 현행 유지(전체 적용)·null→기본값.
- 한계: `enabled=false` 명시 전달은 정상 적용(미전달과 구분=Boolean nullable 의 핵심).

## M4. GET /scan-status 보강

- `ScanStatusView` 에 추가(additive): `latestSpec {filename, uploadedAt, specEndpointCount}` — activeMeta(active 1건)에서. `specEndpointCount`=그 문서의 `endpointCount`(spec 에서 추출한 API 수).
- 멀티문서면 active 최신 1건 기준(scan-status=경량 메타). 전체 목록은 M6.

## M5. GET /result 보강 — rationale(조회시 재계산)

- **현**: `ResponseEntity<String>`=report_json(스캔 스냅샷)+ETag(`"r.version"`). 304 조건부GET.
- **보강(사용자확정)**: serve-time 에 **report_json 파싱 → `rationale` 필드 가산 → 재직렬화**. report_json **기존 필드 불변**(중앙 additive-safe). `rationale`=**/discovery 와 동일 메커니즘**(`Classifier.classifyExplained` 현재 discovered+spec+effective 재계산, doc/34).
- **ETag**: `r.version`(report_json 버전) **유지** — rationale 는 ETag 입력 아님(serve-time, doc/34 일관).
- **★정직 caveat**: report_json findings=**스캔시점**, rationale=**현재 재계산**(현재 effective 설정 기준) → 둘이 다를 수 있음(doc/34 "현재설정 기준" 한계 동일). 또 조건부GET 304 시 캐시 body 의 rationale 는 갱신 안 됨(ETag 가 report 만 추적) — 신선 rationale 은 200 응답에서. 의도된 트레이드오프(ETag=스캔리포트 계약 보존).
- 샘플: report_json 객체 + `"rationale":[ {method,host,pathTemplate,classification,basis} ]`(doc/34 §2 shape).

## M6. GET /spec 복수 반환 (★구현 = flat `List<SpecMetaView>`)

- **현**: 단일 active `SpecMetaView`(404 if none). → 도메인 **spec 문서 목록** 반환(문서별 업로드 일시·파일명). 무스펙=빈 배열(200, 404 폐지).
- **★구현(PR2)**: 별도 wrapper(`SpecListView{host,mode,documents}`) 대신 **`SpecMetaView` 배열**을 직접 반환(린·DTO 추가 0). `SpecMetaView` 에 `specName`·`filename`·`active` 가산(additive — 단일 meta 소비처 DomainView.spec·upload 무회귀). `activeRecords(host)` → specName asc·specVersion asc 정렬(결정적).
- 응답(구현):
```jsonc
[ { "format":"OPENAPI", "specVersion":42, "endpointCount":18, "uploadedAt":"2026-06-29T...",
    "specName":"users", "filename":"users-api.yaml", "active":true } ]
```
- 데이터: 기본=active 목록(`activeRecords`). 버전 이력 옵션 `?history=true`(inactive 포함)는 **후속**(신규 repo 쿼리 필요·이번 미구현). spec_record 멀티(id/specName/specVersion/active/uploadedAt) + filename(PR2) → 목록화.

## A2. 가중치 편집 API (profile 자동 CUSTOM)

- 현 PUT /classification=**전체 교체**. A2=**부분 weight 편집**(1키 가능) + **profile→CUSTOM 자동**.
- 경로: `PATCH /api/v1/domains/{host}/classification/weights`(도메인) + `PATCH /api/v1/classification/weights`(전역). body=`{ "apiSegment":0.7, "query":0.2 }`(부분 weight map, key=`WEIGHT_KEYS` 14중).
- 동작(★"나머지 현 effective 유지"):
  1. 현재 effective weights 스냅샷(resolver.resolve→eff.weights(), 14개 전체) — preset(HIGH 등)이면 그 값 보존.
  2. `profile=CUSTOM` 자동 설정.
  3. `customWeights` = 스냅샷 14개 ∪ 요청 부분(요청 키 override) → 편집 안 한 키는 **현 effective 값 유지**(MIDDLE 베이스로 리셋 방지).
  4. `validateWeightOverrides`(unknown 키·비유한 reject)→400, 저장, `resolver.invalidate`.
- 재사용: `applyOverrides`·`WEIGHT_KEYS`·resolver·`DomainClassificationConfig.customWeightsJson`. **무회귀**: 기존 PUT /classification 불변(A2=신규 PATCH 가산).
- 응답: `DomainClassificationView`(override+effective, 기존 DTO 재사용).
- 한계: threshold·matcher 는 A2 범위 밖(weights 만) — 그건 기존 PUT /classification.

---

# P2 — M7 멀티문서 관리 + API 상태추적 (고위험·★최대)

> **★이번 보류(사용자 확정)** — M7(멀티문서 관리 + ADDED/DELETED/UPDATED 상태추적)은 추후 진행. access-log 파라미터 추출(canonical query/param 강화)과 함께 다루는 것이 자연스러움(UPDATED param-level diff 가 canonical 강화에 의존, §M7.2 한계). P1 머지 후 별도 PR.

## M7.1 멀티문서 업로드 관리

- `PUT /api/v1/domains/{host}/spec` 에 **옵션 `?filename=`**(또는 헤더) 추가. filename → `spec_record.filename` 저장 + **specName 도출**(filename 제공 시 specName=filename, 미제공=현행 "default").
- **버전 관리**: 동일 filename 재업로드 = 그 specName 의 **새 specVersion**(기존 동일-specName active 비활성, 현 `SpecStore.upload` MERGE 로직 그대로). 다른 filename = **별개 문서**(병합 대상). → "파일명+업로드일시 다르면 별개 버전" = (specName=filename, specVersion↑, uploadedAt) 튜플로 자연 충족.
- 병합: 기존 `specMergeStrategy`(MERGE/SEPARATE/VERSION_GROUPED)·`SpecStore`·`loadActiveCanonical` 정합(변경 없음). 멀티 active 문서 → merged canonical.

## M7.2 API 상태추적 (ADDED / DELETED / UPDATED) — ★compute-on-read 권장

- **저장 위치 결정 — 계산형(compute-on-read) 권장, 신규 테이블 없음**:
  - 근거: 직전 버전 canonical 이 **이미 영속**(업로드가 prev active 를 `active=false` 로만 두고 canonicalJson 보존). diff=현재 active 집합 vs 직전 집합 → **저장 불요·stale 없음·이식 부담 0**.
  - 비교 기준: **specName 별 version N(현 active) vs N-1(직전, inactive 행)**. 도메인 레벨=specName 별 diff 의 합(merged 관점). 직전 부재(최초 업로드)=전부 ADDED.
  - 대안(영속): 신규 테이블 `spec_api_change`(host·method·path_template·status·specVersion·detectedAt). **감사 로그/이력 필요 시에만**(스키마+stale). 1차 미채택(YAGNI) — 필요 시 후속.
- **동일성 키**: `method + path_template`(canonical). (host 는 도메인 spec 이라 동일/agnostic.)
- **status 규칙**:
  - `ADDED` = 현 집합에 있고 직전에 없음.
  - `DELETED` = 직전에 있고 현 집합에 없음(직전 inactive canonical 에서 읽어 표시).
  - `UPDATED` = 동일 키, **속성 변경**.
- **★UPDATED 의 구조적 한계(정직·중요)**: `CanonicalEndpoint` 는 `method·pathTemplate·host·deprecated·version·sourceRef` 만 보유 — **query param·request/response 스키마 미보유**(doc/03, canonical 은 query param 정의 없음). → "파라미터 변경" 의 일반적 의미(query/body param diff)는 **canonical 에서 검출 불가**. path param 변경(`{id}`→`{userId}`)은 template 변경=다른 키=ADDED+DELETED(UPDATED 아님). → **UPDATED 검출 범위 = `deprecated`/`version`/`sourceRef` 변경**으로 한정. 진짜 param-level diff 는 **canonical 강화(파서·스키마 저장 = 더 큰 변경)** 필요 → 범위 밖(§범위밖). 이 한계를 응답·매뉴얼에 명시.
- 노출: GET /spec(M6) 또는 신규 `GET /api/v1/domains/{host}/api-status` 에 status 동봉:
```jsonc
{ "host":"api.example.com", "comparedVersion":42, "previousVersion":41,
  "changes":[
    { "method":"POST","pathTemplate":"/v2/orders","status":"ADDED" },
    { "method":"GET","pathTemplate":"/v1/legacy","status":"DELETED" },
    { "method":"GET","pathTemplate":"/v2/users/{id}","status":"UPDATED",
      "changed":["deprecated"],"deprecated":{"from":false,"to":true} } ] }
```
- **재계산 비용**: canonical 2집합(현·직전) 로드+diff=경량. ETag 무관(별도 조회).

---

# P3 — A1 즉시 스캔 API (Loki 동기)

## A1. 즉시 스캔

- 미스캔/미등록 도메인을 **즉시 동기 스캔**해 API 목록 반환. DB 미존재면 **자동 등록 후 스캔**.
- 경로: **`POST /api/v1/domains/{host}/scan-now`**(동기·결과 반환). 기존 `POST .../scan`(비동기 202, `runScan` 스케줄 트리거)와 **명확 구분**.
- 동작: `requireNormalizedHost` → `DomainRegistrar.registerIfAbsent(host)`(미등록 자동등록, CLI `-scan` 자동등록과 일관·discoveredAt=null) → `DiscoveryJobService.scanOnDemand(host, window, null)`(**watermark 미전진**, doc/33 §7) → `CombinedDiscoveryService.forHost(host)` 로 findings+rationale 반환(/discovery 일관).
- 파라미터: `?window=PT1H`(기본=`scan.max-window` 상한). **Loki 동기 호출** — LokiClient 부하보호(slice·throttle·동시·백오프·LokiBudget) 준수, window 상한으로 폭주 차단(doc/33 PR1.1 재사용).
- 응답: `CombinedDiscovery`(findings+rationale+effectiveClassification, doc/34) 또는 경량 요약+findings. **rationale 동봉 권장**(즉시스캔=판단근거 확인 수요 큼).
- **★주의**: 동기 Loki 호출이라 응답 지연 가능(busy 도메인). max-window 작게·타임아웃 명시. 비동기 트리거가 필요하면 기존 `POST .../scan` 사용 안내.

---

## W1. 매뉴얼 §2.5 /classification 의미 정리 (technical_writer 편집)

architect 가 의미만 정리(아래), TW 가 §2.5 편집:
- **thresholdOverride** = 점수 게이트 **임계 교체**(Shadow admit 기준). 전 프로파일에서 가능, **[0,1] 유한**(`validateThreshold`). 미지정=preset 임계(MIDDLE 0.70 등).
- **customWeights** = 14신호 **가중치 override map**(key=`WEIGHT_KEYS`). **profile=CUSTOM 일 때만 적용**(MIDDLE 베이스+override, `applyOverrides`). HIGH/MIDDLE/LOW 면 검증만 하고 미적용. 비유한·unknown 키 → 400.
- **matcher**(`MatcherConfig`) = api 힌트(`apiPathPrefixes`/`apiPathRegexes`=강제 API admit)·exclude(`excludePathPrefixes`/`Regexes`=강제 제외)·`includeWebForms`(web-form 억제 on/off)·`optionsOperationPrefixes`. 힌트 설정 시 **explicit-hint 모드**(내장 path-shape 비활성, pathHint 만, doc/09). 전역∪도메인 병합.
- 적용 우선순위: 도메인 override > 전역 > preset(doc/11). A2(PATCH weights)=profile 자동 CUSTOM.

---

## 무회귀 / 리스크 (정직)

- **무회귀**: P1 대부분 additive(신규 필드/엔드포인트). M3 는 전 항목 전달 시 동일. M5 report_json/ETag 불변. `spec_record.filename` ddl-auto ADD·기존행 null. A2 신규 PATCH(기존 PUT 불변).
- **리스크①(M1 Breaking)**: 배열→페이지 객체. 중앙 소비 여부 확인 후 동시 갱신(매니저).
- **리스크②(M7 UPDATED 한계)**: canonical 에 query param 없음 → UPDATED=deprecated/version 한정. param-level diff 는 canonical 강화 필요(범위 밖). 응답·매뉴얼 명시 필수.
- **리스크③(M5 스냅샷 vs 현재)**: rationale=현재 재계산, findings=스캔시점 → 불일치 가능(doc/34 동일 caveat). 304 시 rationale 미갱신.
- **리스크④(A1 동기 Loki)**: 응답 지연·부하 — window 상한·부하보호·타임아웃. 운영 대용량은 비동기 `/scan` 안내.
- **리스크⑤(M2/M6 filename 폴백)**: 기존 spec(filename=null)·legacy 업로드 → 표시 폴백("—"/specName). 중앙이 filename 전송하도록 PUT /spec 갱신 필요(M7).
- HA 단일 인스턴스 전제(기존).

## dev 구현 체크리스트 (TASKS subitem, D26 / P 버킷)

**P1 (저위험·additive)** — ★PR1(도메인 엔드포인트 D1+M1+M3) 구현 완료(브랜치 feat/api-batch-p1-domain, build green 464·커밋 보류). 나머지(filename·M2·M4·M5·M6·A2)는 후속 PR.
- [x] D1 `HostQueryController` 제거(+`GET /hostnames/{}/domains`·`POST .../query`) + `findByHostname` orphan 정리(전용·타 사용처 0 확인 후 삭제). 매뉴얼 `/hostnames` 절=TW 후속.
- [x] M1 GET /domains 페이지네이션 — ★body=JSON 배열 유지 + 헤더(X-Total-Count/Pages·X-Current-Page), page 0-based·size [1,1000] clamp·host asc. N+1 은 page-bounded(≤1000/page) 완화 + batch meta 로드 후속(ponytail 주석). non-breaking.
- [x] **PR2** `spec_record.filename`(ADD-only·ddl-auto·기존행 null) + `SpecRecord` 접근자 + PUT /spec 옵션 `?filename=`(SpecStore.upload 오버로드, M2/M6/M7 공유). ★머지 후 재배포 필요(컬럼).
- [x] **PR2** M2 GET /domains/{host} 보강(`DomainDetailView`: `lastScanAt`·spec`{filename,uploadedAt,...}`·`effectiveClassification`). ★목록(M1)은 경량 `DomainView` 유지=성능 회귀 방지(단건만 scan/resolver 조회). effective 빌더=`EffectiveClassification.toView()` 공유(/discovery 와 중복 제거).
- [x] M3 PUT /domains 부분수정(`DomainUpsert` enabled Boolean nullable·present-only apply, []=비우기·null=유지, 전항목=무회귀·create 무회귀).
- [x] **PR2** M4 GET /scan-status 보강(`latestSpec`=SpecMetaView{filename·uploadedAt·endpointCount(추출 API수)} 재사용, 없으면 null).
- [ ] M5 GET /result rationale 가산(serve-time, report_json/ETag 불변, classifyExplained 재사용) + 중앙 additive-safe 검증. — 후속 PR
- [x] **PR2** M6 GET /spec 목록 — flat `List<SpecMetaView>`(specName·filename·active 가산, activeRecords·specName/version 정렬, 무스펙=[]). `?history` 옵션은 후속.
- [ ] A2 PATCH /classification/weights(도메인·전역, 현 effective 스냅샷+부분 override+profile CUSTOM, applyOverrides 재사용).
- [ ] 테스트(각 항목 + 무회귀: M3 전항목 동일·M5 report_json 불변·페이지네이션 경계).

**P2 (M7, 고위험) — ★이번 보류**(추후 access-log 파라미터 추출과 함께, §P2)
- [ ] M7.1 멀티문서 업로드(filename→specName 도출·버전관리·기존 merge 정합).
- [ ] M7.2 API 상태추적(compute-on-read diff: specName N vs N-1, method+path_template 키, ADDED/DELETED/UPDATED[deprecated·version 한정]) + 노출(GET /spec 또는 /api-status). ★UPDATED 한계 명시.
- [ ] 테스트(ADDED/DELETED/UPDATED·최초업로드 전부 ADDED·param-diff 범위밖 확인).

**P3 (A1)**
- [ ] A1 POST /domains/{host}/scan-now(registerIfAbsent+scanOnDemand+forHost findings/rationale, window 상한·부하보호, 비동기 /scan 과 구분).
- [ ] 테스트(미등록 자동등록·watermark 미전진·findings 반환, 운영 Loki 단위 mock).

## 범위 밖 / 후속

- **canonical query-param 강화**(M7 UPDATED param-level diff) — 파서·스키마 저장 큰 변경, 별도.
- **M7 상태 영속(audit 테이블)** — compute-on-read 로 충족, 이력/감사 수요 시 후속.
- **M1 중앙 소비자 호환 레이어**(배열 유지 옵션) — 필요 시.
- **A1 비동기화/큐잉** — 동기 1차, 대량 수요 시 후속.
