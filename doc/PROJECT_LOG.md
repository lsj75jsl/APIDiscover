# PROJECT LOG — 작업 내역 (세션 메모리)

> 새 세션 시작 시 참고. 최신 항목이 위로 오도록 역순 기록.
> 형식: `## YYYY-MM-DD 세션 N — 제목` + 한 일/결과/다음 단계.

---

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
