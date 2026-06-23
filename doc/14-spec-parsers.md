# 스펙 파서 Postman/CSV 실구현 (설계)

> 브랜치 `feature/spec-parsers-postman-csv`. OpenApiSpecParser 가 참조 impl·Canonical 출력 기준.
> 신규 의존성 0(Jackson·univocity-parsers 기존). 근거 결정은 doc/DECISIONS.md **D21**. 연계: doc/03(스펙·Canonical).
> **범위 밖**: 매처 캐시 무효화(별도 항목), 멀티 스펙 병합(후속), 구조화 spec_source.warnings 채널(리포트 항목).

## 0. 현 상태 / 가용

- `SpecParser.parse(byte[])→List<CanonicalEndpoint>`, 실패 시 throw. `SpecStore` 가 `Map<SpecFormat,SpecParser>` 디스패치,
  parse 결과 empty → IllegalArgumentException("no endpoints", 400). **warnings 반환 채널 없음**(List 만 반환, SpecRecord 에 warnings 필드 없음).
- `CanonicalEndpoint(method, pathTemplate, host, deprecated, version, sourceRef)`.
- OpenApiSpecParser(참조): method 대문자, joinPath(선행 `/`·후행 strip·`//` collapse), host 소문자/null, sourceRef `openapi#...`.
  `:var`/`{{var}}` 변환은 OpenAPI 에 불필요(이미 `{}`).
- 의존성: `univocity-parsers 2.9.1`(CSV), Jackson(Postman JSON 트리) — 둘 다 존재.
- 두 파서는 스캐폴드(`throw UnsupportedOperationException`).

## 0.1 공통 — Canonical 동일성을 위한 공유 정규화 (신규 util)

3종 동일성(§5)의 핵심은 template/host 정규화가 포맷 간 **동일**해야 함.
- `spec/SpecNormalize`:
  - `template(raw)`: 세그먼트별 `:seg→{seg}`, `{{x}}→{x}`(정규식 `\{\{\s*([\w.-]+)\s*\}\}`), 이미 `{x}` 유지.
    선행 `/` 보장·`//` collapse·후행 `/` strip(루트 제외). (doc/03 §1.1)
  - `host(raw)`: trim, 빈값→null, 아니면 소문자.
- `spec/SpecCanonicalizer.canonicalize(list)`: (method,host,template) **dedupe + deprecated OR 결합 + 안정 정렬**(doc/03 §6).
  **SpecStore.upload 의 parse 직후 적용**(3종 일괄·균일, 정렬로 ETag 결정성·동일성 비교 용이). OpenAPI 포함 전 포맷 균일.

## 1. PostmanSpecParser (Jackson 트리)

- ObjectMapper 주입(신규 의존성 X). `readTree` → 루트 object·`item` 배열 검증, 아니면 **IllegalArgumentException**(fatal→400).
- **item 트리 DFS**: 노드에 `request` 있으면 leaf(엔드포인트), `item` 있으면 폴더(재귀). 폴더 name·deprecated 를 자식에 전파.
- leaf 추출:
  - method = `request.method` 대문자. 없으면 **skip + log.warn**.
  - url: object(`path`/`host`/`raw`) 또는 string 모두 처리. object: `path` 배열→`/` join / 문자열→그대로, 없으면 `raw` 에서 경로 추출.
    string: raw URL 에서 scheme://host·`?query` 제거 후 경로.
  - path 변수 `:id`/`{{id}}`→`{id}`(SpecNormalize). query 는 Canonical 제외(method+path+host 만).
  - host: `host` 배열 `.` join. `{{baseUrl}}` 등 변수 host 는 collection `variable[]` 치환 시도, 실패 시 **null**(host-agnostic).
    (path 변수=파라미터 vs host 변수=환경값 구분.)
  - deprecated(doc/03 §3.3, OR): 상위폴더/item `name` 에 `[DEPRECATED]`/`(deprecated)`(대소문자 무시) OR `request.description` 에 deprecated 키워드.
  - version = `info.version`(없으면 null), 전 엔드포인트 공통.
  - sourceRef = `"postman#" + 이름경로`(예 `postman#FolderA/Get User`).
- 엣지: 상대 url→host=null, host-only→path `/`, 중복→SpecCanonicalizer 병합, 폴더만(leaf 없음)→정상(경고 안 함).

## 2. CsvSpecParser (univocity)

- univocity `CsvParser`(header 추출 ON, UTF-8, `,`, 따옴표/이스케이프/내장 개행 자동, **BOM strip**).
- **헤더 검증**: 필수 `method`,`path`(대소문자·trim 무시) 없으면 **IllegalArgumentException**(fatal). 선택: host/deprecated/version/description. 여분 무시.
- 행→endpoint:
  - method(필수, 대문자)·path(필수) 빈값 → **skip + log.warn(row n)**.
  - host: 빈값→null, 아니면 SpecNormalize.host.
  - deprecated 파싱: `true/false/1/0/y/n/yes/no`(대소문자 무시), 빈값→false, 미인식→warn+false.
  - version: 빈값→null. path: SpecNormalize.template(`:id`→`{id}`).
  - sourceRef = `"csv#row" + n`.
- 엣지: header-only(0행)→빈 리스트→SpecStore "no endpoints" 400. 행 컬럼수 부족→null→빈값 처리.

## 3. SpecFormatDetector 라우팅

현재 라우팅 정확(CSV→OpenAPI→Postman, fallback `item`+`info`). **필수 변경 없음.**
- (선택 견고화) Postman 스키마 host 매칭 `getpostman.com` **또는 `schema.postman.com`** 확장(신버전 컬렉션). CSV·OpenAPI 무영향.

## 4. 오류 처리 / warnings 연계 (린 결정)

- **fatal → IllegalArgumentException**(SpecStore→중앙 400): 손상 JSON, Postman `item` 부재, CSV 필수 헤더 누락, 유효 0(기존 처리).
- **recoverable → skip + `log.warn`**(유효분만 반환, doc/03 §6): CSV method/path 누락·deprecated 미인식, Postman leaf method/url 누락.
- **구조화 `spec_source.warnings` 채널은 범위 밖**(별도 TASKS "리포트/출력: spec_source.warnings 반영"). 이번엔 로그 경고 + fatal 예외로
  "명확한 예외/경고" 충족. 향후 채널 필요 시 `SpecParser.parse→SpecParseResult(endpoints, warnings)` 확장 **seam** 만 명시
  (인터페이스 변경은 그 항목). → 파서 PR 을 작게(OpenApiSpecParser/SpecRecord 무변경).

## 5. 3종 포맷 Canonical 동일성

동일 논리 스펙(예: 4 endpoint, deprecated 1·host 지정·`{id}`)을 OpenAPI/Postman/CSV 로 표현 → 각 파서 + SpecCanonicalizer 후
**(method, host, pathTemplate, deprecated, version) 동일** 검증(`sourceRef` 는 포맷별 provenance → 비교 제외).
공유 SpecNormalize 가 template/host 동일화, SpecCanonicalizer 안정정렬로 순서 무관 비교. → 품질 TASKS "3종 동일성 테스트" 충족.
- 전제: OpenAPI server basePath(`/v2`)에 대응해 Postman path 배열·CSV path 에 prefix 포함(doc/03 예시 일치). 테스트 데이터 명시.

## 6. dev 구현 체크리스트 (11건)

### 공유(신규)
- [ ] `spec/SpecNormalize` — `template(raw)`(`:var`/`{{var}}`→`{var}`·슬래시 규칙)·`host(raw)`. Postman/CSV 공용(가능하면 OpenApi 슬래시 위임).
- [ ] `spec/SpecCanonicalizer` — `canonicalize(list)`: dedupe(method,host,template)+deprecated OR+안정정렬. SpecStore.upload parse 직후 적용.

### 파서(수정)
- [ ] `spec/PostmanSpecParser` 구현 — ObjectMapper 주입, item DFS(폴더 deprecated 전파), url object/string, path 변수 변환,
      host 변수→null, `[DEPRECATED]`/`(deprecated)`/description, sourceRef 이름경로, method/url 누락 skip+warn.
- [ ] `spec/CsvSpecParser` 구현 — univocity header 추출·필수 헤더 검증, 행→endpoint, deprecated 파싱, `:var`→`{var}`, 따옴표/BOM, 불량행 skip+warn.
- [ ] `spec/SpecStore` — parse 결과에 `SpecCanonicalizer.canonicalize` 적용(전 포맷 균일).
- [ ] (선택) `spec/SpecFormatDetector` — Postman 스키마 host `schema.postman.com` 추가(무회귀 확인).

### 테스트
- [ ] `PostmanSpecParserTest` — 중첩 DFS, url 배열/문자열, `:var`/`{{var}}`→`{var}`, host 배열·`{{baseUrl}}`→null, `[DEPRECATED]` 이름/폴더상속/description, method 누락 skip, sourceRef.
- [ ] `CsvSpecParserTest` — 필수 헤더 누락→예외, 행 변환, deprecated 전 표기, `:var`→`{var}`, 따옴표·내장콤마·BOM, host 빈값→null, header-only→빈, 불량행 skip.
- [ ] `SpecFormatDetectorTest` — Postman(스키마 변형)·CSV·OpenAPI 라우팅, 미지원→예외(무회귀).
- [ ] `SpecCanonicalizerTest` — dedupe·deprecated OR·안정 정렬.
- [ ] `ThreeFormatEquivalenceTest` — 동일 논리 스펙 3종 → (method,host,template,deprecated,version) 동일(sourceRef 제외). 품질 항목 충족.

## 7. 범위 밖 / 후속

- 구조화 `spec_source.warnings` 채널(리포트 항목, seam=`SpecParseResult`).
- 매처 캐시 무효화(SpecStore 업로드 시 `(host,specVersion)` evict — 별도 항목).
- 멀티 스펙 업로드(병합) — 후속.
