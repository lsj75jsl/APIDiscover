# M7 — spec 멀티문서 관리 + API 상태추적 (ADDED/DELETED/UPDATED) 상세 설계

> ## ★ SUPERSEDED — [37-spec-inventory-reconcile](37-spec-inventory-reconcile.md) 로 대체 (M7 재설계)
> 본 문서의 **compute-on-read 문서버전 diff 모델**(현 active vs 직전 inactive `canonicalJson` 조회 시점 비교·`SpecDiffService`·`GET /spec/changes`)은 **사용자 의도와 불일치**로 폐기됐다. 사용자 의도 = **영속 API 인벤토리 + 업로드마다 문서별 reconcile**(파라미터 보유·ADDED/UPDATED/DELETED 를 현재 상태 속성으로 관리·DELETED 를 Zombie 입력으로). 재설계는 **[37-spec-inventory-reconcile](37-spec-inventory-reconcile.md) — 영속 API 인벤토리 + reconcile** 참조. **M7a 구현(`SpecDiffService`·`GET /spec/changes`)은 doc/37 §5 롤백 경계에 따라 이미 제거·이관됐다**(현 소스에 존재하지 않음). 본 문서는 이력 보존용(삭제 안 함).

> doc/35 P2(고위험) 상세화. 근거: [26](26-multi-spec-merge.md)(멀티스펙·specMergeStrategy·SpecStore)·[03](03-spec-formats-and-canonical-model.md)(canonical)·[28](28-testcontainers-pg-integration.md) §10·**DECISIONS D51**(rawDoc oid 교훈)·[35](35-rest-api-batch.md) M6(filename·projection). 근거 결정 **DECISIONS D52**.

## 0. 목적 / 현 구조 / 보류 맥락

사용자 요구(그대로): "PUT /spec — 문서는 여러 번 업데이트(동일 파일명이라도 업로드 일시 다르면 다르게 관리). 한 도메인 여러 문서 → API 목록 병합. 병합 시 **이전 문서엔 있고 신규에 없음=삭제, 이전에 없고 신규=추가, 파라미터 변경=update** 로 도메인별 API 상태정보 관리."

**현 구조(확인)**:
- `SpecRecord`: id·host·specName(기본 "default")·format·specVersion(**도메인별 monotonic**)·`rawDoc`(@Lob **oid**·유일)·`canonicalJson`(**text**·매칭 진실원)·warnings·endpointCount·uploadedAt·active·**filename**(P1 추가). 멀티 행.
- `upload(host,specName,content,filename)`: 파싱→canonical→영속. **재업로드 시 구 active 를 `active=false` 로 보존**(하드삭제 없음·prune 없음 — 확인). 모드(MERGE=같은 specName 비활성/SEPARATE=host 전체/VERSION_GROUPED).
- `CanonicalEndpoint = method·pathTemplate·host·deprecated·version·sourceRef`. **★param 미보유**(스펙 param 스키마 없음). `discovered_endpoint.params_json` 은 **관측(access-log) param** 이지 스펙 param 아님.
- **★D51/oid**: 비-tx `SpecRecord` 엔티티 로드 = rawDoc(oid) materialize → auto-commit 500. 메타는 `SpecMetaProjection`(rawDoc 미선택)으로. **M7 diff 도 canonicalJson 포함 projection(rawDoc 제외) 또는 @Transactional 로 읽는다.**

## 1. 멀티문서 버전관리 (M7.1)

- **filename→specName 도출**: 현재 `upload(host,content,filename)` 가 specName="default" 고정 → **filename 을 specName 으로 도출**(정규화: trim·소문자, 빈/null=`"default"`). → **서로 다른 filename = 서로 다른 문서**(병합 대상), **동일 filename 재업로드 = 그 문서의 새 버전**(MERGE 가 같은 specName 비활성→신버전 active). "동일 파일명+다른 업로드일시=별개 버전" 충족: (specName=filename, specVersion↑, uploadedAt) 튜플.
  - 하위호환: filename 미전달 업로드 = specName "default"(현행). 중앙이 멀티문서 원하면 filename 전송.
- **버전 보존(diff 기준)**: 재업로드가 구 버전을 `active=false` 로 **보존**(현행 그대로·하드삭제 없음) → diff 의 "직전" 기준 확보. **변경점 없음**(이미 보존).
- **병합**: 기존 `loadActiveCanonical`(active 집합 canonical 병합) + `specMergeStrategy` **정합 유지**(변경 없음). 멀티 active 문서(다른 specName) → merged canonical = 스캔/분류 입력.
- **버전 키**: (host, specName, specVersion). uploadedAt·filename 은 표시·식별 보조.

## 2. API 상태추적 — compute-on-read diff (M7.2)

### 2.1 비교 단위 — per-specName 버전 전이 (사용자 "이전 문서 vs 신규 문서")
- **단위 = 같은 specName 의 현 active 버전 vs 직전 버전**. 사용자 표현("이전 문서에 있고 신규 문서에 없음")과 정확히 일치(문서=specName).
- **"직전" 식별**: 같은 (host, specName) 의 `active=false` 중 **specVersion 최대**(= 최근 업로드가 막 비활성화한 버전). 직전 부재(최초 업로드)=전부 ADDED.
- **도메인 상태 = active specName 별 diff 의 집합**(specName 으로 그룹/태그). 명시 비교는 `?specName=&from=&to=`(specVersion).
- **멀티문서 겹침 주의(명시)**: per-document diff 라, "users" 에서 빠진 엔드포인트가 "orders" 엔 남아 있으면 — users diff 는 DELETED 로 보고하나 **merged 도메인엔 존재**. 단일문서/비겹침이 일반(정확), 겹침은 문서-레벨 변경으로 해석(응답에 specName 명시로 구분). (도메인-merged diff 대안은 "직전 merged 집합 재구성"이 복잡·모호 → per-document 채택.)

### 2.2 동일성 키 / status 규칙
- **동일성 = `method` + `path_template`**(canonical). host 는 도메인 spec 라 상수/agnostic(필요 시 tiebreak).
- `ADDED` = 현 버전에 있고 직전에 없음. `DELETED` = 직전에 있고 현 버전에 없음. `UPDATED` = 양쪽 존재 + **추적 속성 변경**(§3). `UNCHANGED` 는 미보고(노이즈 제거 — ADDED/DELETED/UPDATED 만).

### 2.3 compute-on-read (신규 테이블 0)
- 직전 canonical 이 **inactive 행 `canonicalJson`(text)에 이미 영속** → diff 는 **조회 시 계산**(저장 불요·stale 없음).
- **★oid 회피(D51)**: 신규 **canonicalJson 포함 projection**(`SpecCanonicalProjection(specName, specVersion, canonicalJson, uploadedAt, filename, active)` — **rawDoc 미선택**) 로 읽어 비-tx 안전. 두 버전 canonicalJson 역직렬화(List&lt;CanonicalEndpoint&gt;)→ method+pathTemplate 맵 → diff. 신규 repo 쿼리(아래) ORDER BY 에 nullable specName → **`nulls first` 명시**(h2-pg null 정렬 트랩, D48 동형).
- 신규 쿼리(projection): `findCanonicalByHostAndSpecNameOrderBySpecVersionDesc(host, specName)`(active+inactive, desc → [0]=현 active·[1]=직전), `findActiveSpecMetas`(기존, active specName 열거 재사용).

## 3. ★UPDATED 스코프 — (a) 린 vs (b) param 추출 (핵심 결정)

| 옵션 | UPDATED 검출 범위 | 비용 | 사용자 요구("파라미터 변경") 충족 |
|---|---|---|---|
| **(a) 린** | `deprecated` flip · `version` 변경 (canonical 보유 속성) | **0 스키마·즉시 가능** | **부분** — param 변경 미검출 |
| **(b) param 추출** | + query/path/body param 스키마 변경 | 파서·`CanonicalEndpoint`·canonicalJson 스키마 변경 + rawDoc 재파싱(또는 재업로드) = **대작업** | 충족 |

- **★권고 = 2단계 (M7a=(a) 먼저 / M7b=(b) 후속)**:
  - **M7a(지금)**: 멀티문서 버전관리 + **ADDED/DELETED 완전** + **UPDATED=(a) deprecated/version**. ADDED/DELETED 가 사용자 가치의 大半(엔드포인트 추가·삭제가 가장 흔한 spec 변경)이고 **0 스키마·compute-on-read** 로 즉시 가능.
  - **M7b(후속)**: canonical **param 추출**(파서가 OpenAPI/Postman param 스키마를 canonical 에 추출·영속) → **진짜 param-level UPDATED**. ★path param 변경(`{id}`→`{userId}`)은 template 변경=다른 키=ADDED+DELETED(UPDATED 아님) — query/body param 이 (b)의 본질. 사용자가 링크한 **access-log 파라미터 추출과 함께** 진행이 자연(둘 다 param 스키마 도입).
- **근거**: (a) 단독은 사용자의 "파라미터 변경=UPDATED" 를 **충족 못 함**(deprecated/version 만) → **(a)는 부분 이행**임을 응답·매뉴얼에 ★명시 필수. 그러나 (b)는 파서·스키마 대변경이라 같이 묶으면 M7 전체가 지연. **ADDED/DELETED 부터 신속 제공(M7a) + param-level 은 신중히(M7b)** 가 린·정직. **사용자 확인 포인트**: M7a(부분 UPDATED) 선출시 수용 여부 vs M7a+M7b 동시 대기.

## 4. 상태 노출 인터페이스

- **신규 `GET /api/v1/domains/{host}/spec/changes`**(별도 엔드포인트 — /spec 목록[M6]과 관심사 분리).
- 파라미터: 기본(무param)=active specName 별 현 vs 직전. `?specName=X`=X 한정. `?specName=X&from=2&to=5`=명시 버전 쌍(specVersion). `?status=ADDED,DELETED` 필터(선택).
- 응답 shape(샘플):
```jsonc
{
  "host": "api.example.com",
  "documents": [
    { "specName": "users", "filename": "users-api.yaml",
      "comparedVersion": 7, "previousVersion": 5,        // 직전 부재면 previousVersion=null(전부 ADDED)
      "comparedUploadedAt": "2026-06-29T10:00:00Z", "previousUploadedAt": "2026-06-20T09:00:00Z",
      "changes": [
        { "method":"POST", "pathTemplate":"/v2/orders", "status":"ADDED" },
        { "method":"GET",  "pathTemplate":"/v1/legacy", "status":"DELETED" },
        { "method":"GET",  "pathTemplate":"/v2/users/{id}", "status":"UPDATED",
          "changed":["deprecated"], "deprecated":{"from":false,"to":true} }
      ] }
  ],
  "updatedScope": "deprecated_version_only"   // ★(a) 한계 명시 — param-level 미포함(M7b 전까지)
}
```
- `updatedScope` 필드로 (a) 한계를 **응답 자체에 노출**(소비자 오해 방지). 매뉴얼에도 명시.

## 5. 스키마 / 중앙 영향

- **스키마 변경 = 0**(M7a). compute-on-read·canonicalJson projection·inactive 행 보존(이미 됨)·filename 컬럼(P1 기존). 신규 테이블/컬럼 없음 → **재배포 불요**(P1 filename 배포 후엔).
- **중앙 영향**: `GET /spec/changes` 신규(additive·영향 0). PUT /spec filename→specName 도출 = **멀티문서 거동 변화**(filename 다르면 별개 문서) — filename 미전달=현행 "default"(무회귀). 중앙이 멀티문서 원하면 filename 의도적 전송.
- **inactive 누적(정직)**: 재업로드마다 inactive 행 누적(prune 없음). spec 업로드는 희소(operator 행위)라 저volume·무해. 버전 보관 상한(specName 별 최근 N) prune 은 **후속**(diff 는 직전 1개만 필요하나 이력 보존은 가치) — 현재 무제한 보존.

## 6. 실 PG 테스트 계획 (Testcontainers podman)

- **★oid 가드(D51 필수)**: `GET /spec/changes` 경로가 **canonicalJson projection 으로만** 읽어 **비-tx(auto-commit)에서 oid materialize 없이** 동작함을 **실 PG**로 단언(H2 는 oid 트랩 미재현 → 실 PG 필수, `PostgresIntegrationTest` 패턴 재사용). projection 대신 엔티티 로드 회귀 시 RED.
- **h2-pg null 정렬 가드(D48)**: 신규 diff 쿼리 ORDER BY specName 에 `nulls first` 미명시 회귀 시 실 PG 에서 순서 발산 단언.
- **시나리오**: ① 단일 specName v1(EP A,B,C) → v2(B 삭제·D 추가·A deprecated=true) 업로드 → /spec/changes 에서 ADDED(D)·DELETED(B)·UPDATED(A, deprecated false→true) 단언, previousVersion=v1. ② 최초 업로드(직전 없음)=전부 ADDED·previousVersion=null. ③ 멀티문서(users+orders) → specName 별 분리 diff. ④ ?from=&to= 명시 버전. ⑤ param 만 다른 재업로드 → (a) 스코프선 UNCHANGED(미보고)·`updatedScope` 노출 확인(M7b 전 한계 회귀가드).

## 7. 단계 분할

- **M7a(우선, 저-중위험·0 스키마)**: filename→specName 도출 + 버전 보존(확인) + compute-on-read diff(ADDED/DELETED + deprecated/version UPDATED) + `GET /spec/changes` + projection·쿼리 + 실 PG 테스트. 독립 PR.
- **M7b(후속, 고위험·스키마)**: canonical param 추출(파서·`CanonicalEndpoint`·canonicalJson 스키마 + 기존 spec 재파싱/재업로드) → param-level UPDATED. **access-log 파라미터 추출과 묶음**. 별도 PR·별도 설계.

## 8. 무회귀 / 리스크 (정직)

- **무회귀**: M7a 가산(신규 엔드포인트·projection·쿼리). upload 의 filename→specName 도출은 **filename 미전달 시 "default"=현행**. 스캔/분류(loadActiveCanonical·merge) 불변. 스키마 0.
- **리스크①(★UPDATED (a) 부분 이행)**: param 변경 미검출 — 사용자 요구의 일부만. `updatedScope` 응답·매뉴얼 명시. M7b 로 완성. **사용자 확인 필수**.
- **리스크②(per-document vs merged 겹침)**: 다문서 겹침 시 DELETED 가 merged 엔 잔존 가능(§2.1). specName 명시로 구분.
- **리스크③(oid 트랩)**: diff 가 엔티티 로드하면 비-tx 500 — projection 강제(테스트 가드).
- **리스크④(inactive 누적)**: prune 없음 — 저volume 무해, 상한 prune 후속.
- **리스크⑤(SEPARATE 모드)**: host 전체 교체라 같은 specName 누적 안 됨 — per-specName diff 는 MERGE/VERSION_GROUPED 에 자연. SEPARATE 는 직전 active 집합 전체가 비활성 → 그 문서의 직전=교체 전 버전(있으면). SEPARATE 다문서 diff 의미는 약함(명시: SEPARATE=전체 교체 모델).

## 9. dev 구현 체크리스트 (TASKS subitem, D26 / P3 — doc/35 2단계)

**M7a (우선·0 스키마) — ★한때 구현됐으나 doc/37 재설계로 제거됨**(아래는 이력 기록 — 현 소스에 `SpecDiffService`·`/spec/changes` 없음)
- [x] `upload(host,content,filename)` specName 도출(filename→trim·소문자 specName, 미전달/빈=default) — 멀티문서 거동, 하위호환(filename 미전달=현행).
- [x] `domain/SpecCanonicalProjection`(specName·specVersion·canonicalJson·uploadedAt·filename·active, **rawDoc 미선택**) + `SpecRecordRepository.findCanonicalVersions`(JPQL 생성자식·`coalesce(specName,'default')` 매칭(레거시 null)·specVersion desc).
- [x] `spec/SpecDiffService`(compute-on-read: 현 active vs 직전 inactive canonicalJson 역직렬화·method+path_template 맵·ADDED/DELETED/UPDATED[deprecated/version], TreeMap 정렬 결정적) — **projection only(loadVersions 단일 지점, oid 회피)**.
- [x] `GET /api/v1/domains/{host}/spec/changes`(SpecController, 기본 active 전 specName·`?specName/from/to/status`·`updatedScope` 노출·host 정규화 null→400) + `spec/SpecChanges` DTO(api.dto↔spec 순환 회피로 spec 패키지).
- [x] 실 PG 테스트(§6: ADDED/DELETED/deprecated-UPDATED·최초=전ADDED·멀티문서 specName 분리·param-only 미보고+updatedScope·★oid 가드 RED-확인). null 정렬은 기존 `findActiveSpecMetas`(nulls first) 가드 재사용.
- [x] 매뉴얼(TW): /spec/changes + ★UPDATED (a) 한계(param 미포함) 명시. — `api-rest-manual.html` §2.4 반영(커밋 fd441db).

**M7b (후속·고위험·별도 PR)**
- [ ] canonical param 추출(파서·CanonicalEndpoint·canonicalJson 스키마·재파싱) → param-level UPDATED. access-log 파라미터 추출 묶음. **별도 설계**.

## 10. 범위 밖 / 후속

- **M7b param-level UPDATED** — canonical param 추출(대작업), access-log param 과 함께.
- **도메인-merged diff**(per-document 대신) — 직전 merged 재구성 복잡·모호로 미채택, 수요 시 재검토.
- **상태 영속(audit 테이블)** — compute-on-read 로 충족, 이력/감사 수요 시 후속.
- **inactive 버전 prune**(specName 별 최근 N) — 무제한 보존 현행, 대volume 시 후속.
