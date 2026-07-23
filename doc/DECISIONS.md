# DECISIONS — 의사결정 기록 (세션 메모리)

> 새 세션 시작 시 참고. 프로젝트 진행 중 내린 주요 결정과 근거.
> 결정이 바뀌면 항목을 갱신하고 사유를 남긴다. 설계 상세는 `doc/00~07`.

---

### D1. 범위 — 문서 업로드 기반 Shadow/Zombie 탐지
주어진 nginx 로그(body/헤더 없음)로는 엔드포인트 인벤토리 + Shadow/Zombie 까지 가능.
스키마/파라미터 타입/인증/응답 PII 는 범위 밖. **Shadow = `D \ S`(트래픽엔 있고 문서엔 없음)**,
**Zombie = `S_deprecated ∩ D`(문서 deprecated 인데 트래픽 지속)**. 관찰측·문서측 2-pass 분류.

### D2. 대표 문서 포맷 = OpenAPI + Postman + CSV
REST 명세 사실상 표준 OpenAPI, 협업 표준 Postman, 단순 입력 CSV(스키마 자체 정의).
Postman 은 표준 deprecated 필드가 없어 `[DEPRECATED]` 이름/폴더 규약 정의.

### D3. 경로 정규화 = 스펙 우선 → 휴리스틱 → 통계 보정
스펙이 있으면 스펙 템플릿 채택(추론오류 0). 없으면 휴리스틱(uuid/id/token/date), 그래도 안 되면
통계 클러스터링(과병합 위험 → 신뢰도 감산·`inferred` 표기). 통계 보정은 미구현(TODO).

### D4. endpoint_kind 신호 = `$type` 1순위(확장자 우선), referer 보조
실데이터에서 nginx `$type`(document/library) 발견 → web_page vs static 직접 분류.
단 `.js/.css`가 `$type=document`로 찍히는 경우가 있어 **정적 확장자 판정을 `$type`보다 우선**.
`$type=document`는 서버 렌더링 페이지 기본값 성격 → web_page 약한 신호로만. referer 보조는 TODO.
**static 으로 판정된 미문서 경로는 Shadow 로 보고하지 않음**(오탐 방지).

### D5. 404-only 실재성 필터
존재하지 않는 경로 탐침은 거의 404만 반환 → Shadow 신뢰도 -0.7. 현재 Classifier 신뢰도에 반영,
인벤토리 단 사전 필터는 TODO.

### D6. 로그 소스 = 사내 Loki, 주기 배치 (실시간 불필요)
LogQL: `{job="access_log", hostname=<엣지서버>} |= ` <domain>``. job 라벨로 축소 + 도메인 라인필터,
정밀 파싱은 LogLineParser 단일 진실원. 접속값은 `sample/loki_sample.py` 기준(인증 없음).

### D7. 운영 부하 보호 (필수)
대상 Loki 가 운영 중 → 샘플의 `limit=1e8`·무 페이지네이션·전 hostname 동시발사 **금지**.
LokiClient 에 chunk-window 분할 + 유한 page-limit 페이지네이션 + 동시성 세마포어 + 스로틀 +
429/5xx 지수 백오프 적용.

### D8. 구현 스택 = Java 21 + Spring Boot 3.3 (사내 표준)
배포는 k8s CronJob(매 회차 cold start) 대신 **상주 Spring Boot + Spring Batch + @Scheduled/Quartz**.
HA 시 ShedLock/Quartz 클러스터로 단일 실행 보장. 라이브러리: swagger-parser, Jackson, DataSketches,
univocity-csv. 데이터모델 record, 분류 sealed interface+패턴매칭.

### D9. MSA = Worker 서비스 + 중앙 서버, Pull + 조건부 GET
도메인 설정은 중앙→Worker push(REST, DB 영속). 결과 동기화는 중앙 주도 Pull:
경량 `scan-status`(lastScanAt/version) 확인 후 변경 시에만 `result` 조회, `If-None-Match`로 `304`.
**ETag(version)은 generatedAt/window 제외한 내용(specVersion+summary+findings) 기반** → 동일 결과 동일 버전.

### D10. Spec Store — 업로드 시 1회 파싱, Canonical 영속, 스캔 시 재사용
고객→중앙→`PUT /domains/{host}/spec`. 업로드 시점에 파싱·검증(무효는 400) 후 새 specVersion 으로
원본+Canonical 영속. 스캔은 활성 Canonical 로드(재파싱 없음). 매처 캐시 무효화는 TODO.

### D11. 내부 DB = PostgreSQL(운영) / H2(개발), 신규 DB 불필요
이미 Spring Data JPA + H2/PostgreSQL 채택. DomainConfig(host↔hostnames), SpecRecord, ScanResult,
Watermark. host↔domain 역방향 조회(`findByHostname`) + API 제공. PostgreSQL 사유: 사내표준·JSONB·
트랜잭션·생태계.

### D12. 설정 분리 = 정적(config) vs 동적(DB)
정적(Loki 접속·인터벌·부하보호값·ingest_lag·backfill) → `application.yml`/ConfigMap +
`@ConfigurationProperties`. 동적(대상 도메인·hostnames·스펙·인터벌 override) → 중앙 API → DB.

### D13. 실로그 필드 24개 + request_id dedup
실로그는 문서 20필드 + [geo, `0`, `0`, request_id(32hex)] = 24. 파서는 [0]~[16] 읽고
type(idx19)·referer(idx13)·request_id(idx23) 추가 수집(20필드 로그 호환). **request_id 가 dedup 1순위 키**
(id 있을 때만 dedup, 없으면 보존). watermark 증분으로 신규 구간만 조회.

### D15. API 점수화 모델/프로파일 — **평가 후 린(lean) 채택** (doc/08)
타 프로젝트 설계를 **무조건 복제하지 않고 평가**한 결과, 우리 제약(body·헤더 없음)에서 실익 있는 부분만 채택.
- **채택**: high/middle/low/custom 프로파일(임계 preset), 중앙 API 전역·도메인 튜닝, **린 점수 모델**(가용 신호만).
- **미채택(불가)**: Content-Type/Accept/AJAX/body 신호 — 로그 부재. → `$type`·user-agent 클래스로 부분 대체.
- **보류**: endpoint decision cache(배치 구조라 이득 작음), 참고의 정확한 가중치 값(보정 전 임의값).
- **이유**: 참고 설계의 판정력은 대부분 우리가 없는 강신호에서 나옴. 남는 신호는 기존 endpoint_kind+path 휴리스틱과
  거의 동일 → 점수화는 정보량을 늘리지 않고 유연성·복잡도만 늘림. false precision 경계.
- **완화**: 신호를 가용분만 린하게, api_confidence(후보성) vs shadow/zombie confidence(실재성) **역할 분리**(08 §6),
  가중치는 **잠정값**으로 표기하고 **실데이터 보정을 선행**(08 §8).
- status·WAF tag 는 점수 신호 아님(metadata) — D1 과 일치. 반영: doc/08, 04 전제, 07 §3.1/§4. 계획: TASKS.md.
- **보정 완료(2026-06-22, 08 §8)**: api.weble.net(API) vs dreampark(웹) 실데이터로 보정.
  ① **`$type=document` html penalty 제거** — JSON API 응답에도 document 가 붙어 진짜 API를 0점화(100% 미탐).
  ② **`host_api_subdomain`(0.40)·`cors_preflight`(OPTIONS, 0.30) 추가** — access log 기반 최강 API 양성 신호.
  ③ static penalty 강화(-0.60). 결과: API 호스트 0.82~1.00 / 웹 호스트 ≤0.27 로 깨끗이 분리(임계 0.70).
  ④ 한계: api 서브도메인·`/api` prefix·CORS 모두 없는 동일출처 www JSON API 는 분리 난망 → operator 가 path 매처로 보완.

### D16. explicit-hint 매처 — 힌트=강제 양성, exclude=강제 제외(최우선) (doc/09)
operator 가 `api_path_prefixes`/`api_path_regexes` 로 명시한 경로는 **점수 임계와 무관하게 강제 양성(hard-admit)**,
`exclude_path_prefixes`/`exclude_path_regexes` 는 **강제 제외(게이트 내 최우선)**. 근거: doc/08 §4 표의 weight 0.55 만으론
middle 임계 0.70 을 못 넘어(0.55+repeat 0.12=0.67) §8 이 보완하려는 동일출처 www JSON API 를 구제 못 함. explicit hint 는
operator 의 선언적 단언이므로 임계 우회가 직관·목적에 부합. 0.55 weight 는 api_confidence **보고값**엔 그대로 사용.
- **게이트 순서**(spec 미매칭 시): exclude → api 힌트(admit) → web-form 억제 → score 게이트. spec 매칭은 게이트 우회(권위).
- **설정 shape**: `MatcherConfig`(apiPathPrefixes/apiPathRegexes/excludePathPrefixes/excludePathRegexes/includeWebForms).
  exclude 에 regex 대칭 추가(doc/08 §7 은 prefix only 였음 → 보완 필요). prefix=세그먼트 경계, regex=full-match, 대상=pathTemplate.
- **explicit-hint 모드**: 힌트 설정 시 내장 path-shape 신호(api_segment/version/id 등) 비활성, pathHint(0.55) 사용. host/cors/method/query/ua/static/repeat 공통.
- **병합**: 4개 list 는 전역∪도메인, includeWebForms 는 도메인 override(null=상속)→기본 false. weights override(custom)와 별개.
- **ReDoS**: 생성 시 1회 compile(캐시) + 개수(prefix200/regex50)·길이(regex200/prefix256) 상한 fail-fast,
  매칭당 deadline(50ms) interruptible CharSequence, 초과 시 해당 regex no-match + 카운터(throw 금지). 입력 길이 4096 상한.
- **include_web_forms**: html penalty 는 §8 에서 제거됨. 대신 `false`(기본) 시 `endpoint_kind=WEB_PAGE` AND write_method 를
  hard-drop(DROP_WEB_FORM) 하되 host_api·cors·hint 강신호면 미적용. GET 은 대상 아님(§8 `$type=document` 함정 회피).
- **범위**: 매처+설정 모델+게이트 통합까지. DB 저장·중앙 API·메트릭 배선은 후속. 상세 doc/09.

### D17. 분류 설정 DB 저장 + effective 병합 (doc/10)
전역 단일 레코드 `ClassificationConfig`(고정 PK=1L) + 도메인 override 는 **신규 엔티티 `DomainClassificationConfig`(host PK)**
(DomainConfig 확장 아님 — 관심사 분리·희소 행·전역과 동일 shape·surgical). 둘 다 profile + thresholdOverride(Double 컬럼)
+ customWeightsJson(@Lob, Map<String,Double>) + matcherJson(@Lob, MatcherConfig 직렬화).
- **JPA 저장**: 매처(4 lists+includeWebForms)·custom weights 는 **`@Lob String` JSON**(record/Map Jackson 왕복, 컬럼 1개),
  threshold 는 **Double 스칼라 컬럼**. **벤더 JSON 타입(JSONB) 미사용** → H2/PG 이식(기존 canonicalJson/reportJson 컨벤션).
  weight map key=Weights 필드명(단일 명명원). repeatMinCount override 는 v1 컷.
- **병합**: profile=도메인??전역??MIDDLE. weights=preset(profile) 또는 CUSTOM→MIDDLE+global+domain(키별 domain 승).
  **weights override 는 CUSTOM 한정**(preset 무시, doc/08 §5). **threshold 는 어떤 프로파일도 override 가능**(도메인>전역>preset)
  → doc/08 §5 의 "preset→임계 override 무시"를 **가중치 한정**으로 완화. matcher=MatcherConfig.merge(lists 합집합).
- **무회귀(최우선)**: 설정 부재/기본 seed = 현행(`ApiScorer(MIDDLE)`+`ApiHintMatcher.NONE`, includeWebForms=true)과 100% 동치.
  `MatcherConfig.merge` raw default `?? false`(억제 ON)는 출시 즉시 무회귀 위반+§8 함정 → **resolver 가 전역 includeWebForms=null→TRUE 정규화**
  후 merge → `effective.webForms=도메인??전역??TRUE` = **억제 opt-in**. merge 코드/테스트 무변경. → **D16 의 "기본 false" 를 "effective 기본 true(억제 opt-in)" 로 조정.**
- **해석**: `EffectiveClassificationResolver.resolve(host)`→`EffectiveClassification(profile/weights/matcher/scorer/hints)`.
  v1 무캐시(스캔당 재빌드, 비용 무시). 캐시(호스트별+invalidate/invalidateAll hook)는 REST 단계. JSON 파싱/상한 위반 fail-fast.
- **배선**: `Classifier` 5-arg `classify(...,scorer,hints)` 오버로드 추가, `DiscoveryJobService.analyze` 의 NONE 주입(139-141) 교체.
  `ApiScorer` 에 `ApiScorer(Weights)`/`weights()`/`presetWeights(Profile)`/`applyOverrides` 추가.
- **범위 밖(후속)**: 중앙 REST GET/PUT /classification(전역·도메인 effective)·캐시 invalidate 배선·non_api dropped 메트릭.

### D18. 분류 설정 중앙 REST API + 캐시 활성화 (doc/11)
doc/10 저장/병합 위에 REST 4종을 신규 `ClassificationController`(`@RequestMapping("/api/v1")`) 하나에 모은다(응집·DomainController 비대화 방지).
- **엔드포인트**: `GET/PUT /classification`(전역, PK=1L upsert), `GET/PUT /domains/{host}/classification`(도메인). PUT=전체 교체(null=clear).
  GET 도메인은 **override(저장값)+effective(병합값)** 동시 노출. DTO 는 `MatcherConfig`·`ApiScorer.Weights` record 직접 재사용(평행 DTO 회피).
- **부재 처리**: 전역 행 부재→200 default(MIDDLE), 도메인 override 부재→200 effective(전역 기반), **도메인(DomainConfig) 미등록→404**(sub-resource 일관, orphan 방지).
- **쓰기 검증→400**: PUT 저장 전 `ApiScorer.validateThreshold/validateWeightOverrides` + `new ApiHintMatcher(matcher)`(폐기) 재사용.
  customWeights 는 profile 무관 항상 검증(적용만 CUSTOM). 매핑은 **컨트롤러-로컬 `@ExceptionHandler(IllegalArgumentException)→400`**
  (전역 advice 신설 안 함, 타 컨트롤러 불변). 손상 *저장* JSON 의 `IllegalStateException`=미처리 500(데이터손상). 손상 body=Spring 자동 400.
- **캐시 활성화**: `EffectiveClassificationResolver` 에 host별 `ConcurrentHashMap`+`computeIfAbsent`, `invalidate`/`invalidateAll` 실구현
  (현 no-op 대체). 무효화 주체=PUT(전역→invalidateAll, 도메인→invalidate(host)). effective 불변→공유 안전. throw 시 미저장(poisoning 없음).
  **스캔 경로(DiscoveryJobService)는 이미 resolve() 호출 → 코드 변경 없이 캐시 경유.**
- **HA 한계**: in-memory per-instance 캐시 → 다중 인스턴스 stale 가능. 단일 인스턴스 전제(HA=ShedLock 후속). TTL/pub-sub 는 HA 도입 시.
- **동시성**: 단일행/host upsert + updatedAt=now, last-writer-wins(쓰기 희소). `@Version` 낙관락은 선택(범위 밖).
- **범위 밖(후속)**: 인증(permitAll 유지)·non_api dropped 메트릭·repeatMinCount override·HA cross-instance 무효화.

### D19. non_api dropped observation 메트릭 (doc/12)
게이트 DROP_* 탈락분을 사유별로 집계해 스캔 결과에 노출(현재 단순 제외 → 운영자 가시성).
- **집계 위치 = Classifier**(게이트 evaluate 를 아는 유일 지점; 타 위치는 evaluate 재실행+로직 중복). 신규
  `classifyWithMetrics→ClassificationResult(findings, DroppedNonApi)`; 기존 `classify→List<Finding>` 3 오버로드는 `.findings()` 위임(하위호환).
- **카운트 대상**: non-OPTIONS·spec 미매칭·게이트 DROP_*. **OPTIONS(CORS-only)·spec 매칭(ADMIT 우회)·ADMIT(Shadow) 은 제외.**
  불변식 `discovered(non-OPTIONS)=specMatched+shadow+dropped.total`.
- **노출(린)**: **(a) DiscoveryReport 임베드 → /result** 채택, (b) Micrometer 미채택(별도 Actuator TASKS 항목과 중복). 동일 카운트 후속 재사용.
- **버킷**: `model/DroppedNonApi(excluded, webForm, lowScore)` + `@JsonProperty total()` 파생. `DiscoveryReport` top-level 필드 추가
  (Summary 아님 — scan-status 경량 유지). 항상 non-null, JSON 가산적(비파괴).
- **영속**: reportJson(@Lob) 통째 직렬화에 자동 포함 → **ScanResult 컬럼 변경 0**.
- **ETag**: 입력에 droppedNonApi 추가(`List.of(specVersion, summary, findings, droppedNonApi)`). findings 불변·dropped 분포만 바뀌는
  경우(예 LOW_SCORE→EXCLUDED)도 결과 콘텐츠 변화이므로 version 갱신돼야 함(generatedAt 제외 원칙 일관, doc/07 §8). 기존 결과 ETag 1회 변경.
- **범위 밖(후속)**: Actuator/Micrometer 대시보드·알람, scan-status/ScanResult total 비정규화(선택).

### D20. 정규화 고카디널리티 방지 — T1 통계승격+상한 / T2 param 후보 / T3 sensitive (doc/13)
3개 TASKS 항목을 정규화/인벤토리 계층 응집으로 **1 PR 통합**(브랜치 feature/normalization-cardinality-control).
- **(T1)** 통계 `{var}` 승격: 1차 휴리스틱 후 2차 패스. 클러스터(method/host/segcount/위치제외 prefix)에서 `distinct/requests≥0.3`
  **AND** `distinct≥20`(소표본 가드) **AND** 승격후 수렴`≥0.7`(false merge 가드) → `{var}` 재병합. (doc/02 §3.3/§3.4와 동일 = 별도 "통계보정 3단계" 항목도 커버.)
  상한: host당 template 5000 / endpoint당 query param 50, 초과분 drop(hits 낮은 순) → **`DroppedByLimit(templates,params)`**(DroppedNonApi 패턴 재사용, DiscoveryReport top-level + ETag).
- **(T2)** param 후보(body 없음): `ParsedRequest.queryKeys→queryParams(name+ValueLenBucket)` 교체(**값 폐기, 길이 버킷만** — privacy-preserving, 내부필드라 무회귀).
  path param = 템플릿 변수세그먼트. `DiscoveredEndpoint.params: ParamCandidates(query,path)` 저장, per-endpoint 상한.
- **(T3)** sensitive key matcher: 기본 키목록+정규식(대소문자무시). **정책: 키이름 보존+sensitive 플래그+길이버킷 억제**(보안도구라 "민감 param 존재"는 고가치 신호 → 숨기지 않음; 값특성 누출 위험인 버킷만 억제). 완전제외는 옵션.
- **설정 저장(린 판단)**: 이번엔 **`@ConfigurationProperties`(application.yml) 만**(D12 정적→yml 원칙). 도메인 override·중앙 REST 는 후속(doc/10·11 패턴).
- **순서**: 파스→1차템플릿→T1승격→T1상한→T2후보→T3마스킹→방출. 승격을 상한 앞에(병합될 것 미리 drop 방지).
- **하위호환**: queryKeys 교체는 내부한정(외부 무영향). params·droppedByLimit 가산적. 승격 보수적+상한 높아 기존 입력 미발동(무회귀). 노출=Shadow.params + report.droppedByLimit, ETag 포함.
- **범위 밖**: sensitive/상한 도메인 override·중앙 API, Active/Zombie param 노출, HLL/t-digest 근사.

### D21. 스펙 파서 Postman/CSV 실구현 (doc/14)
스캐폴드인 Postman/CSV 파서를 OpenApiSpecParser 와 동일 Canonical 산출로 실구현. **신규 의존성 0**(Postman=Jackson 트리, CSV=기존 univocity-parsers).
- **공유 정규화 신설**: `SpecNormalize.template`(`:var`/`{{var}}`→`{var}`+슬래시 규칙)·`host`(소문자/null). 3종 동일성의 단일 진실원.
  `SpecCanonicalizer.canonicalize`(dedupe(method,host,template)+deprecated OR+안정정렬)를 **SpecStore.upload parse 직후 전 포맷 균일 적용**(정렬→ETag 결정성·동일성 비교).
- **Postman**: Jackson `readTree`, item 트리 DFS(폴더 name·deprecated 자식 전파), url object(`path`배열/문자열)·string 모두, host 배열 `.`join+`{{baseUrl}}` 변수→치환 실패 시 null,
  path 변수→`{x}`, deprecated 규약=`[DEPRECATED]`/`(deprecated)` 이름·폴더·description, sourceRef `postman#이름경로`. query 는 Canonical 제외.
- **CSV**: univocity(header 추출·따옴표/BOM 자동), 필수 헤더 method/path 검증(누락→fatal), deprecated 파싱(true/false/1/0/y/n), `:var`→`{var}`, sourceRef `csv#row{n}`.
- **오류 처리(린)**: fatal(손상/필수 누락/0건)→IllegalArgumentException(→400), recoverable(행/item 단위 누락)→**skip+log.warn**(유효분 반환, doc/03 §6).
  **구조화 spec_source.warnings 채널은 범위 밖**(별도 리포트 항목) — seam=`SpecParseResult(endpoints,warnings)` 만 명시, 인터페이스 변경 안 함(OpenApi/SpecRecord 무변경, 파서 PR 작게).
- **SpecFormatDetector**: 현 라우팅 정확 → 필수 변경 없음(선택: Postman 스키마 host `schema.postman.com` 확장).
- **3종 동일성**: 동일 논리 스펙 3포맷 → (method,host,template,deprecated,version) 동일(sourceRef 제외). 품질 TASKS "3종 동일성 테스트" 를 이 PR 이 충족.
- **범위 밖**: warnings 채널·매처 캐시 무효화·멀티 스펙 병합.

### D22. 매처 캐시 무효화 (doc/15)
매 스캔 `new EndpointMatcher(spec)` 재생성을 캐시로 대체 + SpecStore 업로드 시 evict. 참고 패턴=EffectiveClassificationResolver(doc/11 §3).
- **캐시 키 = (host, specVersion), host당 1슬롯**(`ConcurrentHashMap<String,VersionedMatcher(specVersion,matcher)>`). 스캔은 항상 active 버전만 조회하므로
  새 버전이 슬롯을 덮어써 누수 없음 + version 필드로 **stale 서빙 불가**(구조적 정합성). vs host-only(evict 호출에 정합성 의존) → (host,specVersion) 가 더 안전.
  분류설정은 단조 버전이 없어 host-only 였지만 스펙은 specVersion 활용.
- **무효화 + 순환 회피**: `SpecStore.upload→cache.invalidate(host)`, `DiscoveryJobService→cache.get(host,specVersion,Supplier)`. **캐시는 무의존**
  — 스펙 로드를 안 하고 **build supplier 를 호출측이 제공**(`()->new EndpointMatcher(spec)`) → SpecStore↔캐시 순환 불가(writer 무효화/소비자 빌드 원칙, doc/11 동일).
- **불변·poisoning-free**: EndpointMatcher 불변(final index·read-only match)→공유/동시 read 안전. build throw 시 compute 매핑 불변(미저장)→재시도.
- **specVersion=0(스펙 없음)**: 빈 matcher 균일 캐시(특별분기 없음). in-flight 스캔은 로드 시점 버전으로 스냅샷 일관(matcher 불변).
- **무회귀**: 동일 spec→동일 matcher→동일 findings/ETag, 재생성만 제거. SpecStore/DiscoveryJobService 수동생성 테스트 인자 추가(가산).
- **범위 밖**: 멀티 인스턴스 cross-instance 무효화(HA 후속, doc/11 §3 한계와 동일).

### D23. 버전 기반 Zombie 추정 + Zombie severity (doc/16)
명시 deprecated 만 Zombie 이던 것을 버전 보강(§5) + 모든 Zombie 에 severity(§4.2) 부여. 1 PR.
- **버전 추정**: 첫 `^v\d+$` 세그먼트=버전, resourceKey(버전 위치 "{V}" 치환·나머지 동일)로 페어링. 그룹 내 active(observed&비deprecated) 최대버전 Vmax,
  active 이면서 `<Vmax` → **추정 Zombie**(Active 재분류). 신뢰도 **0.6**(명시 1.0 보다 낮게, doc/04 §5), `estimated=true`. `!deprecated` 에만 적용(중복 없음).
  신버전이 Unused/deprecated 면 트리거 안 함.
- **severity=f(hits,recency,2xx)**: 외부 시계 없이 가용 메트릭만으로 결정적 산출 — hitsScore(log10 볼륨)·successScore(2xx/total)·spanScore(lastSeen−firstSeen, recency 대용).
  score=0.5·hits+0.3·success+0.2·span → band HIGH≥0.66/MED≥0.33/LOW. **모든 Zombie(명시+추정)에 적용.** confidence(진짜냐)와 severity(시급성) **직교**.
- **shape**: `Finding.Zombie` 에 `Severity severity`+`boolean estimated` 가산. `model/Severity(score)`+`@JsonProperty band()` 파생+`enum SeverityBand`. findings 가 ETag 입력이라 노출/ETag 자동.
- **산정 위치**: Classifier(spec+observed+메트릭 보유). `observedSpecKeys: Set→Map<String,Evidence>`(1st pass 매칭 d 메트릭 누적, host-agnostic 다중 host 합산).
  헬퍼 `VersionZombieInference`·`ZombieSeverity` 분리. 무회귀: 명시 Zombie confidence 1.0 보존, 버전 추정은 버전 페어 있을 때만(비버전 spec 무영향).
- **설정(린)**: 추정 0.6·severity 가중치/임계는 코드 상수(1차), 튜닝 시 `@ConfigurationProperties` 이동(seam). 중앙 API 후속.
- **범위 밖**: 절대 cross-scan recency(히스토리), 추정 임계 중앙 설정.

### D24. response_type_api 양성 가중치 (doc/17)
doc/08 §9 보류($type taxonomy 불확실) 항목을 **양성-only 비대칭 + 보수적 집합**으로 활성화.
- **신호 재사용**: `EndpointKindClassifier` 가 이미 dominant $type ∈ {xhr,fetch,json,api,ajax} → `EndpointKind.API_CANDIDATE` 로 분류.
  → **신규 필드/Acc 변경 없이** `endpointKind==API_CANDIDATE` 를 신호로 사용. 집계도 기존 dominant(plurality) 로 해결.
- **집합**: xhr/json 으로 좁히지 않고 기존 API_TYPES(5값) 재사용 — 모두 정당한 API 신호(document 트랩과 달리 역위험 낮음), API_CANDIDATE 와 일관.
  집합은 EndpointKindClassifier(=taxonomy 샘플링 항목) 소관, ApiScorer 는 API_CANDIDATE 만 소비 → taxonomy 정제 시 자동 수혜.
- **비대칭(양성-only)**: API_CANDIDATE→+responseTypeApi. WEB_PAGE(document)/UNKNOWN/부재→무가산·**무감점**(§8/§9). STATIC 과는 kind 단일값이라 **상호배타**(충돌 없음).
- **Weights 확장(14번째)**: Weights record/MIDDLE·HIGH·LOW presets(**0.25/0.18/0.32**, §9 캐비엇·customWeights 튜닝)/WEIGHT_KEYS/applyOverrides/score 5곳.
  funnel 구조라 **resolver/DTO/controller 무변경**(presetWeights·applyOverrides·WEIGHT_KEYS 경유, record 재사용). REST customWeights 가 키 자동 수용.
- **무회귀**: 비-API endpoint 무변경(신호 미발화), API_CANDIDATE 만 api_confidence 상승(의도). clamp 테스트 영향 없음, exact-score API_CANDIDATE 테스트만 갱신.
- **범위 밖**: $type taxonomy 샘플링(별도 항목), responseTypeApi 실데이터 보정.

### D25. TASKS 정합화 + 우선순위 방침 (자체기능 우선, 외부연동 후순위)
설계문서 09~17 이 늘면서 dev 항목이 doc 본문 '체크리스트/후속'에 흩어져 TASKS 와 싱크가 어긋남 → 일원화.
- **정합화**: doc/00~17 의 dev 항목·'범위 밖/후속' 전수 추출 → TASKS 교차대조(누락 0). 미반영 후속 추가: cross-scan recency(doc/16)·Active/Zombie param 노출(doc/13)·
  scan-status total_dropped(doc/12 선택)·분석 파라미터 중앙 API 확장 묶음(repeatMinCount/sensitive·상한/severity 임계, doc/10/11/13/16). HA cross-instance 무효화·spec_source.warnings 채널 seam 은 기존 항목에 메모.
  완료된 "API 점수화 모델"(전부 [x]) Done 이동, 보류 섹션 response_type_api 중복 제거. TASKS 상단에 **'설계문서↔TASKS 매핑' 표** 추가(다음 세션 단일 기준).
- **우선순위 방침(사용자 결정)**: **기본/자체 기능 먼저, 외부(중앙) 연동은 나중.** TODO 를 P1 자체 분석기능 → P2 품질/테스트 → P3 운영 → P4 외부연동 → 보류 로 재배열, 섹션 순서로 우선순위 표현 + `→ 의존:` 메모.
  - P4(외부연동 후순위): 서비스간 인증·완료 웹훅·분석 파라미터 중앙 API 확장. 자체 분석기능(P1) 안정 후 진행.
- **근거**: 분석 정확도(자체 기능)가 제품 가치의 핵심이고 외부 연동은 그 결과를 전달하는 계층 → 자체 기능 선행이 합리적. 코드 변경 없는 문서 작업(branch docs/tasks-sync-and-db-schema). doc/18(DB 스키마)은 technical_writer 담당이라 미관여.

### D26. 설계 도출 개발 항목의 subitem 추적 (TASKS 일원화, D25 연계)
설계 과정에서 나온 dev 항목이 설계문서 본문에만 흩어지지 않도록 TASKS 로 일원화·추적하는 규칙을 CLAUDE.md 에 영구 코드화.
- **규칙(사용자 결정)**: architect 가 설계를 완료하면 도출된 dev 구현 체크리스트 + 후속/한계 등 개발 항목을 TASKS 의 **해당 부모 항목 아래 subitem(들여쓴 하위 체크박스)** 으로 추가. subitem 은 개발 완료 시 `[x]`, **모든 subitem 완료 시 부모를 `[x]` 로 바꾸고 Done 섹션으로 이동**한다.
- **권위**: TASKS = 단일 권위 기준, 설계문서(doc/NN) = 근거·상세. D25 의 '설계문서↔TASKS 매핑표'·우선순위(P1~P4)와 일관 — 새 subitem 은 부모의 P 버킷을 따른다.
- **근거**: 항목 단위로 완료를 확인해 추적성을 높이고 설계와 실행의 싱크를 유지. D25(흩어진 dev 항목 일원화)의 운영 규칙화. 코드 변경 없는 문서 작업(branch docs/subitem-tracking-policy).

### D27. 실재성 404-only 필터 (인벤토리 단계) (doc/19)
doc/02 §4·doc/04 §7(:172)의 인벤토리 단계 명시 적용(현재 Classifier soft penalty 만). 비실재 탐침 경로를 인벤토리에서 hard-drop.
- **정의(보수적)**: `hits>0 AND status404==hits`(= 전 요청이 404)만 hard-drop. `Acc` 에 **`status404` 전용 카운터** 추가 — `statusBuckets`(4xx 통합)로는
  **401/403-only(인증벽 뒤 실재 endpoint)**까지 drop돼 보안 미탐 위험 → 정확히 404 100% 만. 2xx/3xx/5xx·비-404 4xx 하나라도 있으면 보존.
- **위치**: `InventoryBuilder.buildWithLimits` 에서 Acc 집계 후·`CardinalityNormalizer`(승격/상한) **전**. noise 가 host 상한(5000) 예산 먹지 않게 가장 먼저 제거
  (404-only 는 단조 → 승격 전 개별 제거 = 승격 후 제거 동치). **spec 보호**: `source==INFERRED` 만 대상, `SPEC`(spec 매칭)은 제외(권위, 미배포 경고는 별도).
- **soft penalty(-0.7, doc/04 §4.1)와 역할 분리**: hard-drop 조건(404==hits, 100%) ⊂ soft 조건(4xx≥0.9, 90%). hard-drop 이 인벤토리에서 먼저 제거 → Classifier 미도달 → 중복 없음.
  남는 회색지대(mostly-4xx≠100% / 401·403 포함)는 soft 가 저신뢰 Shadow 로 보고. 우선순위 hard→soft.
- **노출(린)**: 단순 제외 아니라 **`model/DroppedNonExistent(int notFound)`** 신규 형제 필드(DroppedNonApi/DroppedByLimit 패턴) → `DiscoveryReport` top-level + ETag 입력 포함.
  운영자 가시성(보안 도구). `InventoryResult`·`ReportBuilder.build`·`DiscoveryJobService` 배선.
- **무회귀**: 2xx/3xx/5xx 혼재·404≠100%·401/403-only·spec 매칭 모두 보존. `ClassifierTest`(DiscoveredEndpoint 직접) 무영향(필터는 인벤토리 계층).
- **범위 밖**: 문서화됐는데 404-only=미배포 경고, 401/403 status 세분.

### D28. 구현 시 설계문서 체크리스트 동기 갱신 (D26 보완)
설계문서의 'dev 구현 체크리스트' 항목이 구현 완료 후에도 미완료(`[ ]`)로 남아 미구현으로 오해되는 문제(doc/09~19 점검에서 발견) 방지.
- **규칙**: dev 가 구현 완료해 PR 로 머지할 때, TASKS 의 subitem/부모 갱신과 함께 **해당 설계문서(doc/NN)의 dev 구현 체크리스트 항목도 `[x]` 로 동기 갱신**한다 → 설계문서가 항상 자기 구현 상태를 반영.
- **역할 분담**: TASKS = 우선순위·큐 단일 기준, 설계문서 체크리스트 = 그 문서 범위의 구현 상태 기록. 둘 다 같은 PR 에서 동기 갱신. '범위 밖/후속/한계' 는 설계문서에서 미완료로 두고 TASKS 후속으로 추적(현행 유지).
- **D26 와의 관계**: 모순 아닌 보완. D26 = '설계 도출 항목을 TASKS subitem 으로 추가·추적', D28 = '구현 시 설계문서 체크리스트도 동기 체크'. CLAUDE.md '작업 항목 관리' 섹션에 코드화. 코드 변경 없는 문서 작업(branch docs/checklist-sync-and-process).

### D29. endpoint_kind referer 보조 신호 (doc/20)
$type+확장자만 쓰던 EndpointKindClassifier 에 `$http_referer` 부모-자식(정적 자원의 referer=부모 페이지)을 **보조 양성 신호**로 추가(doc/02 §5.1~§5.4).
- **PAGE_URLS**: `InventoryBuilder.buildWithLimits` 시작 corpus pre-pass(신규 `RefererSignalExtractor`) — static 요청(확장자/library) referer 의 **path 만 PathNormalizer 정규화**→`Map<template,childCount>`. Acc 무변경(corpus 횡단).
- **커버리지 게이트(§5.4)**: `static_ratio≥0.05 AND referer_present_ratio≥0.20` → ACTIVE, else **DORMANT**(전 endpoint 현행 UNKNOWN). 실 Loki api 호스트(정적 미경유)는 dormant → 무회귀 핵심.
- **통합**: $type 결정 우선, **결과 UNKNOWN 일 때만** + active + `pageUrls.get(template)≥2` → WEB_PAGE(conf 0.6). **비대칭 양성**(PAGE_URLS 부재→UNKNOWN 유지·무감점, §5.1). 3-arg classify 신규 + 2-arg 오버로드(dormant 위임) 하위호환.
- **노출**: `model/EndpointKindSignal(status, staticRatio, refererPresentRatio)` → DiscoveryReport top-level(`.NONE`=dormant) + ETag(ratios round3). pageUrls 비노출.
- **api_candidate 약가점(§5.3 step4) 미채택**: non-browser UA 는 이미 ApiScorer.nonBrowserUa 중복 + referer **부재**를 양성 근거로 쓰면 §5.1 비대칭(부재=무증거) 충돌. referer **존재→web_page** 양성만.
- **상호작용**: doc/17 responseTypeApi 충돌 없음(referer 는 UNKNOWN 일 때만, API_CANDIDATE 는 이미 결정·배타). doc/09 web-form drop 정확도↑. Shadow 오탐 감소.
- **무회귀**: $type 결정·dormant 환경·2-arg 오버로드 전부 현행. 코드 상수 임계(중앙 튜닝 후속 seam).

### D30. $type taxonomy 샘플링 + 정제 (doc/21)
doc/08 §9 보류 사유($type taxonomy 불확실·document 트랩)를 실 Loki 샘플링으로 확정·정제. **research-gated**(샘플링 선행 → 증거표 → 규칙 적용).
- **현 상태**: 실관측 $type 은 document/library 뿐(doc/02 §5.0). API_TYPES{xhr,fetch,json,api,ajax} 는 관례 추정·**실관측 0** → API_CANDIDATE(=$type 경유)·doc/17 responseTypeApi·Classifier +0.05 가 운영서 dormant 일 수 있음(미확인).
- **샘플링 프로토콜(§A, research 0.4)**: loki_sample.py 라벨 패턴 재사용, 작은 창(≤10분)·유한 limit(≤2000)·페이지 1~2·순차·off-peak, **`limit=1e8` 금지**(D7). 서버측 `^|^` 파싱 금지(fragile, doc/05)→raw 라인 로컬 파싱(field idx 19/9/5). API·웹·혼합 호스트에서 type×status×method 교차(status=200 GET 편향 교정). 산출=증거표.
- **분류 규칙(§B)**: 값→api_candidate/static/web_page/ignore. API_TYPES 편입은 **명백 API성만**(정적 path 편중 아님 + api 컨텍스트/혼합 method), 애매하면 제외(비대칭·무감점, D24 보수적). document=트랩 재확인→WEB_PAGE 약신호 유지·**API_TYPES 미편입**.
- **코드 변경 3 tier**: ⓪ **API_TYPES 상수 정제**(데이터 게이트, ApiScorer 무변경·responseTypeApi 자동 전파) ① **(권장) corpus $type 히스토그램 노출**(앱별 vocab→매 스캔 self-report, 수동 재조회 불요·document 트랩 가시화, Acc.typeDist 재사용 비용≈0; ETag 는 **distinct 키집합만**=드리프트 bump·count 변동 무bump 로 304 보존) ② **(후속) API_TYPES 설정화**(@ConfigurationProperties/중앙 API, 앱별 vocab).
- **결론(샘플링 확정, 2026-06-24, 총 쿼리 3회 / API·웹 호스트·peak·off-peak)**: 관측 vocab = {`document`,`library`} 뿐, **API_TYPES 5값 실관측 0**(write·4xx·OPTIONS 포함 광범위에서도 0) → **Tier0 = API_TYPES 무변경 확정**(데이터가 추가·제거 근거 미제공, 부재는 dormant·무감점이라 5값 관례 유지·근거 주석만). **document 트랩 ≈100% 재확인**(api.weble.net 의 OPTIONS 2066·POST·RESTful path 전부 document) → WEB_PAGE 약신호 유지·**API_TYPES 미편입**. **responseTypeApi / $type-API_CANDIDATE dormant 확정**(무해, 가치 재평가는 후속). 앱별 vocab·호스트별 트랩 → **Tier1 히스토그램 채택**(self-report). 상세 증거표 doc/21 §A-결과.
- **무회귀**: API_TYPES 데이터 게이트(미확정시 무변경 — 이번 무변경 확정), 확장자 1순위 불변, WEB_PAGE⊕API_CANDIDATE 배타 유지(doc/17), 히스토그램 additive·ScanResult 스키마 무변경. doc/20 referer 무관.
- **범위 밖**: 설정화(Tier2), api성 $type 부재 시 responseTypeApi 가치 재평가, document→UNKNOWN 강등(데이터 기반 후속).

### D31. distinct/분위수 대용량 근사 — HLL + KLL (doc/22)
정확 `HashSet`(distinct)·`ArrayList`(분위수) 를 고정크기 sketch 로 교체해 규모 대응(doc/02 §4, D20 후속). **라이브러리 = DataSketches(D8, build.gradle 기확보, 신규 의존성 0)**.
- **라이브러리**: distinct=`HllSketch`(lgK=12), 분위수=`KllDoublesSketch`(k=200). 브랜치명 "t-digest" 는 분위수 근사 일반 지칭 — 실제는 **이미 들어온 DataSketches KLL**(distinct·분위수 한 라이브러리 통일, t-digest 추가는 D8/린 위배 → 미채택). 자체구현 미채택(검증비용). 크기는 코드 상수(seam=@ConfigurationProperties).
- **전면 근사(hybrid 미채택)**: 근사가 판정에 주는 경계가 사실상 없음 — ① distinctClients 유일 결정경계 `<=1`(shadowConfidence)는 HLL **소-N exact**(coupon 모드)라 무오차, ② percentile 은 결정 미사용·미노출, ③ **CardinalityNormalizer `distinct`(승격 임계 distinct≥20)는 `statics.size()`(distinct 세그먼트 값 수=Acc 멤버 수)이지 distinctClients 가 아님** → 근사 무관(과제 전제 정정). hybrid 모드전환 복잡도 한계효용 0.
- **블라스트 반경 = `Acc.java` 한정**: 필드(HashSet→HllSketch, ArrayList→KllDoublesSketch)·`add`(update)·`mergeFrom`(HLL Union/KLL merge)·`toEndpoint`(getEstimate/getQuantile→round→long). **`DiscoveredEndpoint.Metrics`(long shape)·Classifier·Normalizer·리포트·영속 전부 불변**(surgical).
- **병합/직렬화**: HLL union=합집합 distinct·KLL merge=결합분포(mergeFrom 의미 보존). sketch 는 **스캔 transient·비영속**(long 추출 후 reportJson 영속) → 직렬화 복잡도 없음. sketch 고정크기 = per-signature 메모리 가드 내장(doc/13 철학, 무한성장 제거 — 본 작업 본질).
- **ETag churn 0 + percentile ETag 금지(불변식)**: distinctClients·percentile 둘 다 ETag 입력 아님 → 근사 전환 churn 0. KLL compaction 비결정 가능 → percentile 은 향후에도 ETag 입력 금지(노출 시 body 진단용만).
- **무회귀/검증**: distinctClients `<=1` HLL-exact→shadowConfidence 불변, percentile 미소비, normalizer/severity(hits/2xx/span 정확) 불변. 검증=정확도(허용오차 HLL±3%/KLL rank)·경계(0/1/2 exact)·병합·회귀. 기존 exact-값 단언은 소-N 유지 또는 허용오차 전환.
- **범위 밖**: sketch 크기 설정화, percentile 리포트 노출(body·비-ETag), distinctClients 캡 exact-set 옵션.

### D32. preflight vs 진짜 OPTIONS 구분 — B 판정(로그 신호 부재, 한계 확정) (doc/23)
스펙 OPTIONS operation 이 Unused 오판되는 한계의 타당성 판정. **결론 B: 로그에 구분 신호 없음.**
- **신호 분석**: preflight 결정적 식별자 = 요청 헤더 `Origin`+`Access-Control-Request-Method`. 로그 포맷(doc/02 §1)은 referer 만 캡처, **Origin·ACRM·Authorization 미로깅**. 캡처되는 204/body/UA/referer 는 preflight↔진짜 OPTIONS **무판별**(브라우저 fetch OPTIONS 는 preflight 와 로그상 동일). 약신호 확률판정은 false precision(D15)·비대칭 충돌로 미채택. ⇒ B.
- **현 동작·영향**: Classifier 1차 패스가 **모든 OPTIONS skip**(cors 신호로만, doc/08) → observedSpec 미진입 → 스펙 OPTIONS operation 항상 Unused. 오판 범위 = 스펙이 OPTIONS 를 명시 정의한 소수 endpoint(진짜 호출 시 false Unused). 비-OPTIONS·cors 보너스·Shadow 는 무왜곡("반대 왜곡" 없음 — OPTIONS 미보고라 inflate 불가).
- **완화**: M1(권장·린·선택) — 2차 패스 `OPTIONS && !observed && corsKeys.contains(host+template)` → Unused 에 `preflightAmbiguous` 주석(corsKeys 재사용·신규수집 0·비대칭, Active 단정 안 함, Finding.Unused +필드는 doc/16 severity 추가 동형). M2(채택·설계 doc/23 §8) operator genuine-OPTIONS 힌트 — 아래 별도 bullet. 약신호 휴리스틱 미채택.
- **M2 상세(설계 → doc/23 §8)**: `MatcherConfig.optionsOperationPrefixes`(전용 list 신설 — `apiPathPrefixes` 재사용 불가, api 경로 preflight 를 genuine 으로 오인하기 때문) 선언 → `ApiHintMatcher.genuineOptions(template)`(validatePrefixes·prefixMatch 재사용). Classifier 1차 OPTIONS 분기를 **`genuineOptions && matcher.match(OPTIONS) → observedSpec(→Active)`, else skip** 로 한정. **spec-match 한정 = 핵심 안전장치**(과declare 해도 preflight 가 Shadow 로 폭발 안 함, "OPTIONS 절대 Shadow" 불변식 보존; M2 = documented OPTIONS 의 false-Unused 회복만). M1 정합: declared+matched→observed→Active 라 preflightAmbiguous 미설정(이중표기 없음); 미선언→M1 유지. corsKeys/cors 보너스 무변경. 무회귀: 빈 선언→genuineOptions=false→현행, matcherJson 마이그레이션 0(필드부재→empty), 5-arg 편의 ctor. **중앙 API = 추가 코드 0**(MatcherConfig=REST DTO 재사용 → PUT/GET 자동 수용·검증) → **P1 포함**(D25 의 P4 는 신규 엔드포인트 기준).
- **정의적 해결(M3·설계 doc/23 §9, 후속·org 로그포맷 의존)**: isPreflight=**`OPTIONS && acrm!=null`(결정적**, acrm 은 preflight 에만 부착; origin/204 약신호·origin 필드 미채택). 파서 설정 인덱스 `parse.acrm-field-index`(기본 -1=미사용, "있으면 읽는", 20/24필드 호환). **가용성 게이트(무회귀 핵심, doc/20 dormant 선례)**: `Σ acrmPresentCount(OPTIONS)>0`=ACTIVE, 아니면 **DORMANT=현행 전부 skip**(idx -1→acrm 전부 null→자동 dormant, Classifier config 무의존→회귀 구조적 불가). ACTIVE: acrm-absent OPTIONS=genuine→정상 매칭(Active/Shadow, preflight 가 acrm 으로 정밀 제외돼 flood-safe), acrm present=preflight→skip(cors only). **M1 정합**: `preflightAmbiguous` 조건에 `&& dormant` → ACTIVE 면 확실 판정(genuine→Active/pure preflight→plain Unused)으로 자동 승급. **M2 정합**: 게이트 배타(ACTIVE=acrm 자동·권위, DORMANT=M2 수동 spec-match). 노출 `model/PreflightSignal(status)`, ETag=status 만(전환 bump·count 무churn). 무회귀: 기본 dormant=현행 100%·가산 필드. **구현 시점**: org 로그포맷 커밋 시 착수(dormant 선구현은 speculative — 설계/seam 으로 충분).
- **무회귀**: 순수 문서=코드 0. M1 채택 시 Finding.Unused +필드 가산(현행 기본)·ETag 1회(doc/16 선례).

### D33. cross-scan recency 로 Zombie severity 보강 (doc/24)
doc/16 severity 의 window-한정 spanScore 를 스캔 간 이력으로 보강. **recency = zombie 누적 lifespan(entrenchment)**, `now()` 불사용.
- **의미 확정**: "절대 recency" = `lifespan = lastSeen − firstSeen(이력 최초)` (얼마나 오래 지속 = entrenched = 시급성↑). **`now−lastSeen` 류 미채택** — Zombie 는 정의상 트래픽 보유라 ≈0 으로 퇴화 + `now()` ETag churn. 모두 **데이터 타임스탬프** 기반.
- **보강(additive), 대체 아님**: `severity = clamp(base(doc/16 불변) + entrenchmentBonus(lifespanDays))`. bonus = `W·clamp01((log10(days+1)−log10(GRACE+1))/(log10(SAT+1)−log10(GRACE+1)))`, GRACE 미만→0. 1차값 W=0.2/GRACE=7d/SAT=90d(코드상수, 보정=D24 보류 연계). **대체 시 척도 불일치 cliff** → 보강이 base 불변·연속·콜드스타트 무회귀.
- **콜드스타트 자동 흡수**: 이력 없으면 historicalFirstSeen=현재 firstSeen → lifespan=window span ≪ GRACE → bonus 0 → **base=현행**(별도 분기 불요, doc/20 무증거 선례). `ZombieSeverity.of(Evidence)` 오버로드 보존→기존 테스트 무변경.
- **이력 영속**: 신규 `EndpointHistory`(@Id host, **@Lob historyJson** = `Map<specKey,{firstSeen,lastSeen}>`, specKey=`METHOD|host|template`). @Lob JSON(D11 컨벤션·per-host·ScanResult 와 분리=관심사 분리). **spec 매칭 endpoint 만 기록**→spec 크기 bound(누적 방지, Shadow/noise 미기록). ddl-auto 신규 테이블(기존 무영향). ScanResult.findById 패턴 재사용.
- **ETag churn 방지(핵심)**: ① `now()` 불사용 → lifespan=데이터 ts → 동일 데이터+이력 재스캔=동일 severity=동일 ETag(시간 흐름 bump 없음). ② **ETag 의 Zombie severity 를 raw score→`band` 투영(버킷화)** → band 전이 시만 bump, 미세 creep(매 스캔 lastSeen 전진) 무bump(선례 distinctKeys/status 투영; pre-existing hits churn 도 완화). raw score 는 body 유지.
- **흐름**: analyze 가 EndpointHistory 로드→priorFirstSeen 을 Classifier 에 주입(빈 map 오버로드 하위호환), `ClassificationResult.observedTimes` 로 persist 가 merge(min firstSeen/max lastSeen)→save.
- **규모**: host당 @Lob 1행 spec-bound, 스캔당 findById 1+merge O(관측). 정규화 테이블은 대량 규모 후속.
- **범위 밖**: 실데이터 보정(D24 보류)·entrenchment 임계 중앙 API(P4)·추세 신호·정규화 테이블·doc/18 sync(technical_writer).

### D34. 리포트/출력 보강 3항목 (doc/25)
리포트/출력 P1 3건을 1 PR(분리 가능)로 통합. 공통: 가산 노출·편의 ctor 하위호환·ETag 데이터 ts+버킷/명칭집합 투영(doc/21·24 선례).
- **A. low_confidence + spec_source.warnings**: ① `SpecParseResult(endpoints, warnings)` seam **신설**(doc/14 deferred 분) — 3 파서가 log.warn→warnings 수집. ② warnings 업로드 시점 생성→**`SpecRecord.warningsJson` 영속**→스캔이 로드(재파싱 없음, doc/10)→`model/SpecSource(specVersion, format, warnings)` = DiscoveryReport top-level(specVersion 유지·가산). ③ low_confidence = **파생 플래그**(Shadow/Zombie `@JsonProperty low_confidence`=confidence<0.5 1차값, 단일진실원=confidence) + `Summary.lowConfidence` 카운트. **별도 섹션 미채택**(findings 리스트 계약 불변). ETag: warnings 버전당 고정(specVersion 편승, churn 0), 카운트는 임계교차만.
- **B. Active/Zombie params**: `Finding.Active`·`Finding.Zombie` +`ParamCandidates params`(편의 ctor=EMPTY 하위호환). 출처 = **관측 query(매칭 `DiscoveredEndpoint.params` 재사용, `Evidence` 가 params 누적·union)** + **spec 템플릿 path(권위)**. 한계: `CanonicalEndpoint` 는 query-param 정의 미보유(doc/03) → "spec param 정의"=path 템플릿 한정, query 는 관측 기반(canonical query-param 확장은 범위 밖). ETag: doc/24 findings-투영 확장 → params를 **정렬 이름집합+path 토큰**(count/buckets 제외)으로 축약(doc/21 distinctKeys 동형), Shadow·Active·Zombie 균일 → 추가 churn 0 + 기존 Shadow params churn 완화.
- **C. scan-status total_dropped**(선택·낮음): `ScanResult` +`int totalDropped`(persist 에서 droppedNonApi.total+byLimit.total+nonExistent.notFound), `ScanStatusView` +totalDropped. 비정규화 합계(reportJson 파싱 불요), 사유별 상세(/result)는 불변. **ETag 무영향**(scan-status 비대상, 구성요소는 이미 /result ETag).
- **무회귀**: 전부 가산/파생, 편의 ctor, ddl-auto 컬럼(warningsJson·totalDropped 기존 0/null). 무스펙/무params/무dropped=현행. doc/18 sync=technical_writer.
- **범위 밖**: spec query-param 정의 캐노니컬 확장, THRESHOLD/spec_source 실데이터 보정(D24 보류 연계), Shadow confidence ETag 버킷화(pre-existing).

### D35. 멀티 스펙 업로드 (여러 문서 병합) (doc/26)
doc/14(D21) 후속. 한 도메인의 여러 스펙 문서를 하나의 canonical 집합으로 병합. **단일=병합of1=현행 무회귀.**
- **데이터 모델**: `SpecRecord` +`specName`(host 내 문서 식별, null→"default"). PK=id 유지(이미 멀티행). host active set = specName 별 최신 active(여러 active 허용). upload(host,name,content)=같은 name 이전 active 만 비활성+신규(문서 단위 upsert, 다른 name 보존). 기존 데이터 specName null→default→현행.
- **병합**: `loadActiveCanonical` = `SpecCanonicalizer.canonicalize(∪ active)`(dedupe method+host+template·deprecated OR·정렬 재사용). **결정성 보강**: union pre-sort(host,template,method,sourceRef)→비-deprecated 충돌 first 가 결정적(현 insertion-order 의존 제거) → 업로드 순서 무관 동일 merged. version/sourceRef 등 비-deprecated 충돌=**latest-upload-wins**(최신 specVersion 권위, §8.5 갱신)+deprecated OR(provenance 결합은 후속).
- **합성 버전(host-level)** = merged canonical **콘텐츠 결정적 해시**. report.specVersion/SpecSource/matcherCache 키에 사용(per-record specVersion 은 이력 유지). add/update/delete 로 콘텐츠 변화 시만 변경, 동일 콘텐츠 재업로드=동일 버전(현 per-upload bump 보다 안정). upload/delete→matcherCache.invalidate(host)+합성버전 가드. ETag 콘텐츠 결정적·시간非의존(doc/24 일관).
- **업로드 API**: SpecController +`name`(기본 default=현행 교체 호환)·`DELETE .../spec/{name}`·(선택)list. **P1=store/merge/apply+관리 최소**, 중앙 멀티-spec 오케스트레이션/auth=P4(D25). 교체 vs 추가 = 문서 단위 upsert(같은 name 교체/다른 name 추가).
- **SpecSource(doc/25) 확장**: +`documents[{name,format,specVersion}]`(가산), warnings=문서별 union, specVersion=합성, format=단일이면 그것·다중 null. 단일-문서 소비자 호환.
- **무회귀**: 단일 default=현행, upload 가 같은 name 만 비활성(다른 name 보존), 매처캐시/warnings/low_confidence/EndpointHistory(specKey 동일) 정합. 기존 SpecStore/JobService 테스트 default 경로 무변경.
- **병합 전략 옵션(보강 2026-06-25, doc/26 §5 — 제안·사용자 확인 대기, dev 미착수; D36 데이터 모델 통합과 함께)**: 사용자 요구로 "항상 MERGE"에 모드 선택 추가. **권장안** — 위치: `DomainConfig.specMergeStrategy`(영속, 기본 MERGE, DomainController DTO 가산=엔드포인트 0). 모드 3종: **MERGE**(현행 flat union)·**SEPARATE**(업로드가 형제 비활성→새 문서가 권위 전체 목록=교체, pre-doc/26)·**VERSION_GROUPED**(매칭은 union 동일, version/specName 그룹으로 **결합 뷰만 분리**=경량, 병렬 인벤토리 미채택). 충돌: latest-upload-wins(비-deprecated)+deprecated OR. 무회귀: 기본 MERGE=현행, 모드 전환만 재병합·invalidate·bump; 전 모드 매칭셋·specKey·host 키 불변축으로 doc/15·24·25 정합. case×mode 표·권장요약 doc/26 §5/§9.
- **범위 밖**: provenance(sourceRef 결합)·spec query-param 확장·중앙 멀티-spec 오케스트레이션(P4)·문서 간 호환성 경고(후속)·완전 독립 병렬 인벤토리(VERSION_GROUPED heavy 대안).

### D36. 검출 SoT 테이블 + version 차원 + host 조회 (doc/26 통합 재설계) — 제안·확인 대기
사용자 신규 요구로 멀티스펙(D35)이 데이터 모델 재설계로 확장. **제안·사용자 확인 대기(dev 미착수). 각 선택 권장안 명시.**
- **검출 전용 테이블 `discovered_endpoint`(A)**: 검출 API 를 spec_record 와 **대칭 분리**한 정규화 SoT. 컬럼 host/method/pathTemplate(unique)·templateSource·endpointKind·version·firstSeen/lastSeen/lastScanAt·hits·statusDistJson·params(@Lob). 누적 upsert(firstSeen min/lastSeen max). 카디널리티 cap(5000)+retention prune(stale 삭제). **`EndpointHistory`(doc/24) 흡수**(firstSeen/lastSeen 이관, severity recency=discovered_endpoint.firstSeen) → endpoint_history 제거.
- **SoT 분담**: spec_record=업로드 SoT / discovered_endpoint=검출 SoT(누적) / scan_result.reportJson=**파생 분류 뷰**(두 SoT⊕Classifier, /result ETag, 재생성 가능). 검출/업로드 중복 정리.
- **version 차원(C, DB 반영)**: 검출=`discovered_endpoint.version` 컬럼(path `^v\d+$`/매칭 spec 도출, index (host,version)); 업로드=`spec_record.specName` 컬럼(문서/그룹)+canonical.version(endpoint). host 내 버전 그룹 조회.
- **host 조회(신규 제약)**: 전 테이블 host 키/인덱스(discovered_endpoint host index+unique(host,method,template), spec_record host+specName, scan_result/domain_config PK host). host 단위 (검출∪업로드) 결합 조회·버전그룹. 논리 FK host.
- **결합·불변(D)**: Classifier(discovered ∪ active spec) **불변** — Shadow/Active/Zombie/Unused, 두 출처 분리 유지. 결합 Discovery 응답=host 단위 하나의 목록(+VERSION_GROUPED 그룹 구조). **분류 범위 권장=per-scan 유지(무회귀)+누적 카탈로그 결합 뷰 신설(둘 다)**; 누적-분류는 staleness 처리 필요라 확인 후.
- **무회귀/마이그레이션(E)**: 기본 MERGE+단일=현행. ddl-auto 신규 테이블(기존 무영향). EndpointHistory→discovered_endpoint **재구축 이관 권장**(콜드스타트=현행 severity, doc/24 폴백). ETag 결정적·시간非의존(lastSeen 은 카탈로그 표시만, ETag 비포함; severity→band·params→이름집합 투영 doc/24/25 유지).
- **단계화**: 1)데이터모델(A/C) 2)멀티스펙+모드(B,D35) 3)결합·버전그룹(C/D). 권장 요약 doc/26 §9.
- doc/18 sync(discovered_endpoint·spec_name·endpoint_history 제거)=technical_writer 후속.
- **진행(2026-06-25)**: 사용자 확정(3모드·EndpointHistory 흡수·한 PR 전체, 단계별 커밋 누적). 브랜치 `feature/multi-spec-merge`, 단계별 커밋·리뷰 대기.
  - **1단계(데이터모델 A/C) 완료**(build 그린 tests=297→P3 보강 298) — `discovered_endpoint` 엔티티/repo, 누적 upsert+cap(5000)/retention(180d) prune, version 도출(path `^v\d+$`→spec.version), `spec_record.specName` 컬럼, **EndpointHistory→discovered_endpoint.firstSeen 흡수**(severity recency=검출 signature 키, endpoint_history/observedTimes/EndpointObservation 제거, 콜드스타트=현행 무회귀).
  - **2단계(멀티스펙+모드 B, D35) 완료**(build 그린 tests=305) — `model/SpecMergeStrategy`+`DomainConfig.specMergeStrategy`(기본 MERGE, null→MERGE)+DTO 가산. `SpecStore` 모드 분기(`upload(host,name,content)`: SEPARATE=전체 교체/MERGE·VG=같은 specName 만·형제 유지; default 위임=현행). `SpecCanonicalizer.merge` 결정적(dedupe+deprecated OR+비-deprecated latest-specVersion-wins·tie sourceRef·순서 무관, 단일=canonicalize 동치). 합성 spec 버전=merged canonical SHA-256(EtagUtil 앞 16hex=64bit→long, ETag 일관)→report/SpecSource/matcherCache(동일 콘텐츠=동일 버전). **한계**: 멀티문서 SpecSource format/warnings union·documents[]·SpecController name REST·버전그룹 뷰=3단계.
  - **3단계(결합·버전그룹 C/D, D36) 완료**(build 그린 tests=310) — `CombinedDiscoveryService`(누적 discovered_endpoint 재구성 ∪ active spec → Classifier 불변 5-arg → 결합 findings)+`model/CombinedDiscovery`+`GET /api/v1/domains/{host}/discovery`. VERSION_GROUPED=version 라벨(`VersionTag` path `^v\d+$` ∪ spec version) 그룹·그 외 flat. 분류 범위=per-scan 유지(무회귀)+누적 카탈로그 뷰 신설(둘 다). `SpecSource`+documents/format-union/warnings-union(`SpecStore.specSourceFrom`, 2단계 이월 완료). 한계: 카탈로그 distinctClients/p50·p95/acrm 미보유→결합 뷰 confidence 근사(분류 무영향), 원 카탈로그 list REST=결합 뷰로 충족·P4 생략.
  - **구현 1~3단계 완료**. 잔여: doc/18 sync(technical_writer) + 최종 리뷰 + 1 PR 머지(D28).

### D37. 매칭 엣지 케이스(doc/04 §7) 회귀 테스트 — 갭 분석 + 2 플래그
doc/04 §7 9 케이스를 회귀로 잠금(순수 테스트, 프로덕션 무변경). 갭 분석: 대부분 기존 테스트가 이미 잠금(doc/16/19/20 PR) → **매처 계층 소수 갭 + 2 코드 플래그**만 신규.
- **문서 위치(판단)**: 신규 doc 대신 **doc/04 §7.1 '회귀 테스트 매핑' 보강**(케이스↔불변식↔테스트↔상태 co-location, 가벼움).
- **테스트 위치(판단)**: 신규 클래스 대신 **기존 클래스 확장**(EndpointMatcherTest/ClassifierTest) + `// doc/04 §7 case N` 태그(추적성·중복 회피).
- **신규 테스트(미커버 갭)**: ① 동일 path 양 method 정의 → 각자 distinct 매칭(현 mismatch 만) ② INFERRED 단독 → shadowConfidence −0.1 격리(현 번들) ③ specificity front-segment 우선·동률(현 `/users/me` 1건). 나머지(host-agnostic·404-only·version-zombie·undocumented_web_page·dormant)=기존 커버 → 중복 회피.
- **플래그(현행 미구현/버그 — 테스트로 고정 금지)**: **F1** base-path-strip(doc/03 §2.2·§7 명시) **미구현** — OpenApiSpecParser 가 basePath 를 템플릿에 join → 프록시 strip 관측은 불일치 → false Shadow. **F2** catch-all `{var+}` 분기가 매처에 있으나 파서 미생성(도달 불가) + segCount 버킷팅이 `.+` 다중세그먼트 차단(도달 시 오동작). → 둘 다 TASKS 후속(F1=한계/옵션, F2=dead code 정리 vs 의도), 회귀 테스트 아님. **F1 해소 → D38/doc/27. F2 해소 → D39.**

### D38. base-path-strip — false Shadow 방지 (D37 F1 해소, doc/27) — 제안·권장안
프록시가 base path prefix 를 strip 하는 환경에서 basePath-결합 템플릿 vs strip 관측 불일치 → false Shadow/Unused. **(a) 옵션 구현 권장**(기본 off=무회귀), (b) 문서화만 미채택.
- **옵션 위치/형태**: **`DomainConfig.basePathStrip`(String, nullable, 기본 null)** = 프록시가 제거한 base prefix(예 `/v2`)를 operator 명시. specMergeStrategy 와 동일 패턴(per-domain, DomainController DTO 가산). MatcherConfig 아님(그건 ApiHintMatcher 게이트용; basePathStrip 은 spec EndpointMatcher 라우팅). 자동 감지 미채택(fragile·strip≠basePath 케이스).
- **적용 지점 = at-match additive**: canonical 불변(basePath 결합 유지, SoT 보존). `EndpointMatcher.match(...,stripPrefix)` 가 **as-is 우선, 미매칭+prefix 시 `stripPrefix+path` 재시도**. strip=match 호출 파라미터(matcher 비포함) → matcherCache(host,specVersion) 불변·변경 시 무효화 불요, 재파싱 불요(canonical 그대로). 멀티스펙(doc/26) merged 매칭에 도메인 레벨 일괄.
- **doc/03 §2.2 정제**: 문서의 "parse-time join 토글" 대신 at-match strip prefix — 재파싱·SoT 손실 회피·임의 prefix 일반성. 목표 동일.
- **비대칭/무회귀**: 기본 null=as-is=현행. 설정 시 가산(시도 추가만)→false Shadow→Active·false Unused→observed 교정, 기존 매칭 불변(as-is 우선). 잘못 prefix=opt-in operator 오류 한정.
- **ETag**: canonical 불변→specVersion 무변경. 설정 시 findings 변화→ETag bump(정당·결정적·시간非의존, now 무관). basePathStrip 은 findings 반영이라 ETag 입력 별도 불요.
- **무회귀**: 3-arg match 오버로드·null stripPrefix 하위호환. doc/18 sync(`domain_config.base_path_strip`)=technical_writer. 후속: 다중 prefix List·자동감지(미채택)·parse 토글(대안).

### D39. catch-all `{var+}` dead code 제거 (D37 F2 해소, doc/04 §1.1) — 권장
`EndpointMatcher` 의 catch-all 분기(`isCatchAll`→`.+`)는 **도달 불가 vestigial code** → **(a) 제거 권장**, (b) 유지+주석 미채택.
- **근거(제거)**: ① 파서 3종(OpenApi/Postman/CSV) 어느 것도 `{var+}` 미생성(grep 0) → `isCatchAll` 항상 false. ② `(method,host,segCount)` 버킷팅이 다중 세그먼트 `.+` 를 구조적 차단(후보가 정확 segCount 버킷에서만 옴 → `.+` 의 멀티세그 의도 도달 불가). ③ 도달 가능한(동일 segCount) 요청에선 `.+` ≡ `[^/]+`(단일 세그먼트엔 `/` 없음) → 제거해도 **동작 불변**. YAGNI(CLAUDE.md §2).
- **범위**: `isCatchAll` 분기(compile `.+`) + 헬퍼 삭제. 제거 후 `{var+}` 는 `isVariable`→`[^/]+`(단일 세그먼트 변수)로 일관 처리. 타 사용처 0(grep, SensitiveKeyMatcher 등 무관)·관련 테스트 0 → 회귀 없음, 기존 `EndpointMatcherTest` green.
- **설계 결함 명시(#4)**: catch-all 은 doc/04 §1.1 에 '옵션'으로 문서화됐으나 `.+` 구현이 **segCount 버킷팅과 충돌**(가변 세그먼트 수 매칭 불가)해 처음부터 비기능. 진짜 catch-all 지원은 **버킷팅 재설계**(템플릿 segCount 이상 버킷 스캔 등)가 필요한 **별도 기능** — 이번은 vestigial 제거만. doc/04 §1.1 을 '미지원'으로 갱신.
- **(b) 미채택**: 도달 불가·비기능 코드 유지는 오해 유발(catch-all 작동 오인). 설계 지식은 doc/04 §1.1·본 결정에 보존. (catch-all 실수요 발생 시 별도 기능 항목으로.)

### D40. Testcontainers(PostgreSQL) 통합 테스트 — podman 연결·Ryuk·게이팅 (doc/28) — 채택(구현·머지 완료)
H2 단위테스트가 못 잡는 **실 PG 매핑/동작**을 검증(L52 `@Lob String`→`text` + L53 JPA/REST/304 e2e). **묶음 단일 PR**. **무회귀(빌드 green) 최우선**.
- **podman 연결(권장)**: `tasks.withType<Test>` 에서 `DOCKER_HOST` 주입 — `unix://$XDG_RUNTIME_DIR/podman/podman.sock`(uid 하드코딩 금지·XDG 파생), **가드**(DOCKER_HOST 기설정·소켓 부재 시 미개입→실docker/無docker 무영향). 리포 커밋·재현 가능. 대안 `~/.testcontainers.properties`(uid 하드코딩·per-dev)·베이스클래스 static init(로딩순서 취약) 미채택.
- **Ryuk 비활성**(`TESTCONTAINERS_RYUK_DISABLED=true`): rootless podman 호환 마찰(소켓 mount) 회피. 트레이드오프 — 정상종료 정리는 Testcontainers shutdown hook 이 수행, Ryuk 은 강제 kill 백스톱일 뿐 → 누수는 ephemeral CI + `podman prune` 로 무해. 단순성 우선.
- **게이팅(권장)**: `@Testcontainers(disabledWithoutDocker = true)` — docker/podman 부재 시 클래스 auto-skip(빌드 green), 기존 319 H2 단위테스트 무영향, gradle/sourceSet 변경 0. 대안 `assumeTrue(isDockerAvailable)`·`@Tag`·별도 sourceSet 는 후속/병용(선택).
- **패턴/ddl-auto**: `@SpringBootTest`+`@ServiceConnection` 싱글톤 `postgres:16-alpine`(로컬 pull), `ddl-auto=create-drop`(마이그레이션 없어 validate 불가, ephemeral 에 Hibernate 가 엔티티→PG DDL 직접 생성=L52 검증대상), `@MockBean LokiClient`(운영 Loki 실호출 차단, doc/11 선례).
- **검증 대상**: ① `@Lob String` **9컬럼**(classification 4·report 1·canonical/spec 2·discovered 2) `information_schema.data_type='text'` 단언 + 대용량 round-trip ② `raw_doc`(@Lob **byte[]**) 는 별도(통상 `oid` 매핑 → `text` 단언 금지, round-trip+실타입 기록) ③ `GET /discovery` e2e ④ `GET /result` 304.
- **현행 버그 고정 금지(D37 원칙)**: 9컬럼이 `text` 아닌 `oid`/`bytea`/`varchar` 로 나오면 **실결함** → 테스트 느슨화 금지, 엔티티 수정(`columnDefinition="text"`/`@JdbcTypeCode`) 별도 보고 후 해소. 테스트 가치 = 분기 노출.
- **구현 결과(2026-06-25, 실결함 확정·수정)**: 9컬럼 전부 PG `oid`(large object) 매핑이었고, 비트랜잭션 경로(`CombinedDiscoveryService.forHost` → `/discovery`)에서 `Unable to access lob stream`(auto-commit LOB) 발생 = 실 운영 결함. D37 원칙대로 테스트 유지하고 5엔티티(`ClassificationConfig`·`DomainClassificationConfig`·`ScanResult`·`SpecRecord`·`DiscoveredEndpointRecord`) 9 String 필드 `@Lob`→`@Column(columnDefinition = "text")` 수정 → PG `text`·정상 `setString` 바인딩(LOB stream 없음)·H2 호환(`text` alias, totalDropped 와 동일 columnDefinition 패스스루 선례). `raw_doc`(@Lob byte[])은 범위 밖 — 실측 `oid` 유지(round-trip 만 검증).
  - **수정 방식 선택 근거**: `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` → PG `varchar(32600)`(유한, 대용량 초과)·`LONG32VARCHAR` → 바인딩 시 PG 드라이버 `Unknown Types value`(seeder insert 부팅 실패) 둘 다 부적합. `@Column(columnDefinition="text")` 만 text·정상 바인딩·H2/PG 양립 충족.
  - **검증**: PG 통합테스트 13건 실행·green(skip 0), 총 332 실패 0(skip 1=LokiLive), bogus `DOCKER_HOST` 로 클래스 auto-skip(무회귀 게이팅 확인). doc/18 스키마(@Lob String 컬럼 정의) text 반영은 technical_writer 후속.
  - **후속(2026-06-26, 실배포 발견·동일 사유)**: `discovered_endpoint.path_template`(varchar(255))가 일부 정규화 경로(긴 REST·다세그먼트, >255)에서 `value too long for type character varying(255)` → insert 실패·엔드포인트 유실. 경로는 임의 길이라 `@Column(columnDefinition="text")` 부여(varchar 상한 회피, 동일 패턴). unique(host,method,path_template)에 text 포함은 PG/H2 모두 허용. 검증=PostgresIntegrationTest path_template `text` 단언(파라미터화 10번째) + >255 round-trip. (다른 varchar(255) 필드 method·host·version 등은 짧아 무관 — surgical.) VM 기존 컬럼은 ddl-auto update 가 타입 미변경 → 재배포 시 매니저가 `ALTER COLUMN TYPE text`(또는 테스트데이터 drop+recreate) 마이그레이션.

### D41. 엔티티 캡슐화 — public 필드 → private + 접근자 (doc/29) — 채택(구현·머지 완료)
스캐폴딩 7 `@Entity`(약 63 public 필드)를 캡슐화. **행동·매핑·직렬화 불변 절대조건**(332 테스트 green). P2 마무리.
- **접근자(권장)**: **수기/IDE 생성 getter·setter**(신규 의존 0, 전역지침 §2·§3, 기존 컨벤션 일치). 보일러플레이트는 IDE "Encapsulate Fields" 로 상쇄. **Lombok 미채택** — 정리 리팩터 부산물로 컴파일 의존+애너테이션 프로세서+전 엔티티 컨벤션 전환을 끌어들이지 않음(원하면 별도 심의, D41 범위 밖).
- **JPA 접근타입 보존(★)**: 애너테이션을 **필드에 그대로 유지**(getter 이동 금지) → field access 유지 → 매핑/DDL/ddl-auto/컬럼타입 불변. 특수 매핑(`@ElementCollection hostnames`·`@Lob byte[] rawDoc`·text 9필드 `columnDefinition`·`@GeneratedValue`·`@Enumerated`·필드 초기화자) 필드 고정. getter 이동=property access 전환=리스크라 금지.
- **직렬화/ETag 무관(조사결과)**: 엔티티는 **Jackson 직접 직렬화 경로 없음** — 엔티티 Jackson 애너테이션 0, 전 컨트롤러가 DTO/뷰/모델 반환(엔티티 직접 반환 0), 모델/DTO 가 엔티티 미포함, `EtagUtil.of(String)` 입력은 `DiscoveryReport`/canonical **모델** 직렬화물. → 접근자 네이밍/순서가 JSON 계약·ETag 불변경. 원칙: 값 그대로 반환·boolean `isX()`·필드당 1쌍·파생 getter 금지.
- **setter 범위**: 전 필드 getter / `@GeneratedValue` 자동생성 id 2개(SpecRecord·DiscoveredEndpointRecord) 제외 전 필드 setter(자동생성 id=앱 미기록·field access → 불변 신호). 진짜 불변(생성자 강제·setter 제거)은 더 큰 재설계로 범위 밖. equals/hashCode 무신설(동작 변화·범위 밖).
- **진행**: 엔티티 1개씩 스테이지 + 단계별 `build` green(리뷰·bisect·롤백), 단일 PR. 순서=블래스트 반경 오름차순(Watermark→config 쌍→DomainConfig→SpecRecord→ScanResult→DiscoveredEndpointRecord). churn 대부분은 테스트(~9파일, public 필드 직접 대입), IDE Encapsulate Fields 가 호출측까지 변환.

### D42. 도메인 자동 디스커버리 — Loki 서버측 집계·coalesce·무삭제 업서트·가드 (doc/30) — 채택(A 구현·머지 완료)
access log 에서 API 도메인을 자동 열거해 `DomainConfig` 부트스트랩/증분. 인프로세스 `@Scheduled`(별도 스케줄러), 원시 로그 미수신·서버측 메트릭 집계. P3.
- **LogQL(서버측 집계) — ★coalesce 위치 정정(성능)**: 최초 설계는 서버 `label_format domain=coalesce(...)` 였으나 **실 Loki 측정상 10배 느림**(`count_over_time(...|pattern [5m])` 2.2s → `|label_format` 추가 시 20.2s, Go 템플릿 라인별 평가 → query-timeout 30s 초과·디스커버리 실패). → 확정형: `sum by (host, real_host, hostname)(count_over_time({job="access_log"} | pattern <필드15 host·16 real_host, DELIM ^|^> [W]))` (서버 label_format·domain 필터 제거). **coalesce(host 빈/"-"→real_host)·도메인 필터·FQDN 검증은 클라이언트(Java `DomainDiscoveryService`)** — `firstNonEmpty(normalizeDomain(host),normalizeDomain(real_host))`, **LogLineParser line 83 와 동일 의미**(full fidelity, 사용자 확정). 같은 도메인이 여러 (host,real_host)·hostname 으로 나오면 클라이언트가 coalesce 후 domain 키로 합산(hostnames 합집합·count 합). `hostname` 라벨로 엣지 집합 확보 → `DomainConfig.hostnames` 자동채움. 응답=집계 벡터(라인 미전송), label_format 제거로 서버 CPU·지연 ~10배 경감.
- **LokiClient 확장**: instant 벡터 쿼리(`/loki/api/v1/query`) 메서드 신설, `requestWithRetry` URL 인자형 추출로 throttle/concurrency(max 2)/백오프/timeout **재사용**(우회 금지·queryRange 불변).
- **스케줄러**: 신규 `DomainDiscoveryScheduler`(@Scheduled fixedDelay 기본 10분 + initialDelay stagger), 기존 per-domain 스캔과 분리·저우선(concurrencyGate 공유로 양보). 동적 주기는 fixedDelayString 으로 충분(런타임 동적=YAGNI).
- **윈도우**: 롤링 W≈interval+오버랩, 부트스트랩 1h 1회(빈 DB/플래그, 사용자 확정) 후 롤링. DELIM/F_HOST=15/F_REAL_HOST=16 이 Java(LogLineParser)·LogQL 양쪽 중복 → doc/02 §1.1 단일근거 + 포지션 교차검증 테스트로 드리프트 차단.
- **업서트(★무삭제·설정보존)**: 신규 INSERT(host+hostnames+기본값), 기존 UPDATE(hostnames 합집합·lastSeenAt 갱신만). **사용자 설정(basePathStrip·specMergeStrategy·enabled·intervalOverride) 덮어쓰기 금지, 도메인/설정 자동삭제 절대 금지**(수동만). `DomainConfig.discoveredAt`/`lastSeenAt` 가산(ddl-auto, lastSeenAt=윈도우 끝 데이터 ts). DomainConfig 직렬화 경로 없음→ETag 무관.
- **P3-1(리뷰 2회 반영·정정)**: 설정 lost-update 방지 = `@DynamicUpdate` + **managed 엔티티 단일 트랜잭션**(둘 다 필요). 1차 시도(서비스 비-@Transactional·repo 기본 tx)는 무효였음 — findById/save 가 별 tx → detached → `save=em.merge` 가 stale 전 필드 복사 → 설정 컬럼 dirty → @DynamicUpdate 가 stale 값 UPDATE 포함 → 덮어씀(단일스레드 테스트가 가린 결함). 정정: 별도 빈 `DomainUpserter.@Transactional upsert()`(self-invocation 회피)로 `findById→mutate→커밋` 한 tx → entity managed → dirty-check 가 load 스냅샷 기준 → 설정 컬럼 비-dirty → @DynamicUpdate UPDATE 제외. `discover()` 는 비-@Transactional 유지(Loki tx 밖). `@Version` **미도입**(D18 §5 일관). 한계: 동시성 배제는 단일스레드 결정적 테스트 불가 → 구조+주석+실 PG 정상경로 통합테스트로 고정.
- **가드(폭증 차단)**: ① 호스트 FQDN 정규식 검증(신규 — LogLineParser 는 `-`/빈값만 거르고 형식 미검증) ② max-domains-per-run 상한(카운트 desc) ③ 서버측 topk(선택) ④ 셀렉터 `{job="access_log"}` 단일→타임아웃 시 hostname 라벨 분할 폴백(측정 후).
  - **정정(사용자 지시 '제한 없음')**: `max-domains-per-run` 기본 **0=무제한(전수 등록)**, `>0` 만 상위 N 캡·드롭 로그. 근거: 이 캡은 **DB 업서트량만 제한**(벡터는 이미 수신)이라 Loki 부하 무관 → 제거해도 Loki 영향 0. 무제한 시 1회 ~14k upsert(대부분 lastSeenAt UPDATE) = PG 감당. 폭증 가드는 ①FQDN ③topk(선택)으로 충분.
- **리스크(정직)**: 필드 레이아웃 LogQL 중복·`label_format` coalesce 실 Loki 1회 확인 필요·서버 파싱 CPU·LAN egress. 무회귀: 신규 가산만, `discovery.enabled=false`=완전 비활성.

### D43. 테스트 배포 — CLI CSV 내보내기 + Docker/podman 패키징 (doc/31) — 채택(B·C 구현·머지 완료)
동일 bootJar 의 CLI 모드로 한 도메인 결합 Discovery 를 CSV 로 내보내고(B), app+postgres 2컨테이너 pod 로 테스트 배포(C). P3. 사용자 확정: PG 컨테이너·PGDATA→`/opt/adc`·CLI 는 지금 1 명령만(YAGNI).
- **CLI 모드(B)**: `--adc.cli.export-domain=<domain>` 감지 → `SpringApplicationBuilder.web(NONE).profiles("cli")`. `@EnableScheduling` 을 `SchedulingConfig(@Profile("!cli"))` 로 분리해 CLI 시 스케줄러/Loki 미기동(서버 모드 동일 활성=무회귀). `CliExportRunner`(CommandLineRunner) forHost→CSV→exit code. picocli 등 CLI 프레임워크 미도입(신규 의존 0, 단순 인자 분기만).
- **CSV 스키마(architect 확정)**: 소스=`CombinedDiscoveryService.forHost`(List<Finding> 5 subtype). 컬럼=host·method·path_template·status·source(파생: Shadow→detected/Unused→spec/Active·Zombie→both/WebPage→detected)·confidence·severity·estimated·spec_ref·preflight_ambiguous·low_confidence·param_query·param_path·first_seen·last_seen. 헤더행·RFC4180. **first/last_seen 은 Finding 미보유→discovered_endpoint (method,host,template) join**(spec-only 공란). **score 미영속→범위 밖**.
- **출력 위치(★PGDATA 충돌 회피)**: 확정 마운트 `-v /opt/adc:/var/lib/postgresql/data`(=/opt/adc 전체 PGDATA) → exports 를 그 내부(`/opt/adc/exports`)에 두면 initdb 충돌 → exports 는 **PGDATA 밖 별도 경로**(예 host `/opt/adc-exports`→`/exports`). 확정 PGDATA 마운트는 유지.
- **토폴로지(★localhost 정정)**: podman **pod 는 netns 공유 → 컨테이너간 `localhost`**(컨테이너명 DNS `db` 미해석). app `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/adc`. (manager `db:5432` 는 비-pod user-defined network 에서만 성립 → pod 선택 시 localhost. **pod+localhost 권장**.) 배포 산출물 = `podman play kube adc.yaml`(선언적, 추가 툴 불요) 권장 / pod 스크립트 대안 / podman-compose 비권장(툴 부재 가능).
- **CLI DB 접속**: one-off `podman run --rm --pod adc -v /opt/adc-exports:/exports` (localhost:5432·서빙 무부하) 권장 / `podman exec` 대안.
- **프로파일**: `application-container.yml`(PG driver·ddl-auto update·Loki LAN, doc/28 PR#20 PG text 검증), `SPRING_PROFILES_ACTIVE=container`. 기존 `application.yml`(H2) 불변→로컬·332 테스트 영향 0. Dockerfile 멀티스테이지(bootJar→temurin:21-jre).
- **리스크(정직)**: localhost-vs-db·exports/PGDATA 충돌(해소)·컨테이너→LAN Loki 도달 1회 확인·CLI 2nd JVM 경합·score 미영속. 운영 Loki 부하보호·off-peak 문구 포함.

### D44. 테스트 배포 운영 수정 — 실 VM 배포에서 드러난 결함 5종 (doc/32, feature/container-wait-for-db) — 채택(실배포 검증·머지)
실 테스트 VM(192.168.8.197, Rocky 8.10, rootful podman, SELinux Enforcing)에 배포하며 잡은 결함과 채택 수정.
- **이미지 개별 전송(★운영절차)**: `podman save img1 img2 -o one.tar`(결합) → load 시 두 태그가 한 이미지로 collision(postgres 태그가 app 을 가리킴→app 이 빈 DB 접속→113회 크래시 루프). **이미지는 개별 tar 로 save/load**(또는 VM 에서 pull). doc/32 §1 경고+로드후 ID 분리 검증.
- **wait-for-db(기동 순서)**: pod 동시 기동 시 postgres initdb 지연으로 app 이 DB 미준비 접속→실패→재시작 폭주. `wait-for-db.sh`(bash `/dev/tcp` 로 `localhost:5432` 바운드 대기 후 `exec java … "$@"`)를 ENTRYPOINT 로. initContainer 는 단일 pod 에서 db 가 일반 컨테이너라 부적합. 바운드(120s) 후 진행→무한대기 회피, 잔여는 재시작정책.
- **hostNetwork(LAN Loki egress)**: 기본 bridge pod 는 LAN 운영 Loki(192.168.8.100:3200)에 connect timeout. `adc.yaml hostNetwork: true` → host 네트워크로 egress(검증 LOKI-OK), app↔db 는 동일 host netns `localhost:5432`. 트레이드오프: postgres 5432 가 VM 인터페이스 노출(내부 테스트 VM 수용, 공유 시 방화벽 하드닝 선택).
- **coalesce 클라이언트 이전(쿼리 성능)**: 서버측 `label_format domain={{coalesce}}` Go 템플릿이 라인마다 평가돼 ~10배(실측 5m 2.2s→20.2s)→1h 부트스트랩·12m 롤링 모두 query-timeout(30s) 초과. LogQL 을 `sum by(host,real_host,hostname) count_over_time(…pattern…[W])`(label_format 제거)로 바꾸고 coalesce 를 클라이언트(`firstNonEmpty(normalizeDomain(host),normalizeDomain(real_host))`, LogLineParser 동형)로. 운영 Loki 부하도 감소. D42 갱신.
- **SecurityConfig 웹 한정**: CLI(`web=NONE`)에서 `SecurityConfig.filterChain(HttpSecurity)` 가 웹 전용 빈 의존으로 기동 실패. `@ConditionalOnWebApplication`(웹만 로드). 서버 모드 무회귀.
- **검증**: Restarts 113→0·health UP·디스커버리 `vector=35872 inserted=200`(domain_config 200, timeout 없음)·CLI 정상 기동·361 테스트 green. **한계**: `max-domains-per-run` 캡(관측 ~13,920 도메인 중 상위 200만 등록, 캡은 DB 업서트량만 제한·Loki 부하 아님→상향 저위험, 사용자 결정). 엔드포인트 스캔(PT1H)·CSV 내용은 다음 사이클 후속.

### D45. 엔드포인트 스캔 Loki 부하 운영정책 (A–F) + 운영자 온디맨드 스캔 CLI (doc/33) — 채택(PR1 B+A+E+온디맨드 CLI 구현·머지, C/D/F 후속)
디스커버리 무제한 등록(~13,920+ 도메인) → 현 `DiscoveryScheduler`(@Scheduled PT1H 전수순회)가 매 사이클 14k 스캔 = 운영 Loki 지속 과부하. 사용자 승인 A–F 정책 + 매니저 추가요구(즉시 스캔 CLI) 구현화. P3, 단일 인스턴스 전제.
- **B(필수) 틱당 예산+라운드로빈**: 짧은 틱(tick-interval PT5M)마다 `domains-per-tick` K 내에서 **least-recently-scanned**(`DomainConfig.lastScanAttemptAt` asc nulls-first) 처리·이월. 커서=영속 타임스탬프(재기동 생존, 메모리 인덱스 미채택), **attempt 마다 전진(skip 포함)** — up-to-date 재선택 방지. 전수순회→슬라이스순회. watermark(데이터 진행)와 직교(공정성 커서).
- **A(필수) watermark 증분 확정 + per-scan 윈도우 상한**: 증분은 동작 중(skip-if-current·advance-on-success) → **`max-window` 상한**으로 백필 슬라이스화(미스캔 도메인 first window=end−initialBackfill P7D=7일/10분≈1008쿼리 일괄 pull 차단, max-window 씩 여러 틱 점진). 정상상태 window=직전 이후 경과분(자연 작음). 저티어 staleness(실시간 대비 ~사이클 뒤처짐)는 디스커버리에 허용, off-peak 가속. line 105 TODO 해소.
- **E(필수) 전역 레이트 가드+자동감속**: LokiClient 보호(동시2·200ms·page-limit·백오프) 위에 `LokiBudget`(시간당 쿼리/바이트 하드캡, 초과=틱 이월) + 429/5xx **적응형 throttle**(지속 감속·성공 감쇠) + Micrometer 계측(쿼리·바이트·429, P3 메트릭 TODO 연계). 바이트/경계 근사 한계 명시.
- **C(다음) 활동 티어링**: lastSeenAt/활동 → active PT30M/inactive PT6H+ due 판정, 기존 `intervalOverride` TODO 배선. **D(다음) off-peak 백필**: `schedule.off-peak-window`(현 config-only) 배선 — off-peak 백필 큰 예산/윈도우, peak 델타·active 우선. **F(보조) dormant 디프라이오리티**: N일 무트래픽→최장 주기(삭제 아님, 무삭제 일관).
- **온디맨드 CLI(매니저 추가)**: `--adc.cli.scan-domain=<domain>` [--window/--edge] — runOnDemand 재사용, B 패턴(@Profile cli·web NONE). ★**watermark 미전진**(임시 윈도우만 — 전진 시 스케줄러 [lastEnd,now-win) skip=데이터 갭). 요약(검출수)·exit code. scan→export 는 독립 명령 순차 조합(체인 플래그 YAGNI).
- **스테이지 권고**: PR1=필수 B+A+E(+온디맨드 CLI 동봉, 과부하 즉시 해소·격리 리뷰) / PR2=C+D(최적화 적층) / PR3=F(선택). HA=단일 인스턴스 전제(복수 시 ShedLock+예산 DB-backing, 기존 HA TODO). 리스크(정직): 전수 커버리지 시간↑(14k/100×5m≈11.7h, 노브 조정)·백필 수렴·기아(FIFO 무기아)·메트릭 근사.

### D46. PR1.1 — 스캔 per-domain 폭주 수정 (실배포 발견, doc/33 §14) — 채택(구현·머지)
PR1 실배포 검증: 단일 busy 도메인(www.takigen.co.jp)의 max-window PT6H 백필이 `query_range` 1500+ 쿼리(≈300만 줄) 미완 → LokiBudget 독점 + 단일 @Scheduled 스레드 점유 → 타 도메인·디스커버리 기아(discovered_endpoint=0). 부하 자체는 throttle/budget 으로 묶임(429=0) — 문제는 진척 0+기아. 근본: 스캔=라인 페이지네이션(집계 아님), PR1 이 도메인-granularity 예산만 묶고 **단일 runScan 쿼리량 무제한 + 단일 스레드**.
- **★권장 = ①+②+③ 함께**. "② 소윈도우 단독 충분?" → **불충분**(하드 천장 없음 — 하이퍼busy·D off-peak 대형윈도우·백필서 재발). ① 가 구조적 보장이고 D 안전 전제.
- **① per-scan 하드캡 + 슬라이스-granular 부분 watermark 전진(핵심)**: 부분전진 채택(윈도우 축소 재시도는 busy 도메인 영구 미진척=기아). 멀티-hostname gap-free 위해 **루프 반전**(슬라이스 외부·hostname 내부) — 슬라이스(=chunk-window PT10M) 모든 hostname 완료 후에만 watermark 그 슬라이스 끝 전진. `max-queries-per-scan`(예 50) 슬라이스 경계 캡 + `budget.hasBudget()` 슬라이스 체크(④ query-granularity 흡수) → 초과 시 마지막 완료 슬라이스까지 analyze+전진 후 종료(resume). floor: 단일 슬라이스 1 hostname 다페이지는 원자(slice-window 축소로 완화).
- **② max-window 기본 PT6H→PT30M**: 흔한 per-scan 축소. 트레이드오프=백필 커버리지↑(D off-peak `off-peak-max-window` 상향으로 가속, 그때 ① 가 안전). 0/null=무제한 유지.
- **③ 스레드 격리**: `spring.task.scheduling.pool.size=2`(또는 TaskScheduler @Bean) → scanTick·discover 별 스레드(fixedDelay 자기직렬화 유지). SchedulingConfig 기본 풀=1 이 기아 원인.
- **변경**: `DiscoveryJobService.runScan`/`collect` 슬라이스 순회·부분전진(`collectBounded`→{lines,consumedUpTo}), analyze/분류 불변. 무회귀: max-queries-per-scan=0=현행, 슬라이스 순회 결과동일(dedup), watermark 데이터 ts 결정적, 풀=2 동작불변.

### D47. 운영자 CLI 문법 — 단일대시 `-domain` 서브커맨드 통일 (doc/33 §15) — 채택(목록 머지 후 전면 통일)
운영자 CLI 를 전부 단일대시 `-domain` 서브커맨드로 통일(사용자 확정). 신문법:
- `-domain -ls` — 도메인 목록(stdout, host·enabled·#hostnames·discovered_at·last_seen_at, 빈 목록 exit0·DB오류 비0, Loki 무관)
- `-domain -register <도메인>` — 즉시 등록(DB only·Loki 미호출·멱등, 아래 즉시 처리 확장)
- `-domain -export <도메인>` — CSV 내보내기(doc/31)
- `-domain -scan <도메인> [-window <ISO8601>] [-edge <hostname>]` — 온디맨드 스캔(doc/33 §7, 미등록이면 자동등록 후 스캔)
- **신규 도메인 즉시 처리 확장(2026-06-29)**: `-register` 신규 추가 + `-scan` 미등록 자동등록. 등록 로직은 **공유 헬퍼 `DomainRegistrar.registerIfAbsent`**(register·scan-autoregister 재사용) — 정규화 host 로 없으면 `enabled=true` 등록(멱등). ★기존 seam 미채택 사유: 자동 디스커버리 `DomainUpserter` 는 `discoveredAt=now`(자동 발견 마킹)라 수동 등록(=`discoveredAt=null` 자연 구분)에 부적합, REST `DomainController.create` 는 409·`ResponseStatusException` 라 CLI 부적합 → 최소 신규 @Component(인터페이스/config 없음, 과설계 금지). **`-scan` 시맨틱**: 미등록=`enabled=true` 자동등록 후 스캔(즉시성), **존재·비활성(`enabled=false`)=자동 활성화 안 함→스캔 불가**(운영자 명시 비활성 결정 존중, 자동등록은 '미등록' 한정). scan-domain 도 정규화 일원화(중복키·Loki 쿼리 정합). exit: register 0 성공(이미 존재 포함)/2 누락/4 DB.
- **구현**: `main().parseCli`(순수·테스트 가능) 가 신문법 감지 → 내부 `--adc.cli.list-domains=true`/`export-domain=`/`scan-domain=`(+`window=`/`edge=`)로 translate 후 web NONE·cli 프로파일 부팅. CliProperties·CliExportRunner·CliScanRunner·CliListRunner 런너/바인딩 불변(외부 단일대시 UX·내부 프로퍼티 구동 분리 = 최소 변경). `-domain` 없음=서버 모드, `-domain` 단독·도메인 누락=usage+exit(2).
- **★기존 `--adc.cli.export-domain=`/`scan-domain=` 사용자 트리거 제거**(직접 입력 시 CLI 미진입). 초기 채택은 (a) 혼재 허용이었으나, 목록 CLI 머지 후 사용자가 (b) **전면 통일** 결정 — 아직 외부 출하 전·테스트 단계라 출하 파괴 없음(피스밀 금지 단서대로 전 명령 동시 교체). HTML 매뉴얼 동기는 technical_writer 후속.
- **출력 형식(목록)**: `-domain -ls` 는 **CSV 파일**(`output-dir`/domains-&lt;stamp&gt;.csv, export-domain 동형 다운로드)로 출력 — 사용자 확정(Option B). 최초안의 stdout 표는 폐지(대량 도메인 11k+ 에서 표는 비실용·파일이 export 와 일관). 헤더 host,enabled,hostnames(';'),discovered_at,last_seen_at. 빈 목록=헤더만·exit 0, DB/IO 오류=4. `-v output-dir` 볼륨 필요(컨테이너).
- **갱신 이력**: 최초 D47=목록 `-domain -ls` stdout 표(혼재 허용·전면통일 미채택). → 사용자 결정으로 전면 통일·기존 트리거 제거(feature/cli-domain-subcommand). → 사용자 Option B 로 목록 출력을 stdout→CSV 파일 변경(feature/domain-ls-csv). → `-register` 신규 + `-scan` 미등록 자동등록(공유 `DomainRegistrar`, feat/cli-register-and-scan-autoregister).

### D48. 스캔 정책 PR2/PR3 — C 활동 티어링 + D off-peak + F dormant (통합 due 모델, doc/33 §4–6) — 채택·구현+리뷰반영(1 PR, build green 408·실 PG 가드 PASS·커밋 보류)
PR1(B+A+E) 토대 위 C(티어)·F(dormant)·intervalOverride 를 **단일 due 모델**로 통합, D(off-peak)는 그 위 파라미터 스위치. P3, 단일 인스턴스 전제.
- **★due 계산 = persisted `nextScanDueAt`(신규 필드) + DB 술어**(메모리 필터·순수 SQL 비채택):
  - 메모리 필터 결함 — **LRS 순서 ≠ due 순서**(미due inactive 가 due active 앞에 정렬 → over-fetch 로도 due 누락). 순수 SQL 결함 — tier 는 Duration CASE 산술(JPQL 빈약·H2/PG interval 방언차)·override 는 ISO String(SQL 비교 불가).
  - 채택 — 스캔 시점 Java 로 `effectiveInterval` 계산 → `nextScanDueAt=now+interval` 영속. 선택=`WHERE enabled AND (next_scan_due_at IS NULL OR <= now) ORDER BY next_scan_due_at ASC LIMIT K`(이식·인덱스). C/F/override 가 단일 값 collapse, due 정렬=가장 밀린 순(FIFO·무기아). null=즉시 due.
- **effectiveInterval(C+F+override)**: `intervalOverride`(String 파싱, 최우선·기존 P3 TODO 배선) ?? tier(`lastSeenAt` age: ≤active-threshold PT24H→active PT30M / >dormant-after P14D→**dormant** P1D[F] / else inactive PT6H / lastSeenAt null→default PT2H). F=최장 band=스캔 강등(**삭제 아님**, 무삭제 일관). 검출활동 가중은 join 필요→후속.
- **갱신**: `scanTick` attempt 마다 `lastScanAttemptAt=now`+`nextScanDueAt=now+effectiveInterval`(touchScanSchedule, skip/실패 포함).
- **D off-peak = 파라미터 스위치**(due 술어 불변): off-peak 시 K=`off-peak-domains-per-tick`(500)·윈도우=`off-peak-max-window`(PT24H)·(선택)쿼리캡 상향 → 백필 가속. peak=증분·소윈도우. `schedule.off-peak-window`(01-06, 현 config-only) + `off-peak-zone`(신규) 판정, 자정 wrap 처리. **백필 우선순위는 due 정렬(nextScanDueAt asc)이 이미 가장 밀린 도메인을 앞세워** Watermark-lag join 불요. PR1 windowFor/ScanSelector/LokiBudget 에 off-peak 값 주입(코어 불변).
- **변경**: `DomainConfig.nextScanDueAt`(ddl-auto nullable)·`ScanSelector`(due 쿼리+off-peak K)·`scanTick`(off-peak window/budget 주입·touchScanSchedule)·신규 `ScanTier`/`OffPeakWindow`(순수함수). **무회귀**: `tiering-enabled=false`→nextScanDueAt 항상 now→LRS=**PR1 정확 동치**(롤백 스위치), off-peak-window 미설정=항상 peak, 코어(collectBounded·analyze·budget) 불변.
- **PR 구조(권장 = C+D+F 1 PR)**: 셋이 단일 due 모델 공유(F=C 1 band, D=직교 스위치) → 분리 시 ScanSelector/scanTick 재수술 반복. 분리 차선=`(C+F due모델)+(D off-peak)` (매니저 원안 C+D/F 보다 응집적).
- **리스크(정직)**: 티어-변경 lag(nextScanDueAt 스캔시 계산→lastSeenAt 변화 반영 최대 1 interval 지연, 디스커버리엔 허용)·off-peak zone 오설정·재기동 후 due 쏠림(예산·이월이 평탄화)·HA 단일전제.
- **구현(2026-06-26, 권장 1 PR)**: 신규 `ScanTier`/`OffPeakWindow`(순수함수)·`DomainConfig.nextScanDueAt`(ddl-auto nullable·@Index next_scan_due_at). `DomainConfigRepository`: `findDueForScan`(due 술어)+`touchScanSchedule`(lastScanAttemptAt+nextScanDueAt 2컬럼 UPDATE) 신설, **구 `touchLastScanAttempt`·`findByEnabledIsTrue(Pageable)` 는 이 변경으로 orphan → 삭제**(ponytail; 무인자 `findByEnabledIsTrue()`는 선존 dead code라 미터치·보존). `ScanSelector`/`DiscoveryScheduler` 에 Clock·ApiDiscoverProperties 주입, off-peak 판정은 scanTick·selectForTick 각자 now 기준(틱당 1회). `runScan(host,maxWindow)` 오버로드(기존 `runScan(host)`=max-window 위임 → ScanController·온디맨드 무영향). **off-peak 쿼리캡 상향은 범위 밖**(LokiBudget 시간당 stateful → 시간경계 캡 스위치 지저분, K·max-window 두 스위치만; 필요 시 후속). 무회귀 검증: `tiering-enabled=false`→effectiveInterval=ZERO→nextScanDueAt=now→`findDueForScan` nullsFirst asc=LRS 동치(DiscoverySchedulerTest), off-peak-window blank=항상 peak(OffPeakWindowTest). 단위 mock(운영 Loki 미호출).
- **리뷰 반영(2026-06-26, 전건)**: **P1(머지 차단)** — `findDueForScan` 가 Pageable `Sort` 에 의존하면 Hibernate 가 NULLS FIRST 를 SQL 에 미방출 → PG 기본 ASC=NULLS LAST 로 null(신규 미스캔) 도메인이 dated-due 뒤로 밀려 **영구 기아**(tiering 기본 true 직격). 수정: `@Query` 에 `order by d.nextScanDueAt asc nulls first` 명시(Hibernate JPQL→PG/H2 결정적), `ScanSelector` Pageable 은 limit 전용(Sort 제거). **실 PG 회귀가드**(`PostgresIntegrationTest.findDueForScanOrdersNullsFirstOnRealPg` — dated 3 + null 1, K=2 → null 이 top-2 맨 앞, NULLS LAST 회귀 시 빨강) podman 실행 PASS(H2/H2-PG모드 무력이라 실 PG 단언 필수). **P3-1** — `OffPeakWindow.zone(invalid)` 가 `ZoneId.of` DateTimeException 매 틱 전파→스캔 중단(isOffPeak/ScanTier 폴백과 비일관) → try/catch 시스템기본 폴백+warn. **P3-2** — `ScanTier` active 체크가 dormant 보다 먼저라 active-threshold>dormant-after 역전 misconfig 시 과스캔(안전쪽·크래시 없음) → javadoc 전제 한 줄(코드 무변경).

### D49. API 판단 근거(점수 산출 내역) 노출 — /discovery 가시화 (doc/34) — 제안·권장안
사용자 개선요구: API 로 판단된 근거(신호별 점수·총점·preset·threshold)가 DB/응답에 없음 → `GET /api/v1/domains/{host}/discovery` 에 판단 근거 동봉. ★설계만(구현 금지).
- **★A(조회시 재계산) vs B(스캔시 영속) → A 권고, 스키마 변경 0**:
  - A 근거 ① /discovery 는 이미 재계산 경로(`forHost` 가 `classify(...eff.scorer()...)` 로 현재 effective 설정으로 findings 재산출) → 근거도 같은 경로 재계산해야 응답 내 findings 와 일관(B 면 저장 근거가 재계산 분류와 불일치 위험). ② **corsPreflight 가 조회시 가용**(per-endpoint 저장값 아님 — discovered 집합의 OPTIONS sibling 에서 `corsKeys` 도출, OPTIONS 행은 discovered_endpoint 영속·forHost 로드 → 이미 올바로 도출 중). **→ 매니저 우려 "corsPreflight 1컬럼 추가" 불필요**. 나머지 입력 전부 영속 → 전 신호 재계산 충실. ③ 사용자 의도="현재 preset/threshold" → 재계산이 현재 설정 반영(B 는 stale). ④ 린(영속 blob·stale 없음, scorer 이미 보유, 비용 N×14 무시).
  - B 미채택: 스키마 증가·preset 변경시 stale·report_json 비대·재계산 findings 불일치 위험.
- **★분류별 근거 차등**: Shadow=점수(게이트 ADMIT) / Active·Zombie=스펙매칭(점수 무관, specRef) / Unused=spec_only(무트래픽) / WebPage=endpoint_kind. polymorphic `basis`.
- **응답 스키마(additive, /discovery 전용)**: `Finding` **불변**(→ report_json/ScanResult.version/ETag 무변경·중앙 /result 무파괴). `CombinedDiscovery` 에만 가산 — ① `effectiveClassification`{profile(HIGH/MIDDLE/LOW/CUSTOM)·threshold·weightsSource·weights(§4.3 14개)} (forHost 가 이미 resolve), ② `rationale: List<EndpointRationale>`(findings 동순서·동 identity, `basis` polymorphic: ScoreBasis{apiScore·threshold·gate·mode·signals[{key,weight,fired,contribution}]} / SpecMatchBasis{specRef·deprecated} / SpecOnlyBasis / KindBasis).
- **메커니즘**: `ApiScorer.scoreExplain()`(신호별 내역, `score()`=`scoreExplain().total` 위임=단일 진실원) + `Classifier` explain 모드(query 전용, 분류 core 공유·스캔 경로 바이트 동일) + `forHost` 가 rationale+effective 동봉(추가 조회 0).
- **ETag 영향 없음**: /discovery 는 plain GET(ETag 없음), report_json 불변(스캔 경로 무영향), 점수 결정적이라 creep 없음(애초에 report_json 미포함). score 를 ETag 입력에 미추가.
- **매뉴얼 §4.3 스펙**: 정적 preset 표 유지 + effective 확인(API 필드)·엔드포인트별 점수 내역 예시·분류별 근거 차이 명시(technical_writer, 기존 per-domain override 요청과 함께).
- **리스크(정직)**: 근거=현재설정 기준(스캔 당시 아님, 의도와 일치하나 명시)·입력=최신 스냅샷(과거 윈도우 재현 아님)·acrm-active(M3) 시 corsPreflight 미세차(기본 DORMANT 라 현 출하 무차이, acrm 영속시 해소)·dropped 비-API 는 근거 부재(집계만). 무회귀: 스키마 0·스캔경로 불변. **매니저 사용자 확인 후 dev 착수.**

### D50. REST API 대규모 변경 배치 — 삭제·수정·신규 종합 (doc/35) — 제안·권장안
사용자 요청 대규모 API 변경. ★설계만(구현 금지). 매니저 사용자확인 후 dev 단계별 착수.
- **단계 분할**: **P1**(저위험·additive: D1 HostQueryController 제거·M1 페이지네이션·M2 GET/{host} 보강·M3 PUT 부분수정·M4 scan-status 보강·M5 result rationale·M6 spec 목록·A2 가중치편집 + `spec_record.filename` 컬럼) / **P2**(고위험: M7 멀티문서+API 상태추적) / **P3**(A1 즉시스캔 Loki 동기). 각 phase 독립 PR.
- **★스키마 변경 = `spec_record.filename` 단 1건**(P1, ADD-only·ddl-auto·기존행 null·무손실, **재배포 필요**). 근거: spec_record 가 원본 파일명 미저장(specName=논리 merge 키 "default", 파일명 아님)·PUT /spec(byte[] body)도 파일명 미수신 → 컬럼+옵션 파라미터 추가. **M7 상태추적은 compute-on-read(신규 테이블 없음) 권장**(직전 canonical 이 inactive 행에 이미 영속) → 추가 스키마 없음.
- **★중앙연동 영향**: M1 응답 **배열→페이지객체(Breaking)** — 중앙 소비 시 동시 갱신. M5 /result `rationale` 가산=additive-safe(report_json 필드 불변·ETag=report version 유지). M3 미전달=기존유지(전항목 전달 시 무회귀). D1 /hostnames 제거(소비자 확인). A1·A2 신규(영향0).
- **핵심 설계결정**:
  - M3 부분수정 = `DomainUpsert` nullable 화(Boolean enabled 등)로 "미전달 vs false" 구분, present-only apply(PATCH 의미).
  - M5 rationale = 조회시 재계산(사용자확정, doc/34 classifyExplained 재사용)·report_json/ETag 불변·serve-time 가산. caveat: findings=스캔시점 vs rationale=현재 재계산.
  - M7 동일성 키=method+path_template. status: ADDED/DELETED/UPDATED. **★UPDATED 구조적 한계**: CanonicalEndpoint 가 query param·스키마 미보유(method·path·host·deprecated·version·sourceRef 만) → param-level diff 불가, **UPDATED=deprecated/version 변경 한정**(path param 변경=다른 template=ADDED+DELETED). 진짜 param diff 는 canonical 강화=범위 밖.
  - A2 가중치편집 = PATCH /classification/weights(도메인·전역), 현 effective weights 스냅샷+부분 override+**profile→CUSTOM 자동**(편집 안 한 키는 현 effective 유지·MIDDLE 리셋 방지), applyOverrides·WEIGHT_KEYS 재사용. 기존 PUT /classification 불변.
  - A1 즉시스캔 = POST /domains/{host}/scan-now(동기), DomainRegistrar.registerIfAbsent(미등록 자동등록)+scanOnDemand(watermark 미전진)+forHost(findings+rationale). 기존 POST /scan(비동기 202)와 구분. Loki 동기·부하보호·window 상한.
  - D1 orphan: findByHostname 삭제(전용), ScanStatusView/SummaryView·runOnDemand 보존(타 사용처).
- **W1 매뉴얼 §2.5**(TW): thresholdOverride(임계 교체[0,1])·customWeights(CUSTOM 한정 14신호 override)·matcher(힌트/exclude/includeWebForms·explicit-hint) 의미 정리.

### D51. SpecRecord.rawDoc(oid LOB) REST 메타조회 — projection (실배포 버그 수정, doc/28 §10) — 채택
실배포 버그: `SpecRecord.rawDoc=@Lob byte[]`=PG `oid`(이 코드베이스 유일 @Lob; canonicalJson/warningsJson 은 D40 에서 text). REST 메타 조회(M2 GET /domains/{host}·M4 GET /scan-status·M6 GET /spec)가 `@Transactional` 없이(OSIV=false→auto-commit) `SpecRecord` 엔티티를 로드 → rawDoc oid materialize → `Large Objects may not be used in auto-commit mode`/`Unable to access lob stream`(JpaSystemException) → **500**. prod 는 스펙 0 이라 잠복.
- **수정 = `SpecMetaProjection`(rawDoc/canonicalJson/warningsJson 미선택 JPQL 생성자식)으로 메타 조회**. SpecStore `activeSpecMetas`/`latestSpecMeta`(projection) 신설·REST 소비처 전환. 엔티티 반환(`activeMeta`/`activeRecords`)은 스캔 경로(`@Transactional analyze`) 전용 유지. 스키마·oid 마이그레이션 없음.
- **대안 기각**: bytea(oid→bytea ddl-auto 미변환·수동 마이그레이션 위험) / 메타조회 @Transactional(rawDoc 불필요 materialize·M1 1000건/page LOB 폭증).
- **forHost 경로(/discovery·/result M5) 동일 버그 — 같은 PR 에서 수정**: `CombinedDiscoveryService.forHost` 가 비-@Transactional·OSIV off 로 `loadActiveCanonical`/`activeRecords` 엔티티(rawDoc oid)를 로드 → spec 보유 도메인서 동일 500. 수정 = `forHost` 에 **`@Transactional(readOnly=true)`**(진입점 tx → 엔티티 로드 안전, 읽기+분류 전용이라 readOnly 적합·LOB 읽기 호스트당 active spec 소수라 bounded). 공유 스캔 메서드 `loadActiveCanonical` 미터치(analyze 무영향·저위험).
- **M6 정렬 결정성**: projection 쿼리 ORDER BY 에 `specName asc nulls first` 명시(specName 레거시 null 가능) — H2 ASC=NULLS FIRST 가 PG ASC=NULLS LAST 발산을 가리는 [[h2-pg-null-ordering-trap]](D48 findDueForScan 동형), 기존 인메모리 `Comparator.nullsFirst` 동작 일치.
- **PUT /spec 동일 filename 재업로드 oid 500 — ★self-invocation(fix/spec-reupload-tx-self-invocation)**: `SpecStore.upload` 의 4-arg core 에만 `@Transactional`, 진입 오버로드(2/3-arg)는 비-@Transactional → 컨트롤러가 진입 오버로드 호출 후 core 를 self-invocation(같은 빈)으로 부름 → 프록시 미적용 → tx 무력 → 재업로드 비활성화 루프(prev.setActive=rawDoc oid 엔티티 로드)가 auto-commit → 500(첫 업로드는 비활성 대상 0이라 통과, 재업로드부터 발현). 수정 = **진입 오버로드 3개에 `@Transactional`**(컨트롤러 호출이 프록시 경유→tx 시작→내부 self-call core 가 그 tx 안). ★교훈: 같은 빈 self-invocation 은 callee `@Transactional`(전 프록시 어드바이스) 무력 → 외부 진입 메서드에 tx 를 둬야 한다.
- **검증**: 실 PG 회귀가드 4건 — `specMetaEndpointsDoNotMaterializeRawDocOidInAutoCommit`(M2/M4/M6 oid) + `forHostEndpointsTolerateRawDocOidSpecOnRealPg`(/discovery·/result oid) + `specListNullSpecNameOrdersFirstDeterministicallyOnRealPg`(M6 nulls-first 정렬) + `reuploadSameFilenameViaHttpDoesNotHit500`(★MockMvc PUT 재업로드 self-invocation oid — 실 HTTP 경로, 테스트 tx 미적용). 각각 fix 임시 원복 시 RED(정확한 oid 에러 / PG NULLS LAST 순서 발산) 확인 후 GREEN(H2 재현 불가).
- **후속(별도)**: rawDoc `@Lob byte[]` oid→bytea 매핑 정밀화(마이그레이션 위험) — 실수요 시.

### D52. M7 — spec 멀티문서 관리 + API 상태추적 (ADDED/DELETED/UPDATED) (doc/36) — ★SUPERSEDED by D53
> **폐기**. 본 결정의 compute-on-read 문서버전 diff 모델(`SpecDiffService`·`GET /spec/changes`)은 사용자 의도(영속 인벤토리+reconcile)와 불일치로 D53(doc/37)으로 대체. M7a 구현(PR #44/#45)은 D53 §5 롤백 경계로 제거·이관. 아래는 이력 보존.

doc/35 P2(고위험) 상세. ★설계만(구현 금지). 사용자 확인(특히 UPDATED 스코프) 후 dev. M7=2 sub-phase(M7a 먼저/M7b 후속).
- **멀티문서 버전관리(M7.1)**: `upload(...,filename)` 의 specName 을 **filename 에서 도출**(정규화, 미전달=default) → 다른 filename=다른 문서(병합)·동일 filename 재업로드=그 문서 새 버전(MERGE 가 같은 specName 비활성). 구버전 **active=false 보존**(현행·하드삭제 없음·prune 없음 — diff 기준 확보). 병합=기존 loadActiveCanonical+specMergeStrategy 정합(변경 0).
- **상태추적(M7.2)=compute-on-read(신규 테이블 0)**: 비교 단위=**같은 specName 의 현 active vs 직전 inactive**(사용자 "이전 문서 vs 신규 문서" 정합). "직전"=같은(host,specName) active=false 중 specVersion 최대(최초 업로드=직전 없음=전부 ADDED). 동일성 키=**method+path_template**. ADDED/DELETED/UPDATED 만 보고. **★oid 회피(D51)**: canonicalJson **포함 projection(rawDoc 미선택)** 으로 읽어 비-tx 안전. 신규 쿼리 ORDER BY specName **nulls first**(h2-pg 트랩 D48). per-document diff — 다문서 겹침 시 DELETED 가 merged 엔 잔존 가능(specName 명시로 구분, 도메인-merged diff 는 복잡·모호로 미채택).
- **★UPDATED 스코프 — 권고 = 2단계 (M7a=(a) 먼저, M7b=(b) 후속)**:
  - (a) 린: canonical 보유 속성(`deprecated`/`version`) 변경만 → **0 스키마·즉시**. **단 사용자 "파라미터 변경" 미충족(부분 이행)**.
  - (b) param 추출: 파서가 OpenAPI/Postman param 스키마를 canonical 에 추출·영속 → 진짜 param-level UPDATED. 파서·CanonicalEndpoint·canonicalJson 스키마 대변경 = access-log 파라미터 추출과 묶음.
  - 근거: ADDED/DELETED 가 가치 大半(0 스키마)·즉시 제공(M7a), param-level 은 대작업이라 신중히(M7b). **(a) 한계를 응답 `updatedScope` 필드·매뉴얼에 ★명시 필수**. path param 변경=다른 template=ADDED+DELETED(UPDATED 아님). 사용자 확인=M7a 선출시 수용 vs M7a+M7b 동시 대기.
- **노출**: 신규 `GET /api/v1/domains/{host}/spec/changes`(기본 active 전 specName 현 vs 직전·`?specName/from/to/status`·`updatedScope` 노출). 응답=specName 별 {comparedVersion·previousVersion·changes[{method,pathTemplate,status,changed,detail}]}. /spec 목록(M6)과 분리.
- **스키마/중앙**: **스키마 0**(compute-on-read·projection·inactive 보존·filename P1 기존) → 재배포 불요. /spec/changes 신규(additive). PUT /spec filename→specName 도출=멀티문서 거동 변화(미전달=default 무회귀). inactive 누적(prune 없음, 희소·무해, 상한 prune 후속).
- **실 PG 테스트(필수)**: oid 가드(/spec/changes 가 projection-only 로 비-tx oid materialize 없음·엔티티 로드 회귀 시 RED, H2 미재현→실 PG), null 정렬 가드(D48), 시나리오(ADDED/DELETED/deprecated-UPDATED·최초=전ADDED·멀티문서·param-only=미보고+updatedScope).

### D53. M7 재설계 — 영속 API 인벤토리 + 업로드 reconcile (doc/37, D52 supersede) — 채택·P1 구현 완료(커밋 보류)
M7a(compute-on-read 문서버전 diff, D52)가 사용자 의도와 불일치 → **롤백 + 영속 인벤토리 재구현**. P1-1~P1-8 구현 완료(브랜치 `feat/documented-api-inventory`, build green 493·실패0·skip2, PostgresIntegrationTest 27/27·커밋 보류·머지 시 Done). P1-9 매뉴얼=TW 후속(범위 밖). ★구현 메모: ① `CanonicalEndpoint` params 가산은 6-arg 편의 생성자 + compact 생성자(null→빈)로 기존 호출부·구 canonicalJson 역직렬화 하위호환. ② reconcile 은 별도 빈 `ApiInventoryService`(upload @Transactional 전파 REQUIRED·self-invocation 무관). ③ DELETED→Zombie 결합 = Classifier core 에 `Set<String> deletedKeys` 가산(빈=무회귀)·DiscoveryJobService(스캔)·CombinedDiscoveryService(forHost) 1쿼리 주입. specRef="deleted-from-spec" 가 이 경로 고유 마커. ④ rawDoc 삭제로 oid 함정 클래스 구조 소멸 — projection/forHost readOnly tx 는 heavy-text 컬럼 회피로 유지. ⑤ ★실 PG RED-확인: Zombie 결합(분기 비활성)·reconcile DELETED(루프 비활성) 각각 RED→복원. ★핵심 교훈 — finding JSON 은 record 컴포넌트만 직렬화(interface `classification()` 미노출, `@JsonProperty` 만 예외) → 테스트 단언은 컴포넌트(specRef/confidence)로. 아래는 설계 근거 보존.
- **의도**: 업로드마다 문서(specName)별로 파싱→인벤토리 reconcile. 4-1 등록된 API param 변경=UPDATE·4-2 신규=ADD·4-3 문서서 빠짐=DELETED. **삭제는 같은 문서 한정**(다른 문서 업로드는 타 문서 API 불삭제·도메인=문서별 union). 상태=현재 속성(history 아님).
- **데이터 모델(신규 테이블 `documented_api`)**: 키 (host, specName, method, path_template). 보유 paramsJson(text·@Lob 금지)·status{ACTIVE,DELETED}(소프트마크)·lastChange{ADDED,UPDATED,UNCHANGED}·deprecated·version·sourceSpecVersion·firstDocumentedAt·lastDocumentedAt·changedAt. **ddl-auto=update ADD TABLE(무손실)**·snake_case·인덱스(host/host,spec_name/host,status). 시각=문서 존재 기준(트래픽 아님).
- **파라미터**: `SpecParam(name, in{QUERY/PATH/HEADER/COOKIE/BODY}, required, type 요약)` + `CanonicalEndpoint` params 가산(매칭키 method+host+path 불변=무회귀·구 canonicalJson null→빈 params). 3 파서 추출(OpenAPI getParameters/getRequestBody·Postman url.query/path/body·CSV 컬럼). "param 변경"=집합/속성 diff(추가·제거·required flip·type). path param 변경=다른 template=ADDED+DELETED(UPDATED 아님).
- **reconcile-on-upload**: upload `@Transactional` 내 in-memory 파싱 결과로 specName 단위 reconcile(존재+변경=UPDATED·존재+동일=UNCHANGED·부재=ADDED·인벤토리 active 인데 파싱 부재=DELETED[그 specName 한정]). **SpecRecord/rawDoc 미접근=oid 무관**. self-invocation 회피(D51).
- **노출**: 신규 `GET /api/v1/domains/{host}/apis`(?specName/status/method). **`GET /spec/changes` 제거**(M7a 모델 폐기).
- **롤백 경계**: 삭제=`SpecDiffService`·`SpecChanges`·`SpecCanonicalProjection`. 수정=`SpecRecordRepository.findCanonicalVersions` 제거(findActiveSpecMetas 유지)·`SpecController` /changes 제거·테스트(reupload 단언 /apis 이관). 유지=filename→specName 도출·upload @Transactional·filename 컬럼·SpecMetaProjection.
- **★Zombie 연결(사용자 핵심)**: DELETED API 는 active canonical 에서 빠져 현 분류기 미포착→SHADOW 오분류. 인벤토리 DELETED ∩ 관측 트래픽(`discovered_endpoint`)=**강한 Zombie**(confidence 0.8, "문서서 제거됐으나 트래픽 지속"). deprecated(문서 내·1.0) vs DELETED(문서서 제거·0.8) 구분·둘 다 Zombie 계열. 결합 위치=Classifier 1st pass SHADOW 분기 정제(DELETED 키 집합 1쿼리 가산·무회귀). **데이터 모델 P1 부터 DELETED 를 Zombie 입력으로 보유**. 결합=P1 권고(사용자 동기·저위험), P2 분리 가능(모델 변경 0). 매칭 source 대체=P2.
- **★rawDoc(oid) 운명**: 코드 확인 — `getRawDoc` 프로덕션 호출처 **0**(저장 전용). 3 oid 버그=엔티티 materialize 부수효과. **권고=(a) 컬럼 삭제**(필드 제거만으로 oid 함정 클래스 소멸·재설계 go-forward·prod 사용자 spec ~0). 백필 정정 — param 백필은 canonicalJson 불가(원본 재파싱 필수) → (a) 삭제 시 **재업로드(go-forward)**, (b) bytea 시 프로그램 백필. (a) 절차=코드 제거(P1) + ops DDL(lo_unlink→DROP COLUMN/vacuumlo·차기 점검창·디커플·무해 잔존). 원본 보관/대규모 백필 수요 시에만 (b) bytea.
- **스키마/중앙**: 신규 테이블 ADD(무손실)·rawDoc DROP(수동 DDL)·`/apis` additive·`/spec/changes` 제거(중앙 미통합 전제 확인). 워커 로컬 DB·중앙 무관.
- **단계**: P1=롤백+인벤토리+param 추출(3파서)+reconcile+/apis+DELETED→Zombie+rawDoc 삭제+실 PG 테스트. P2=매칭 source 대체·백필(재업로드)·풍부 param diff. 실 PG 테스트=문서별 union·격리(B 가 A 미삭제)·UPDATED/DELETED/ADDED·oid 안전·null 정렬(D48)·재업로드 200·param 변경·Zombie 결합.

### D54. M7 재설계 P2 — 매칭 source 대체(보류)·백필(재업로드)·풍부 param diff+breaking·도메인-merged 뷰 (doc/38) — 채택·P2-2/3/4 머지 완료(PR #47)·P2-1 보류
P1 머지(main 3b70c3e) 위 후속. P2-2+P2-3+P2-4 ★머지 완료(PR #47 / main `b60b929`, build green 497·PostgresIntegrationTest 31/31)·재배포·라이브 스모크(breaking·merged 정상)·Loki 수집 계속 확인. P2-1=보류(미터치)·P2-5(prune)=범위 밖(TODO). ★구현 메모: ① P2-3 = `ParamDiff.diff(old,new)`(spec)가 (name,in) 키로 added/removed/modified + breaking(규칙표·widening 허용표 `integer→number`만, 그 외 보수 breaking) 산출 → reconcile `applyUpdate` 에서 ★setParamsJson 전(덮어쓰기 전) 계산해 신규 컬럼 `last_change_breaking`(boolean)·`last_change_detail_json`(text·ParamChange 직렬화)에 저장(사후 재계산 불가). UPDATED 외 lastChange 는 false/null clear. `DocumentedApiView`+`lastChangeBreaking`/`changedParams`·`?breaking=true`. ② P2-4 = `ApiInventoryService.listMerged`(compute-on-read)가 (method,path) 그룹 병합 → `MergedApiView`(status any-ACTIVE→ACTIVE·all-DELETED→DELETED, deprecated OR, version/params latest-by-sourceSpecVersion, contributingSpecNames). `/apis ?view=merged`(기본=per-document 무회귀). ③ 컨트롤러 반환 `List<?>`(per-document/merged 분기). ④ ★실 PG RED-확인: breaking(ParamDiff 강제 false)·merged status(강제 ACTIVE)·merged version 출처(P3-1 reviewer 지목 → 대표값 ACTIVE pool 통일, latestPool→group 환원 시 DELETED 행 version 발산 RED) 각각 RED→복원. ⑤ ★교훈: 컨트롤러 반환 타입 변경 시 미사용 import(DocumentedApiView) 정리. 아래는 설계 근거 보존.
- **★P2-1 매칭 source 대체 = 보류 권고**. 목표=loadActiveCanonical(active spec_record canonicalJson 병합)을 인벤토리(status ACTIVE)로 대체·단일 진실원. 동작보존 필수(분류 불변)면 **가시 가치=내부 단일화뿐(0)**. 위험: ① ★SEPARATE 발산(확정) — reconcile 은 specName 격리(타 specName 미접근)인데 upload(SEPARATE replaceAll)는 host 전체 spec_record 비활성 → loadActiveCanonical=최신만·인벤토리 ACTIVE=전체 union. ② ★status 오버로드 지뢰 — 이를 status 동기화로 고치면 교체문서 API 가 DELETED→deleted-from-spec Zombie 오탐(인벤토리 status=문서별 존재[Zombie 입력]≠host 매칭 active, 양립 불가). ③ 백필 갭 — go-forward라 P1 이전 spec 매칭 누락(prod ~0 무해하나 잠재). → 핵심 분류 직격 위험·가치 0 = 비용/가치 음수. **권고=보류**, loadActiveCanonical 유지·인벤토리 보완. MERGE/VERSION_GROUPED 는 cross-specName 병합으로 재현 가능하나 SEPARATE 불가. 추진 시 안전경로=MERGE 한정+빈인벤토리 canonicalJson 폴백+★골든 이중경로 회귀(shadow 비교·divergence RED·컷오버 전)·status 오버로드 금지.
- **P2-2 백필=재업로드**(코드 0). rawDoc 삭제(P1)로 (a) 확정 → 운영자 재업로드 시 reconcile 이 param 채움. (b)bytea 기각. 문서/매뉴얼 노트만.
- **P2-3 풍부 param diff+★breaking 판정**(제품가치 최고). 현 paramsChanged=Set 동등 boolean. 보강=요청계약 기준 breaking 규칙표(required 추가=breaking·optional 추가=non·optional 제거=breaking·optional→required=breaking·required→optional=non·type 비호환=breaking, 보수적). compute=reconcile applyUpdate 에서 old(row) vs new(p) delta+breaking 계산·저장(old 덮어쓰기 전 가용·사후 불가). 신규 컬럼 2개 가산(last_change_breaking boolean·last_change_detail_json text·ddl-auto ADD·무손실). 노출=/apis 에 lastChangeBreaking·changedParams + ?breaking=true. type 상세=호환 허용표 최소(enum/format/nested 범위 밖). 엔드포인트 ADD/DELETE 는 범위 밖(DELETE=Zombie 로 이미 포착).
- **P2-4 도메인-merged 뷰**. /apis ?view=merged(신규 엔드포인트 대신). method+path_template dedupe. 병합=status(하나라도 ACTIVE→ACTIVE·전부 DELETED→DELETED)·deprecated OR·version latest-by-sourceSpecVersion·params latest-active 택1(union 미채택)·contributingSpecNames[]. compute-on-read(스키마 0). SpecCanonicalizer.merge 와 정합 → P2-1 골든 검증 artifact 재사용 가능.
- **스키마/중앙**: P2-3 컬럼 2개 ADD(무손실)·P2-4/P2-2/P2-1=0. /apis 필드·?view·?breaking 전부 additive·매칭 내부 불변(P2-1 보류)·워커 로컬 DB 중앙 무관.
- **실 PG 테스트**: P2-3 breaking 각 규칙·P2-4 병합/겹침/status 병합·oid 무관(rawDoc 삭제)·h2-pg 정렬(D48). P2-1 테스트 없음(보류).

### D55. 스캔 신선도 운영 — 백필 발산 진단·워터마크 점프·무접속 도메인 중단·/result 판단근거 인라인 (2026-07-01)
실배포(테스트 VM 52,536 도메인) 운영 점검에서 도출. 사용자 요청 3건 + 진단 1건.
- **(진단) 평가 스캔 백필 발산 확정**: 수집(도메인 디스커버리)은 최신(last_seen_at=now-수분)이나, 엔드포인트 스캔은 **호스트별 워터마크 바운드 백필**이라 52k 규모에서 실시간을 못 따라잡고 격차가 ~하루 0.5일씩 **벌어짐**(scan_result.window_to 가 6/19~6/23 에 정체, 실행은 6/30). biz.revu.net 의 discovered_endpoint 가 6/19 박제 = 이 지연의 증상(수집 중단 아님, 실 트래픽은 폭주).
- **① 워터마크 점프(채택·실행)**: 과거 백필 포기·실시간 추적 전환. `watermark.last_end`=now−30m 일괄 UPDATE. ★**가역적·DB 직접 점프는 운영 중 스케줄러와 경합** — off-peak PT24H 장시간 스캔이 점프 전 옛 값 읽고 완료되며 일부(소수)를 되돌림. 99.92% near-now 로 정렬됐고 **깔끔한 정착은 앱 재기동(다음 재배포)에서**. 교훈: 워터마크는 매 스캔 DB 직독(캐시 없음)이나 in-flight 스캔이 덮어쓰므로, 영구 점프는 재기동 필요.
- **② `/result` 판단근거 인라인(채택, ⓒ)**: 기존 별도 `rationale[]` 배열(M5)을 제거하고, report_json 의 각 finding 에 `classification`+`basis`(SHADOW=score{apiScore·threshold·signals}, Active/Zombie=spec_match) 인라인. `ScanController.inlineBasis` 가 `EndpointIdentity.key`(method,host,path) 매칭. ★응답 형태 변경(additive 아님)=중앙/매뉴얼 반영 필요·재배포 전 라이브는 옛 형태. 사용자 의도="발견 API 마다 점수/기준/가중치"는 이미 SHADOW 에 충족, VM 은 스펙 0개라 전부 SHADOW(Active 점수는 스펙 업로드 시 후속=ⓑ 보류).
- **무접속 도메인 중단(채택, 신규 요구)**: 마지막 접속(`last_seen_at`)이 `scan.inactive-after`(기본 P30D)보다 오래된 도메인은 ScanSelector `findDueForScan` 에서 제외 → 스캔(수집+평가) 중단. ★**스키마 변경 0**(domain_hostnames 컬럼 추가 안 함 — @ElementCollection 부적합·per-hostname granularity 불요, 결정은 per-domain). last_seen_at 재사용(디스커버리가 트래픽 볼 때 now 로 갱신=마지막 접속 프록시). **자동 재개**: fleet 디스커버리(경량·전수)는 계속 → 트래픽 재개 시 last_seen_at 갱신 → 다음 틱 자동 재스캔(self-healing·수동 불요). 비활성=inactive-after 0/null. ★**EPOCH 센티넬**: `:staleCutoff is null` 같은 nullable 비교는 실 PG 가 untyped-null($N) 타입추론 실패(h2-pg-null-ordering-trap 동류, PostgresIntegrationTest 가 검출) → 비활성 시 호출자가 `Instant.EPOCH` 전달(non-null 유지). build green 500·실 PG 가드 PASS·필터 제거 RED-확인.
- **공통**: 구현(②·무접속)은 커밋 보류(매니저 git/PR/머지). 재배포 시 ①(워터마크 정착)·②(인라인)·무접속 중단 동시 반영. 매뉴얼(TW)=②·무접속 후속.

### D56. 정적 파일 API 오탐 수정 — 하드 veto + 정적 리소스 파일명 감점 (2026-07-01, D55 후속, 사용자 확정)
사용자 보고: `/api/.../img.php` 류가 API(Shadow)로 오판정. 원인 = 경로 PREFIX 의 `api` 디렉터리가 apiSegment(+0.55) 발화 → 정작 리소스는 정적 서빙 스크립트인데 임계 통과(WEB_PAGE 는 점수 미감점). 사용자 확정: ①정적파일=비-API 보장, ②.php 는 정적 확장자 미포함.
- **#1 정적 확장자 하드 veto**: `ApiScorer.evaluate` 에 `endpointKind==STATIC`(정적확장자 isStaticPath 또는 $type=library) → `Gate.DROP_STATIC`(exclude·operator apiHint 다음, 점수/web-form 앞). 점수·api 키워드 무관 무조건 비-API. ★감점만으론 보장 불가(clamp[0,1] 라 양수합 큰 경우 −0.6 으로 못 누름)이라 veto 채택. `STATIC_EXT` 확대(webp/avif/bmp/tiff/otf). `DroppedNonApi` +staticFile 카운트(total 자동 전파).
- **#2 정적 리소스 파일명 감점**: `EndpointKindClassifier.hasStaticResourceName`(마지막 세그먼트=확장자 있는 파일 + 토큰[img·image·thumb·thumbnail·resize·icon·logo·banner·sprite·avatar·favicon·css·download·attachment]) → `staticAssetPenalty`(-0.6) 발화조건에 OR 추가. img.php(WEB_PAGE) 가 0.79→0.19 로 탈락. ★veto 아닌 감점 = .php 는 실 API 가능(login.php·list.php)이라 보존. ★모호 토큰(photo·view·file·get) 제외(과탐 방지)·확장자 없는 컬렉션(/api/images) 제외(REST 가능). 새 Weights 필드 불요(기존 staticAssetPenalty 재사용).
- **검증**: build green 504·실 PG OK·신규 테스트 RED-확인(veto·감점 무력화 시 2건 red→복원). 응답 additive(DroppedNonApi.staticFile 가산·중앙 무파괴). 가중치 실데이터 보정은 라벨(스펙) 부재로 보류(D55).

### D57. 무접속 자동스캔 제외를 Loki 실로그 기준으로 + 정적분류 규칙 DB 외부화·reload (2026-07-01, 사용자 확정)
D55 무접속 중단(inactive-after)의 기준 정정 + D56 정적 토큰/확장자 외부화. 사용자 지적으로 재설계.
- **① 무접속 기준 = 실 access log(time_iso8601), not last_seen_at**: `last_seen_at`(discovery 관측시각)이 아니라 **Loki 실 로그시각** 기준으로 자동스캔 제외. ★per-domain Loki 쿼리(53k)는 부하 폭증이라 불가 → **스캔이 이미 읽는 로그의 최신 시각(`max discovered_endpoint.last_seen`=time_iso8601)을 `domain_config.last_access_log_at` 에 저장**(analyze 에서 `touchLastAccessLogAt`, never-decrease), ScanSelector 게이트를 이 값으로(추가 쿼리 0). scan-now(명시)는 ScanSelector 미경유=항상 스캔, 자동스캔만 제외. 임계=`inactive-after` 기본 **30일**(3개월은 예시). 미스캔(null)=제외 안 함. ★의미: 신규 로그가 Loki 에 안 들어오는 dead 도메인만 자동제외 → 활성 도메인만 자동스캔=순회 단축. 시스템 가동 <30일이라 현재 제외 0(30일 경과 후 작동).
- **②③ 정적 확장자·토큰 DB 외부화 + reload**(사용자: 소스 하드코드 → 관리자 편집·reload): 신규 `static_classify_rule`(kind{EXTENSION,NAME_TOKEN}·value·unique·ddl-auto) + `StaticClassifyRuleRepository` + `StaticClassifyRules`(빈 테이블 시 기본값 seed·DB→`EndpointKindClassifier.applyRules` 적용·add/remove 시 즉시 재적용) + REST `/api/v1/config/static-classify`(GET 목록·POST 추가·DELETE 삭제·POST /reload). EndpointKindClassifier 의 하드코드 배열 → `volatile Set`(런타임 교체) + 기본값 상수. ★ApiScorer 가 `new` 생성돼 빈 주입 불가 → 정적 volatile + `applyRules` 정적 메서드(단위테스트는 @BeforeEach 로 기본값 리셋해 정적상태 오염 방지).
- **검증**: build green **505**·실 PG OK(무접속 게이트 lastAccessLogAt·규칙 CRUD/reload)·게이트 RED-확인. 스키마=ADD TABLE static_classify_rule + domain_config.last_access_log_at 컬럼(ddl-auto·무손실). 매뉴얼(TW)=후속.

### D58. Loki 부하 안정화 — 버스트 기본값 복귀 + 타임아웃 적응형 감속 + 파일 로깅(외부·일별·gz·30일) (2026-07-02, 사용자 요청)
07-01 13:26 KST(04:26Z)부터 앱 중지(07-02 10:20 KST)까지 adc-app 의 Loki 조회가 대부분 실패(scan failed 2093건, 근본원인 77%가 `HttpTimeoutException: request timed out`=응답 타임아웃, 나머지 ConnectException·NoRouteToHost). 진단: D56 후속 공격적 버스트(300/tick·PT3M·16000q/hr·page 5000)로 Loki 포화 → 타임아웃 급증. 게다가 **IOException(타임아웃/연결실패)이 `escalateThrottle`·`budget.record` 를 우회**(HTTP 429/5xx 에만 감속)해, Loki 가 느려도 앱이 감속 없이 전속으로 실패 쿼리를 지속(가해자이자 피해자).
- **① 버스트→기본값**(adc.yaml env, 재빌드 불요분): 4개 override(`domains-per-tick`·`tick-interval`·`max-queries-per-hour`·`page-limit`) 제거 → application.yml 기본(100/PT5M/3000/2000). ★`off-peak-zone=Asia/Seoul` 은 버스트 값 아닌 타이밍 정합이라 **유지**(기본 ""=UTC 로 되돌리면 off-peak 01-06 이 KST 10-15시 주간에 발동해 악화). 사용자 확정.
- **② 타임아웃 적응형 감속**: `LokiClient.requestWithRetry` 의 IOException 경로 → `escalateThrottle()`(다음 쿼리부터 min-interval×2^level 감속) + `LokiBudget.recordFailure("io")`(실패도 1쿼리로 시간당 예산 계상=무한 hammering 방지) + `loki.errors{status=io}` 메트릭. ★재시도 안 함(느린 Loki 가중 방지)·throttle-on-error 시. `send()` 는 `throws IOException` 로 변경(상위서 유형 로깅).
- **③④⑤ 파일 로깅**: 신규 `logback-spring.xml`(`container` 프로파일만) → `/opt/adc-log`(hostPath 마운트=**컨테이너 외부**). 쿼리별 전용 로거 `com.pentasecurity.apidiscover.loki.query`(응답시간ms·대상 LogQL/도메인·성공status/실패type·바이트·throttle·attempt) + 앱 이벤트/실패(APP_FILE). `%d{yyyyMMdd}` **prefix 일별 파일**(당일 활성=`YYYYMMDD-adc.log`, `<file>` 미지정)·**롤오버 시 .gz 압축**·`maxHistory=30`(**1달 후 삭제**)·UTC 타임스탬프. 콘솔(podman logs) 유지. 비-container=콘솔만(무회귀). adc.yaml=app volumeMounts + `adclog` hostPath 볼륨. VM `/opt/adc-log` 사전 mkdir(type:Directory).
- **검증**: build green **507**(신규 2: 타임아웃→감속·recordFailure 예산 계상)·실 PG OK·타임아웃 감속 **RED-확인**(escalateThrottle 임시 원복 시 throttle=0 red → 복원 throttle=1). 배포=재빌드+/opt/adc-log 생성+볼륨 마운트+재기동. 매뉴얼(TW)=후속.

### D59. 무접속 자동스캔 제외 기준을 last_access_log_at → last_seen_at(discovery)로 전환 + 임계 3일(배포) (2026-07-02, 사용자 확정, D57 재설계)
배경: 스캔 발산 진단 중 커버리지는 ~97% 완료됐으나 워터마크가 중앙값 1.5일 지연·발산(3000q/hr·PT30M 로 55k 도메인 실시간 유지는 물리적으로 ~140배 부족). 사용자가 fleet 축소('무접속 정리')를 요청. D57 게이트 기준 `last_access_log_at`(스캔이 채우는 값)은 스캔 지연(~1.5일)·미스캔 다수(57k 중 2,627만 값 존재)로 "무접속" 판정에 부적합 + 임계 30일 미도래(시스템 가동 ~6일)로 정리 0.
- **전환 근거**: discovery 는 10분마다 Loki **실시간 집계**(최근 12분)로 트래픽 도메인을 관측하고 `DomainUpserter` 가 **매 관측 `lastSeenAt` 갱신**(insert+update, `max-domains-per-run=0`=전수). 따라서 `lastSeenAt` 이 오래됨 = "신규 로그가 Loki 에 안 들어옴"을 실시간·전수로 판정 → **D57 의도를 더 정확히 실현**(per-domain Loki 쿼리 0, discovery 신호 재사용). ScanSelector·application.yml 주석은 이미 lastSeenAt 로 기술돼 있어 코드/문서 정합도 회복.
- **구현**: `DomainConfigRepository.findDueForScan` 게이트 `lastAccessLogAt` → `lastSeenAt`(null=제외 안 함, upsert 시 항상 set). `last_access_log_at`·`touchLastAccessLogAt` 는 정보성으로 유지(제거 안 함). 임계 `inactive-after`: application.yml 기본 P30D 유지, **배포는 env `APIDISCOVER_SCAN_INACTIVEAFTER=P3D`**(즉시 fleet 축소, self-healing 이라 오탐 안전). 3일 미관측 ~10,883개(57k→46k) 제외 예상.
- **소프트 제외 확인(사용자 질의)**: 삭제·enabled=false 아님. 게이트는 `findDueForScan`(스케줄러 자동 틱 선택)에만 작용 → `/result`·`/scan-status`·`/discovery`(저장값 반환)·`/scan`·`/scan-now`(scanOnDemand·runScan 직접 호출) 전부 게이트 미경유 → 정리돼도 정상 조회·명시 스캔 가능. discovery 재관측 시 자동 복귀(self-healing).
- **검증**: build green **507**·실 PG OK(findDueForScanExcludesStaleLastSeen)·게이트 RED-확인(lastAccessLogAt 임시 원복 시 stale 미제외 red→복원). 배포=코드 변경이라 재빌드+재기동.

### D60. 실시간 유지 쿼리 튜닝 — chunk/slice PT30M(A) + delta-driven skip(D) + 워터마크 점프(D) (2026-07-02, 사용자 요청)
"실시간 유지 쿼리 튜닝" 요청. 근본 병목: N 활성 도메인 실시간 유지에 필요 쿼리 ≈ N×(60/chunk-window분)/h — 활성 13k·chunk PT10M 이면 ~78,000/h vs 캡 3,000/h(~26배 부족). 순수 캡 상향(B)은 Loki 부하 직접 증가라 배제. 사용자 선택 = A(쿼리수 감소)+C(대상 축소=D59)+D(근본 효율화).
- **A. chunk-window·slice-window PT10M→PT30M**(adc.yaml env, 재빌드 불요): 도메인-시간당 쿼리 1/3. ★둘을 함께 올려야 유효 — collectBounded 가 slice 단위로 queryRange 호출→유효 청크=min(slice,chunk)라 slice=PT10M 이 캡. busy 도메인은 응답↑(page-limit 페이지네이션으로 상쇄), 저트래픽 다수엔 순감.
- **C. 대상 축소 = D59**(이미 라이브): inactive-after last_seen_at·P3D 로 무접속 제외(~11,420, 57k→46k). 시간이 지날수록 활성 코어로 수렴.
- **D. delta-driven skip**(`DiscoveryJobService.runScan`, D60 코드): 스캔 직전 `lastSeenAt`(discovery)이 `window.from()` 이전이면 = 윈도우에 신규 트래픽 없음 → **Loki 조회 없이 워터마크만 전진**(빈 윈도우 쿼리 낭비 제거 → 쿼리량을 실 트래픽 도메인에만 비례). D59 와 동일 discovery 신호 신뢰. scan-now(온디맨드)는 runScan 미경유라 항상 실조회. ★한계: discovery 가 놓친 트래픽은 skip 가능(정상 가동 전제, inventory/shadow 허용). 워터마크 점프와 결합 시 최대 효과(점프로 백로그 제거 → 이후 delta 만 처리).
- **D. 워터마크 점프**: 밀린 1.5일 백필 갭을 now−lag 로 일괄 전진(재배포 앱-down 중, 세션 66 방식). catch-up 부담 제거 → steady-state 만 남김. inventory/shadow 는 최신 로그 우선이라 과거 갭 무해.
- **검증**: build green **508**(신규 1: runScanSkipsLokiWhenDiscoverySeesNoNewTraffic)·delta-driven RED-확인(skip 무력화 시 loki 호출됨 NeverWantedButInvoked→복원). 배포=재빌드+점프+재기동.

### D61. delta-driven skip 을 now−lag 즉시전진으로 (catch-up 가속) (2026-07-02, 사용자 요청, D60 후속)
D60 delta-driven + PT1M·domains-per-tick 500 로도 워터마크 drift 미해소(평균지연 353분) — 원인: skip 이 워터마크를 `maxWindow(30분)`씩만 전진해 4h 밀린 도메인은 8 touch 필요, 56k 수렴엔 ~112k touch/h 필요(처리율 물리적 부족). tick-interval·domains-per-tick 튜닝만으론 catch-up 불가 확인.
- **변경**(`DiscoveryJobService.runScan`, 1곳): skip 시 `advanceWatermark(window.to())`(=watermark+maxWindow) → `advanceWatermark(now−ingest_lag)`. skip 조건 `lastSeen < window.from` 은 `[window.from, now−lag]` 전체 무트래픽을 보장(discovery 가 그 구간 트래픽을 봤다면 lastSeen 이 갱신됐을 것)하므로, maxWindow 상한 없이 now 까지 전진해도 로그 누락 없음. **빈 도메인이 1 touch 로 즉시 caught-up** → drift 해소. 실조회(트래픽 있는) 도메인은 maxWindow 슬라이스 유지(무변경). clock skew 방어로 최소 window.to.
- **효과**: 빈 도메인(대다수) 1회 처리로 near-now 도달 → 56k 를 1 패스(~2h at 500/PT1M)면 대부분 caught-up, 이후 skip 유지. 실 Loki 조회는 캡 3000/h 로 계속 보호.
- **한계(정직)**: D60 과 동일 — discovery 가 놓친 트래픽은 skip 될 수 있음(정상 가동 전제). D61 은 스킵 폭만 키움(30분→now), 신뢰 가정은 동일.
- **검증**: build green **508**·RED-확인(now−lag 전진을 window.to 로 원복 시 test 의 `isAfter(now−11m)` red→복원). 배포=재빌드+재기동(점프 불요 — skip 이 자연히 catch-up).

### D62. 대상 제외 엣지 (AAJ 23개) — 하이브리드 개선 Phase 1 (2026-07-02, 사용자 확정)
사용자 제안(5분 스텝·활성 도메인만 조회·배칭) 검토 결과 하이브리드 채택(제안의 push 작업목록·배칭만 이식, 워터마크·캡·delta-skip 유지). Phase 1 = 엣지 제외. 사용자 확정 제외 엣지 = AAJ11~AAJ63 23개(패턴 AAJ[1-6][1-4] 계열). 실측: 이 엣지들은 fleet 의 37%(20,880 전용 도메인)를 호스팅하나 활성 기여 5%뿐(주차 도메인 밀집) → 제외 시 fleet 56,403→35,523. AHJ11 비활성은 미제외(P3D 가 1~2일 내 자동 처리, 사용자 확정).
- **구현**: `discovery.excluded-hostnames`(List, 기본 빈=무회귀) 신설. ① `DomainDiscoveryService`: 제외 엣지 관측 샘플 skip(그 엣지에서만 보이는 도메인=미등록·lastSeen 미갱신 → 기존 등록분은 P3D 자연 제외) + 로그 `excludedEdge=N`. ② `DiscoveryJobService.effectiveEdges`: 스캔 조회에서 제외 엣지 매핑 제거, **전부 제외면 스캔 skip(★hostname-less 폴백 금지** — 전 스트림 조회 방지). 온디맨드(collect)도 동일 정책. ③ 배포 env `APIDISCOVER_DISCOVERY_EXCLUDEDHOSTNAMES=AAJ11,…,AAJ63`.
- **기존 데이터 정리(1회, 배포 시 앱-down 중)**: 백업 테이블 생성 후 → 제외 엣지 전용(모든 매핑이 제외 목록 안) 도메인 `enabled=false` → `domain_hostnames` 의 제외 엣지 매핑 삭제. 혼합 도메인은 잔여 엣지로 계속 스캔.
- **검증**: build green **510**(신규 2)·RED-확인 2건(discovery 필터 무력화 → 제외 도메인 등록됨 red / scan 가드 무력화 → 조회 발생 red). Phase 2=엣지-그룹 배칭(+10분 주기), Phase 3=활성 우선 선택 후속.

### D63. 엣지-그룹 도메인 배칭 (하이브리드 Phase 2) (2026-07-02, 사용자 확정)
사용자 제안(2-2 "도메인 몇 개씩 묶어 쿼리")의 이식. Loki 라인필터(|=/|~)는 비인덱스라 같은 엣지 청크를 도메인별 쿼리마다 재읽음 — 엣지당 도메인 수백~수천이면 같은 데이터를 그만큼 반복 스캔(타임아웃 장애의 구조적 배경). 배칭은 이 재읽기를 1/N 로.
- **구현**: `scan.query-batch-size`(기본 0=off 무회귀, 배포 10). ① `LokiQueryBuilder.buildBatch(edge, domains)` = `{job,hostname} |~ \`(d1|d2|…)\`` — ★RE2 이스케이프 필수(`a.com`→`a\.com`, 과탐 방지). ② `DiscoveryJobService.runScanBatched(hosts, maxWindow)`: 도메인별 게이트(disabled/D62/무윈도우/D60·D61 delta-skip, `skipIfNoNewTraffic` 헬퍼로 runScan 과 공유)는 개별 처리 → 실조회 도메인만 (워터마크 10분 버킷 × 엣지)로 그룹 → sub-batch(≤N)당 1쿼리 → 응답 라인을 host 파싱·정규화 **정확 일치**로 도메인별 분배(라인필터 과탐 차단) → 도메인별 analyze + 그룹 to 로 워터마크 전진. ③ `DiscoveryScheduler`: batchSize>0 이면 틱의 호스트들을 `runScanBatched` 일괄 위임(커서 전진 동일).
- **의미론 보존**: 그룹 윈도우=[버킷 min from, min(+maxWindow, now−lag)) — 자기 from 이 늦은 도메인은 ≤버킷폭(10분) 재읽기(멱등). gap-free: 도메인의 **모든** 엣지 쿼리 성공 시에만 전진(부분 실패=미전진 재시도, collectBounded 와 동일 규칙). 예산 소진=잔여 sub-batch 이월. hostname-less 레거시=기존 runScan 위임. 멀티엣지 도메인=엣지별 그룹에 모두 참여, 라인은 host 기준 병합.
- **검증**: build green **514**(신규 4: buildBatch/escape/단건 + 배칭 flow)·RED-확인(배칭 무력화 시 쿼리 1건 검증 TooManyActualInvocations red→복원). 배포=재빌드+env `APIDISCOVER_SCAN_QUERYBATCHSIZE=10`(계측 후 조정). Phase 3(활성 우선 선택)=후속.

### D64. 활성 우선 선택 (하이브리드 Phase 3) (2026-07-02, 사용자 확정)
사용자 제안의 push(활성 도메인에서 작업목록 도출) 이식. due 선택이 FIFO(nextScanDueAt asc)뿐이라 활성 도메인이 skip-류와 같은 줄에서 대기 → 활성 신선도 손해.
- **구현**: `DomainConfigRepository` 에 due 분할 쿼리 2종(크로스-엔티티 left join Watermark) — `findDueWithNewTraffic`(lastSeenAt > watermark.lastEnd 또는 미스캔 = 실조회 필요분) / `findDueWithoutNewTraffic`(여집합 = delta-skip 예정분). `ScanSelector.selectForTick` = 3단: skip-류 예약 확인(K/5) → 활성 우선 채움(K−예약) → 잔여 skip-류 보충. 활성 부족 시 skip-류가 몫을 더 가져감(전 슬롯 활용).
- **★기아 방지 예약(K/5)의 이유**: skip-류도 주기 선택돼야 D61 점프로 워터마크가 near-now 유지 — 안 그러면 트래픽 재개 도메인이 빈 과거 윈도우를 30분씩 기어가는 낭비(사전 검토에서 식별). 한계(정직): K=1 극단에선 예약 무의미·재개 도메인 crawl ≤(skip-류 재선택 주기) 어치는 잔존.
- **검증**: build green **517**(신규 3: 우선순위·예약·실 PG 분할 가드[크로스-엔티티 join 은 H2/PG 발산 위험 — h2-pg trap 대비 실 PG 검증])·RED-확인(FIFO 원복 시 우선순위 테스트 red). 코드 변경만(설정 불요)·배포=재빌드+재기동.

### D65. 엣지 그룹 Main-only 조회 (2026-07-03, 사용자 확정)
엣지는 그룹(단일/이중화/3중화+)으로 운용되고 **Master 엣지 로그에 그룹 전체 요청이 집계**된다(사용자 확정 ㉮). 그룹 규칙 4유형(사용자 정의): ①`[A-Za-z]OC…` = `(M|S)#` 접미 뗀 이름이 그룹, **M#=Master/S#=Slave**(사용자 확정 — 예시 260dM0 의 문자 세그먼트 포함하도록 숫자정규식 보정) ②2번째 문자 `L` = 전부 단일 그룹 ③`AAI##` = 5번째(마지막) 자리가 그룹, 사전순 첫(AAI1x)이 Master ④나머지 = 앞 4자리가 그룹, 사전순 첫이 Master.
- **구현 = 필터가 아닌 치환**: `EdgeGroupResolver`(신규) — DB 관측 엣지 인벤토리(413)로 엣지→Master 맵 구성(TTL 10분 갱신, Master 미상=자기 자신 보수적). `effectiveEdges` 가 D62 제외 후 **Master 로 치환+distinct**. 치환이므로 replica 에만 매핑된 도메인(고아 6,910·활성 1,930)도 Master 조회로 자연 커버(구제 로직 불요). `scan.edge-group-main-only`(기본 false=무회귀, 배포 true).
- **시뮬레이션(실측)**: 엣지 413→287(−31%), **활성 조회 매핑 32,709→17,414(−47%)** → 같은 예산에서 방문 용량 ~2배(4k→~7k/h), 활성 수요 대비 부족 6배→~3배. 샘플링(A안)·예산 상향과 독립 결합 가능.
- **검증**: build green **522**(신규 5: resolver 4유형·서비스 치환)·RED-확인(치환 무력화 시 Master 조회 검증 red). 배포=재빌드+env `APIDISCOVER_SCAN_EDGEGROUPMAINONLY=true`.

### D66. 롤링 샘플링 — 주기 스캔을 "최신 10분 표본"으로 (2026-07-03, 사용자 확정 ②안)
gap-free 크롤은 활성 수요(~22.6k 윈도우/h) vs 예산 용량(D65 후 ~7k)의 구조적 부족으로 발산 불가피(활성 wm 17h+). 사용자 제안(매시 2회×10분 전수) 시뮬레이션 → 성립하나 고정 시각표는 결정적 사각지대(:15 cron 영원히 miss)·데드라인 붕괴 위험 → **롤링 변형(②) 확정**: 도메인 방문 시점마다 "최신 10분"만.
- **구현**: `scan.sample-window`(0=off 무회귀, 배포 PT10M). `nextWindow` 분기 → `sampleWindowFor`(순수): `[max(watermark, now−lag−sample), now−lag)` — 과거 백로그 의도적 통과, 겹침 없음, 신규 도메인도 최신만(★initial-backfill 미적용 — 1008청크 백필 방지). 스캔 후 워터마크 = now−lag(기존 advance 가 자연 수행). 배치·단일 경로 공통(nextWindow 공유). scan-now/온디맨드 무관.
- **동반**: `max-queries-per-hour` 3000→**6000**(표본 수요 ~5-6k/h. 배칭·Main-only 로 쿼리당 Loki 부하 급감 후라 과거 16k 와 다름). active-interval PT30M = 시간당 2회 표본 = **커버리지 33%·신선도 ≤30분·발산 정지**.
- **트레이드오프(정직)**: 표본 밖 호출은 그 시점 미관측(반복 호출은 결국 잡힘 — 롤링이라 결정적 사각지대 없음), hits=표본 기반(~1/3), 워터마크 의미 "전부 읽음"→"표본 경계". 매뉴얼 §8.7 문서화.
- **검증**: build green **524**(신규 2: 순수함수·배선)·RED-확인(샘플링 분기 무력화 시 최신-10분 윈도우 검증 red). 매뉴얼 전면 보강(파이프라인 관문표 §8.8·결정트리·설정표 D58~D66 현행화) 동반.

### D67. query-batch-size 10→20 + 검증 설정의 기본값 승격 (2026-07-03, 사용자 요청)
- **배경(실측)**: D66 수렴 확인(16:12 KST) — 발산 정지·백로그 소진(틱 jobs<K·deferred 0·활성 평균 wm 지연 17h→67분). 단 쿼리 페이스 ~6.4k/h 로 캡 6000 초과 추세(매시 후반 이월 톱니파 위험). 배치 충전 실측(2h, 8,403건): 서브배치 6,383 중 캡(10) 포화 25%·그룹>10 = 786/4,600(최대 120 도메인).
- **① 배치 20**: 캡 20 시 전체 쿼리 **−13.7%**(−1,150/2h, 그룹 재구성 시뮬레이션) → ~5.5k/h 캡 이내. 안전 근거 = fill=10 p50 88ms(fill=1 의 1.8×뿐 — 청크읽기 지배라 fill 증가 저비용)·쿼리 최장 753자→~1.5KB(URL 한계 여유)·병합 시 페이지 수 비증가(ceil 합산 성질)·실패 파급 ≤20 도메인 1틱 이월(FAILED ~0/일).
- **② 기본값 승격(이식성, 사용자 요청)**: 테스트 서버(192.168.8.197)에서 검증된 튜닝(D58~D67) 전부를 **application.yml 기본값으로 승격** — tick PT1M·domains-per-tick 500(주야 균일)·off-peak=주간(PT30M/500/Asia/Seoul)·chunk/slice PT30M·inactive-after P3D·excluded-hostnames AAJ 23·edge-group-main-only true·sample-window PT10M·max-queries-per-hour 6000·query-batch-size 20. **adc.yaml env 는 서버 고유값(DB 접속·configprops)만 유지** → 신규 서버는 기본값 배포만으로 동일 동작. ★off-peak-max-window PT24H 복구 금지(과부하 이력) 주석 유지.
- **한계(정직)**: 캡 상향 이득 상한 13.7% — 서브배치 39%는 fill=1(틱당 due 1개 엣지), 전체 쿼리 24%는 페이지네이션이라 캡과 무관. 캡 30 은 +4.2%p 추가 — 20 관측(elapsedMs·페이스) 후 단계 적용. excluded-hostnames 기본값은 현 사이트 엣지명 기준 — 다른 WAAP 환경이면 env 로 교체.
- **검증**: build green(전 테스트)·재배포 후 batchSize=20 틱 요약·fill≤20 elapsedMs·시간당 쿼리 ≤6000 확인.

### D68. 초장문 path_template 하드 veto + 엔드포인트 저장 격리 (2026-07-04, 사용자 확정 A안+C안)
- **배경(실배포 장애)**: keeperlabo.jp 가 SQLi 스캐너 공격(sqlmap 류, IP 45.134.142.225, 버스트 06-22·07-02·07-04) — 페이로드 URL 이 엔드포인트로 수집되다 3.3K~43KB 초장문 경로가 `discovered_endpoint` unique(host,method,path_template) **btree 인덱스 행 한계(압축 후 2,704B/최대 8,191B) 초과**로 INSERT 실패(SQLSTATE 54000) → 그 틱 해당 도메인 analyze 실패 반복(2~3건/일). 컬럼(text)은 TOAST 로 무제한이나 인덱스 항목은 TOAST 불가가 근본 원인. 저장된 7,414자는 반복패턴 압축 덕(고엔트로피만 실패).
- **A안(2단 가드)**: ① `ApiScorer.MAX_PATH_TEMPLATE_CHARS`(2,048자) — `Gate.DROP_OVERSIZE`(evaluate 0단계 최우선 하드 veto) + `DroppedNonApi.oversizePath` 카운터(리포트 가시화, additive) ② `upsertDiscovered` persist 하드가드 — 초과 identity skip+집계 warn(DB 제약 위반 원천 차단). truncate 가 아닌 **drop**(2KB 초과=정상 API 불가, 페이로드 반보관 무가치. 공격 흔적은 로그/카운터로 유지).
- **C안(저장 격리)**: `discoveredRepo.save` 별 try/catch — 한 행 실패(예상 밖 오류)가 같은 도메인 나머지 행 저장을 막지 않음(pathLen/head 로그). 배치 경로는 save 별 개별 tx 라 격리 유효(analyze @Transactional 은 self-invocation 으로 배치 경로 무력 — 알려진 한계, 주석 명시).
- **기존 데이터 정리(사용자 요청)**: >2,048자 행 백업 테이블(d68_bak) 후 DELETE.
- **검증**: build green **534**(신규 10)·실 PG RED-확인 2단(가드 off→격리가 흡수해 green+오류 WARN 실증 / 가드+격리 off→index row 초과 red). 영구 RED 증거 = `oversizePathTemplateInsertFailsOnRealPgIndexLimit`(실 PG 한계 재현 고정). 매뉴얼(TW) oversizePath 반영=후속.

### D69. P* 엣지 제외 — excluded-hostnames 접두 와일드카드 (2026-07-04, 사용자 확정)
사용자: "P 로 시작하는 엣지의 로그는 API 검색용 로그 검색 대상에서 제외해도 된다."
- **구현**: `EdgeExclusions`(신규) — excluded-hostnames 항목이 `*` 로 끝나면 접두 일치, 아니면 정확 일치(D62 그대로). discovery 등록·출석·스캔 조회 공용. 기본값에 `"P*"` 추가(D67 기본값 승격 위에). 목록 나열 대신 규칙이라 신규 P 엣지 등록에도 자동 적용(노후화 방지).
- **효과**: PAI/PAIP/PAIL 등 P 계열(관측 27종) 조회 제거 — keeperlabo 류 도메인은 AAI Master 만 조회. P* 에서만 보이던 도메인은 lastSeen 정체→inactive-after 게이트가 자연 제외(D62 소프트 제외 의미 동일, self-healing).
- **검증**: build green 534 내 신규(접두 매처 2·discovery 접두 1·scan P* skip 1).

### D70. Swagger 2.0 스펙 업로드 지원 + 파싱 실패 400 매핑 (2026-07-06, 사용자 요청)
- **배경**: 업로드 샘플 검증 중 Swagger 2.0(`swagger:"2.0"`) 업로드가 **500**(`attribute openapi is missing`). `SpecFormatDetector` 는 `swagger` 키를 OPENAPI 로 감지하나 `OpenApiSpecParser` 가 `OpenAPIV3Parser`(3.x 전용)로 파싱 → 2.0 미변환(주석은 "2.0 내부 변환"이라 했으나 호출 클래스 오선택).
- **수정 3건**: ① `OpenAPIV3Parser` → 통합 **`OpenAPIParser`**(io.swagger.parser, swagger-parser 아티팩트 포함) — 2.0 은 v2-converter 로 3.0 자동 변환·3.x 는 그대로. ② `toOrigin()` **protocol-relative(`//host/base`) 처리** — 2.0→3.0 변환이 schemes 부재 시 servers.url 을 `//host/basePath` 로 내보내 host 가 경로에 접히던 것(`/api.example.com/v1/products`) 교정. ③ `SpecController.upload` **IllegalArgumentException→400 매핑**(무효/미인식 문서는 클라이언트 오류, 종전 uncaught 500).
- **검증**: build green **536**(신규 2: swagger2 host+basePath+deprecated 파싱·컨트롤러 400 매핑)·RED-확인(OpenAPIV3Parser 원복 시 2.0 테스트 red). 재배포 후 실 Swagger 2.0 문서 업로드로 라이브 확인. OpenAPI 3.x(verbose export 포함)·Postman·CSV 무회귀.

### D71. 설계문서(doc/NN)에서 'dev 구현 체크리스트' 제거 — 작업 히스토리는 TASKS/PROJECT_LOG 로 일원화 (2026-07-06, 사용자 지시)
- **배경**: doc/NN 문서 현행화 중, 27개 설계문서가 'dev 구현 체크리스트'(구현 항목·PR 완료 `[x]`·테스트 건수) 섹션을 보유. 이는 **작업 히스토리**지 **설계 근거**가 아니며, 이미 `TASKS.md`(Done)·`PROJECT_LOG.md`(세션 로그)에 동일 기록이 있어 **중복**이다. 설계문서가 완료 표기까지 안고 가면 코드 변화 시 또 하나의 동기 대상이 된다.
- **결정**: 설계문서는 **설계·근거·현재 동작**만 담는다. 구현 체크리스트·PR 완료·테스트 건수 등 **작업 히스토리는 `TASKS.md`/`PROJECT_LOG.md` 로 일원화**한다. doc 현행화 패스에서 각 문서의 'dev 구현 체크리스트' 섹션을 삭제하고, 누락 시에만 히스토리 문서로 옮긴다(대개 이미 존재).
- **영향**: CLAUDE.md 의 'D28 — PR 머지 시 설계문서 dev 체크리스트 동기 갱신' 관행을 대체한다(설계문서에 체크리스트가 없으므로 동기 대상 아님). 설계문서↔TASKS 매핑표·우선순위(D25)는 유지.
- **적용**: doc/09 §6 삭제(§7→§6)부터 시작, 나머지 26개는 각 문서 현행화 차례에 삭제.

### D72. 설정·시크릿 확장자 하드 veto 추가 — .env/.ini/.pem/.key/.tf/.save (2026-07-06, 사용자 확정)
- **배경**: 스펙 미업로드 도메인의 Shadow 최다가 스캐너의 설정·시크릿 하베스팅 탐침. 최다 판별 도메인 13.115.168.148(Shadow 168) 은 전부 `/api/config.json/.../.env`·`secrets.json`·`private.key`·`vars.tf`·`phpinfo.php` 류 — 경로에 `api` 세그먼트가 있어 점수 게이트를 통과. D55 정적 확장자 veto(.css/.js/.png…)는 이런 설정/시크릿 파일을 못 걸렀다.
- **결정**: `DEFAULT_STATIC_EXT`(하드 veto = endsWith, 점수·api키워드 무관 STATIC)에 **`.env` `.ini` `.pem` `.key` `.tf` `.save`** 6종 추가. 실 라이브 API 일 수 없는 설정·시크릿·상태 파일.
- **★.json/.yaml 제외(데이터 근거)**: 하드 veto(endsWith) 대상에서 **제외**. 운영 DB 표본상 `.json` 20,179건 중 상위가 전부 진짜 데이터 API(`139.162.99.45/chart_data/{id}/dat.json` 6,300 hits·Jira REST `validators.json`·PRTG `status.json`·펀드 `master.json`) — endsWith veto 하면 진짜 Shadow 를 대량 오탐(false-negative). `.yaml` 도 openapi.yaml 등 정당 사례 존재(`.yml` 도 미추가).
- **운영 반영(D56 런타임 경로)**: 이미 시드된 운영 DB 는 코드 `DEFAULT_STATIC_EXT` 변경만으론 반영 안 됨(`seedIfEmpty` 는 빈 테이블 전용). `POST /config/static-classify {kind:EXTENSION,value:...}` 6회로 `static_classify_rule` insert + 즉시 reload — 재배포 불필요. DB 영속 6행·목록 24종 확인.
- **검증**: build green·신규 가드 테스트(`configSecretExtensionsAreStaticButJsonYamlAreNot` — 6종 STATIC·.json/.yaml 비-STATIC). D55/D56 후속.

### D73. 미사용 스캐폴딩 제거 — central/CentralWebhookClient (2026-07-07, 사용자 요청)
- **배경**: 소스 점검 중 "본문 없는 클래스" 지적. 전수 스캔(전 타입 외부 참조 0건 검사) 결과 진짜 미사용은 `central/CentralWebhookClient`(@Component, `notifyScanCompleted()` = no-op TODO 스텁) 하나 — 어디서도 주입·호출 안 됨(central 패키지 유일 파일). 나머지 "빈 것처럼 보이던" 파일은 정상: `SchedulingConfig`(@EnableScheduling 자체가 목적)·record(필드는 헤더)·Spring Data 인터페이스·@RestController(HTTP 호출이라 코드참조 0 정상).
- **결정**: CentralWebhookClient 삭제(central 패키지 소멸). doc/07 §0 표 행·§6 '현재 상태' 문구를 "미구현(설계만)"으로 갱신. 완료 웹훅은 선택적 후속 설계로 §6 에 유지(결과 동기화는 §7 조건부 pull 로만 동작 — 무회귀).
- **검증**: compileJava+compileTestJava green(참조 0 확인 후 삭제라 무영향).

### D74. 스캔 처리량 상향 튜닝 — page-limit 5000 · domains-per-tick 650 (2026-07-08, 사용자 확정)
- **배경(실측)**: 운영 로그 분석 — Loki 쿼리(페이지)율이 24h 평탄 ~3.0–3.6k/h(피크 3,630), `max-queries-per-hour 6000` 의 60%만 사용(deferred=0, 40% 여유). **budget 은 페이지 단위 소비**(`budget.record`=fetchChunk HTTP 1회). `page-limit 2000` 에 **26%(983/3,830) 쿼리가 페이지네이션(최대 93p)** → 페이지가 예산을 갉아먹음. 백로그 due ~24k(밀림 ~6h).
- **결정**:
  - `loki.page-limit 2000→5000` — 페이지네이션 쿼리의 페이지수 ~60%↓ → 총 페이지율↓(예산 여유↑)·truncation 여유↑. queryRange 는 per-query 페이지 캡이 없어 데이터 손실은 원래 없음 — 이득은 완결성이 아니라 효율. Loki 기본 `max_entries_limit_per_query=5000` 천장 가정(서버 확인 권장).
  - `scan.domains-per-tick 500→650`(+`off-peak-domains-per-tick` 동반, 주야 균일 유지) — 여유로 백로그 감축, **점진 상향 1단계**.
  - `max-queries-per-hour 6000 유지` — 현재 미포화라 상향해도 무효과. Loki 서버 한계 확인은 보류, 이후 모니터링하며 점진 조정(사용자 방향).
- **운영 반영(재빌드 후 재배포, 사용자 최종 결정)**: 처음엔 "재빌드 없이 adc.yaml env override" 로 진행했으나, rootful 파드+무암호 sudo 부재로 재생성이 막혀 **소스 재빌드 후 재배포**로 전환. 새 `application.yml`(5000·650)이 이미지에 baked 되므로 env override 불요 → adc.yaml 의 D74 env 3개는 제거(clean, D67 철학 복귀). 절차: dev(prox-dev, .198)에서 `podman build` → `podman save` → VM(.197) scp → root `podman load` + `podman play kube --replace`. 빌드/save/scp 는 무권한 가능, load+play kube 만 root.
- **검증(예정)**: 재배포 후 configprops 는 값 마스킹(`******`)이라 못 씀 — env(`podman exec … env|grep APIDISCOVER` 는 이제 없음, 대신 baked)·동작 로그로 검증. loki-query.log 페이지네이션(중복 logql) 급감·페이지당 bytes↑(page-limit 5000 발효), adc.log `batched scan tick` jobs↑·`deferred=0`(domains-per-tick 650 발효)·백로그 감소.

### D75. domain_hostnames.host 인덱스 추가 — PG CPU 포화(seq scan N+1) 해소 (2026-07-09, 실운영 진단)
- **증상**: 테스트 VM adc-db PG 백엔드 1개가 CPU 90%+ 지속 점유(단일 프로세스).
- **진단**: `pg_stat_activity` 반복 샘플에서 `select ... from domain_hostnames where host=$1` 이 상시 active(5회 중 4회). `EXPLAIN`=Seq Scan(95,754행 중 95,753 버림·952페이지·~12ms, shared hit=순수 CPU). `pg_stat_user_tables` domain_hostnames seq_scan **1.45M**·idx_scan 없음. 원인 = `DomainConfig.hostnames` 가 `@ElementCollection(fetch=EAGER)` 라 도메인 로드마다 hostnames 조회가 발생하는데, **FK(host)는 자식 컬럼 인덱스를 자동 생성하지 않아** 매 조회가 전체 seq scan → 스캔 틱마다 도메인당(수백회/틱) 반복으로 한 코어 포화.
- **조치**: ① 라이브 즉시 `CREATE INDEX CONCURRENTLY idx_domain_hostnames_host ON domain_hostnames(host)`(무락, 2.1초). EXPLAIN Seq Scan(12ms)→Index Scan(0.066ms, **~180배**)·백엔드 CPU 90%→~0·seq_scan 정체+idx_scan 전환 확인. ② 소스 영구: `@CollectionTable(..., indexes=@Index(name="idx_domain_hostnames_host", columnList="host"))` — 이름이 수동생성 인덱스와 동일해 ddl-auto=update 무충돌, fresh 설치는 자동 생성.
- **불변식**: JPA `@ElementCollection`/`@OneToMany` 조인 컬럼(자식 FK)은 인덱스가 자동 생성되지 않는다 — 조회 술어가 되는 컬럼엔 반드시 명시 `@Index`. (동류 트랩: `@Lob`→oid D40.)
- **후속**: discovered_endpoint autovacuum/bloat → D76 에서 처리.

### D76. discovered_endpoint autovacuum 튜닝 + 초기 VACUUM (2026-07-09, D75 후속)
- **배경**: discovered_endpoint(2.3M행·1GB)가 한 번도 vacuum 안 됨 — 전역 임계(scale 0.2 → ~459k dead)에서야 발화해 1GB 대형 vacuum CPU/IO 스파이크 우려. unique 인덱스 `ukktr…`(host,method,path_template) 282MB(조회 idx_scan=0, 제약 강제 전용) never-vacuum bloat.
- **조치**: ① per-table `ALTER TABLE discovered_endpoint SET (autovacuum_vacuum_scale_factor=0.05, autovacuum_vacuum_threshold=10000, autovacuum_analyze_scale_factor=0.05, autovacuum_analyze_threshold=10000)` → 발화 ~125k dead(기존 ~459k 대비 ~3.7배 자주·소량 = 스파이크 분산). ② 즉시 `VACUUM (ANALYZE, PARALLEL 0)` — dead 153k→**0**·통계 갱신(1m53s, 무락). reloptions 는 pg_class 영속(재기동·재배포 유지 — DB 는 hostPath). ★JPA `@Table` 로 표현 불가한 DB-레벨 설정 → fresh DB(신규 /opt/adc)는 재적용 필요(ops 스텝, deploy 매뉴얼에 기재).
- **발견(shm)**: 컨테이너 `/dev/shm` 기본 **64MB** 라 **병렬 VACUUM 실패**("could not resize shared memory … No space left on device", 282MB 인덱스 병렬 처리 시). `PARALLEL 0` 로 회피. autovacuum 은 병렬을 안 써서 무영향. 병렬 유지보수/대형 병렬쿼리가 필요하면 adc.yaml 에 shm 확대(별도 결정).
- **잔여(선택)**: 282MB 인덱스 bloat 는 VACUUM 으로 안 줄어듦 — 디스크 회수하려면 `SET max_parallel_maintenance_workers=0; REINDEX INDEX CONCURRENTLY ukktr…`(I/O 큼·성능 아닌 디스크 목적, shm 회피 위해 병렬 0).

### D77. 컨테이너 /dev/shm 확대 시도 → SELinux 로 실패·롤백, 비병렬로 대체 (2026-07-09, 사용자 요청)
- **배경**: D76 에서 `VACUUM (ANALYZE) discovered_endpoint` 가 컨테이너 기본 `/dev/shm` 64MB 부족으로 병렬 워커 DSM 세그먼트 리사이즈 실패("could not resize shared memory … No space left on device"). 병렬 유지보수(REINDEX 등) 지원을 위해 shm 확대 요청.
- **시도**: adc.yaml db 컨테이너에 `/dev/shm` = `emptyDir(medium:Memory, sizeLimit:1Gi)` 마운트 + 소스 HEAD(@Index D75 포함) 재빌드(이미지 9cf1551) → `podman play kube --replace`.
- **실패(SELinux)**: el8 SELinux Enforcing 이 **emptyDir(Memory) tmpfs 를 컨테이너 MCS 라벨로 relabel 하지 않아**, PG 가 `/dev/shm` 공유메모리 세그먼트 접근 거부(`FATAL: could not open shared memory segment: Permission denied`) → adc-db 크래시루프. **즉시 롤백**(shm 마운트 제거한 adc.yaml 재배포) → DB 복구·app UP 확인. (hostPath 와 동일 부류 트랩 — hostPath 는 chcon 으로 되지만 tmpfs emptyDir 은 relabel 미적용.)
- **재검토(결론)**: shm 확대는 **불필요**. "shm 부족으로 못 한 작업"(discovered_endpoint VACUUM)은 이미 D76 에서 `PARALLEL 0` 으로 완료(dead 0). 유일한 잔여인 282MB 인덱스 bloat 회수도 `SET max_parallel_maintenance_workers=0; REINDEX INDEX CONCURRENTLY` **비병렬**로 하면 /dev/shm 이 필요 없다. 병렬(속도)만 아쉬운데 이득 대비 위험·복잡도가 커 shm 확대는 보류. 정 필요하면 podman 라벨 annotation(`io.podman.annotations.label/db: disable`, 단 db 컨테이너 SELinux 분리 해제=보안 다운) 또는 tmpfs relabel 방법을 별도 검증.
- **소스 상태**: adc.yaml 은 shm 마운트 제거로 롤백(HEAD=배포본 일치). 이미지 9cf1551(@Index 포함)은 배포 유지.
- **잔여 처리(완료)**: 282MB bloat 인덱스 `ukktr…` 를 `SET max_parallel_maintenance_workers=0; REINDEX INDEX CONCURRENTLY`(비병렬·무락·23.6초, /dev/shm 불요)로 회수 — **283MB→205MB(78MB↓)**, 테이블 총 1031→956MB. 인덱스 valid·unique 유지, invalid 잔여 0, 서비스 UP. shm 확대 없이 목적 달성.

### D78. 스코어링 정책 조회 강화 — effective 노출 + threshold 최상위 분리 + 신호 설명(ko/en), API-only (2026-07-13, 사용자 확정)
- **배경**: API 판단 스코어링 정책(profile·14 weight·threshold·matcher)은 DB(`classification_config`/`domain_classification_config`) + REST(PUT/PATCH) 로 이미 운영·즉시적용(캐시 무효화)된다. 그러나 (1) 전역 GET(`GET /classification`)이 저장값만 반환 → preset(MIDDLE·customWeights=null)일 때 **실제 적용 중인 14 weight·threshold 값이 안 보임**(도메인 GET 은 이미 effective 노출·비대칭), (2) threshold 가 effective 의 `weights` 레코드 안에 매몰 — 쓰기는 최상위 `thresholdOverride` 인데 읽기는 매몰(비대칭). threshold 는 가산 신호가 아니라 판정 합격선(score≥threshold→API 후보)이라 성격이 다르고 전 엔드포인트 경계를 좌우하는 최대 영향 노브.
- **결정**: ① 전역 GET 에 `effective` 블록 추가(도메인과 동형). ② threshold·repeatMinCount 를 effective **최상위로 분리**(전역·도메인 동일), `weights` 는 override 가능한 14키 맵(PUT/PATCH 바디와 대칭). ③ `descriptions`(ko/en) 조회 응답에 첨부 — 신호 의미를 한글·영어로(값 맵은 순수 숫자 유지, 설명은 별도 블록). 매뉴얼에는 한글 설명. ④ **CLI 미구현(API-only)** — 분류설정용 CLI 없고, CLI 는 서버와 별도 원샷 프로세스라 실행 중 서버 캐시(즉시적용) 접근 불가 → 부적합(사용자 확정).
- **무회귀**: 스코어링/분류 로직·쓰기 계약(PUT/PATCH·검증 400·404·즉시적용) 불변. 조회 노출 형태만 변경. 도메인 GET `effective` 형태 변경(threshold 최상위·14키 맵)은 정보성 블록·쓰기 미영향(매뉴얼 갱신). `/discovery` 별도 `EffectiveClassificationView` 는 미터치.
- 설계 상세·API 입출력 = doc/39. 조회는 있으나 "실적용 값" 미노출이던 공백 해소가 핵심. 파일=DB 유지(사용자 결정 — 도메인별 설정도 있어 파일화보다 DB 운영이 적합).

### D79. 8.3 로그변수 소비 범위·가중치·시뮬레이션 방침 (2026-07-13 확정 → 2026-07-14 **구현·시뮬·재배포 완료**, doc/40, PR #73)
- **범위**: 8.3 append 필드 중 **`$server_protocol`·`$upstream_addr` 제외**(효과 ★ 미미, 사용자). 매뉴얼 §8.3 log_format 예시에서도 두 줄 삭제. 소비 = `$sent_http_content_type`(→endpoint_kind, manual 8.2)·`$http_accept`·`$http_x_requested_with`·`$http_origin`·`$auth_scheme`(→ApiScorer 양성 신호). ACRM 은 이미 구현(M3)·설정만. 요청 `$content_type` 는 로깅만·미소비(예약).
- **가중치(신규 4)**: 현재 로그에 없는 항목이라 실데이터 보정 불가 → **a-priori 기반 기본값**, 현행 판정 크게 안 흔드는 modest 값(MIDDLE): acceptJson 0.20·xRequestedWith 0.28·originHeader 0.15·authScheme 0.28(HIGH/LOW 는 doc/40 §3). 양성 가산만(부재 감점 금지).
- **안전(사용자 질문 답)**: "현재 스캔된 API 가 api 아닐 확률 = **0%**." ① DORMANT(인덱스 -1)→로그·설정 전 무영향. ② 양성 가산→단조 비감소→현행 API(score≥threshold) 격하 불가(가중치 무관). 유일 변화=경계 비-API 의 상향 승격(과승격 상한은 시뮬레이션으로 측정).
- **시뮬레이션 우선**: 점수 미영속(report_json 엔 basis/score 없음 — serve-time 계산) → `discovered_endpoint`(2.9M) 피처로 재계산·층화샘플 → 현행 DROP 중 `[threshold−Σw, threshold)` 카운트로 과승격 상한 측정 후 가중치 확정. 구현 전 실행(doc/40 §5).
- **구현 방식**: ACRM(M3) 선례 — 신규 인덱스 기본 -1 DORMANT·양성 가산·kind 는 부재 시 폴백. 무회귀 보장. 상세·파일·테스트 = doc/40.
- **완료(2026-07-14)**: §5 시뮬(과승격 상한 단일신호 ≤31,391 = 전체 1.06%·89.5% API 구조 보유)·§6 구현(4신호 다수결 `count*2>=hits`·CT 2xx-only)·QA P2/P3·재배포(DORMANT, ddl-auto 신규 4컬럼 3.05M행 마이그레이션 검증)까지 완료. 남은 것 = 매뉴얼(TW)·활성화(운영 log_format 선행, **D80**).

### D80. 8.3 활성화 전제 = 엣지 nginx log_format 표준화 (2026-07-14, 실측 발견 — 규약 doc/41)
- **발견**: 활성화 사실 확인(운영 Loki 소량 샘플) 결과 ① 8.3 필드(sent_content_type/accept/x_requested_with/origin/auth_scheme)는 **현재 어느 엣지 로그에도 없음** ② 엣지별 코어 log_format 이 **이질적**(24필드 리치 `$type@19·request_id@23` / 19필드 린 `$type` 없음·`request_id@18` / 18필드 모니터링, 엣지 AAJ14/PAK21/PARV2/PLDI1).
- **결정**: 파서는 **전역 단일 절대 인덱스**만 지원(엣지별 인덱스 미지원) → "끝에 append" 는 이질 포맷에서 다른 인덱스를 만들어 단일 설정 소비 불가. 따라서 **활성화 선행 = 8.3 대상 엣지를 24필드 코어로 통일한 뒤 8.3(24~30) append**. 통일 안 되는 엣지는 8.3 대상 제외(현행 DORMANT 무영향). 운영팀 전달용 규약 = **`doc/41-nginx-log-format-spec.md`**(C안).
- **무회귀**: 인덱스를 로그 변경 전 미리 세팅해도 `LogLineParser` 의 `f.length > idx` 가드로 null 처리 → 신호 부재 → 현행 불변. 위험은 '필드가 틀린 위치에 오는' 경우뿐(→ §41 규약으로 방지).
- **활성화 자체는 매니저/운영 지시로 후속**(이번 세션은 규약 문서화까지).

### D81. 스캔 due/워터마크 발산 대응 — 유령 도메인 억제(A+C) + 하드 정리(B/B-2/D) (2026-07-14, 설계 doc/42, PR #76)
- **배경**: 봇/스캐너/스푸핑 Host 헤더가 캐치올 엣지로 무검증 자동등록 → endpoint 0 유령 ~39.5k 가 due 큐 66% 잠식 → 밀림 p50 4.4일/max 14일 발산.
- **A(즉효)**: `NEW-PAJ*` 엣지 제외(설정 1행, EdgeExclusions 접두 매처). **C(근본)**: discovery 등록 status 신뢰 필터 — 404(미존재 vhost)·470(WAAP 차단)만 관측된 Host 는 등록·lastSeen 갱신 배제(`discovery.probe-statuses`, 기본 [404,470], 빈=무회귀). 서버측 LogQL 라벨 필터(광역 |= 아님·부하 안전).
- **B/B-2/D(파괴적 정리) 방식 = hard DELETE 채택(사용자 결정)** — soft(enabled=false) 아님.
  - 근거: `DomainUpserter` 는 재관측 시 재-INSERT(enabled=true 기본) 하므로 원래 soft 가 재유입 sticky 차단 명분이었으나, **C 가 프로브 재유입을 원천 차단**하면 재등록되는 것은 실 트래픽뿐 = **자기치유**. 즉 C 도입으로 soft 의 명분이 대체됨. 사용자는 완전 정리(행 제거) 선호.
  - **트레이드오프(명시)**: hard DELETE 는 재트래픽으로 재등록 시 `discovered_at`/firstSeen **리셋 = 이력 손실**(7일 지속성 기준 재누적). soft 는 이력 보존이나 재활성 수동. → ★**실행은 백업 없이(2026-07-15, 사용자 최종 결정)** — 안전기준 통과분(unsafe 0)은 오탐도 자기치유(C 차단+실트래픽 재등록)로 복구되고 백업 유지비용을 회피. 무백업 안전장치=dry-run 프리뷰·세션 temp 삭제셋 고정·pre/post-guard(예상 밴드 이탈·orphan≠0 시 롤백)·단일 원자 트랜잭션(`sample/ghost_domain_cleanup.sql`).
- **안전기준(§3, 'endpoint 0' 단독 판정 금지)**: (a)스캔이력 + (b)7일 지속 + (d)사용자/운영자 흔적 없음(`interval_override`·`base_path_strip`·`spec_record`·`documented_api`·**`domain_classification_config`**) + endpoint 0(ghost) / hostnames 전부 제외엣지(excluded-only).
- **FK 실측**(운영 PG information_schema): 앱 FK 는 `domain_hostnames.host→domain_config.host` 단 1개 → 삭제 순서 자식(domain_hostnames) 먼저 → 부모(domain_config) 마지막. watermark/scan_result/discovered_endpoint 는 FK 없음(정합 purge).
- **D48-F 경계**: "삭제·비활성 없음"은 **자동 스케줄러 정책** 결정 — 이번은 **운영자 승인 1회 정리**라 상충 아님.
- **실행 순서·결과**: A+C(PR #76)·재배포(`a171edf`) → 관찰(C 효과: 신규 유령 유입 1,270→41/일 **−97%**) → **사용자 승인 하 실행 완료(2026-07-15)**. ★앱 가동 중 대량 멀티테이블 DELETE 가 discovery/scan 의 domain_config·watermark 쓰기와 **데드락** → adc-app 잠깐 stop(≈2분·백그라운드 워커 무영향) 후 무경합 원자 삭제(runbook 절차 반영). **결과: 31,985 삭제(ghost 29,361+excluded-only 2,624)·due 53.3k→20.3k(−62%)·near-now 7.1%→9.0%·orphan 0(FK 정합)**.

### D82. 무접속 도메인 상태 관리 — 원인 규명 + 7일 무요청 비활성 정책 (2026-07-20, 설계 doc/43, 구현 전)
- **원인 규명(실측 2026-07-20)**: 워터마크 롱테일 발산은 **window 상한도 라운드로빈 기아도 아니다**. 최근 시도(≤24h)됐는데 watermark 뒤처진(>3d) 도메인 **0**(window 상한 아님), selectable(lastSeenAt≤3d) 중 24h+ 미시도 **0**(기아 아님·throughput 650/PT1M 충분). 굶는 enabled 8,201개 = **100% lastSeenAt>3d 로 inactive-after=P3D 게이트에 스킵**된 무접속분. 즉 라이브 트래픽엔 발산 없음, 롱테일은 무접속 도메인이 enabled 인 채 워터마크 얼어붙은 **정상 동작(D59)의 누적 착시**.
- **7일 미스캔 도메인 29,082** = enabled 8,201(게이트분·enabled 유지) + disabled 20,881(07-02 유령잔존, doc/42 삭제 별개).
- **요구(사용자)**: ① 7일 무요청→비활성, 단 `.cloudbric/pron/`·`.cloudbric/afc/` 요청은 실요청에서 제외 ② 비활성이라도 수동 API 스캔·요청 재개 시 그때부터 활성 ③ DB 가 status 보유·규칙대로 변경·활성만 스캔.
- **설계 결론(doc/43)**: 기능 대부분은 기존 lastSeenAt 게이트가 이미 수행 → 실 갭 3가지.
  - ① **경로 제외(필수)**: discovery `buildLogQL` 에 uri 라벨필터(`\| uri !~`) 추가(pattern 인덱스8=`<uri>`, status 필터와 동일 안전패턴·광역 |= 아님). 두 경로만 받는 도메인은 관측 0→lastSeenAt 정체→게이트 자연 제외(신규 등록도 억제=doc/42 C 와 결).
  - ② **임계 = 기본 P7D·설정 override**(사용자 확정 2026-07-20). `scan.inactive-after` 이미 설정화 → 기본값 1행 + 운영자 조정 가능. 스캔량↑ 트레이드오프는 Loki 예산 관찰.
  - ③ **status 표현 = 안 B 영속 enum**(사용자 확정 2026-07-20). `domain_config.activity_status`(ACTIVE/INACTIVE·기본 ACTIVE·인덱스) + `activity_status_changed_at`(전이 이력/중앙연동). ★`enabled`(사용자 수동·자동토글 금지 doc/30 §5) 과 별도 축, **"활성만 스캔"=enabled AND activity_status=ACTIVE**. 단일 진실원: lastSeenAt(입력)→sweep→activity_status(결정)→scan. 전이 3경로: (i) discovery 틱 종료 sweep 이 lastSeenAt<cutoff→INACTIVE, (ii) `DomainUpserter.upsert` 실요청 재관측→ACTIVE, (iii) 수동 스캔→ACTIVE(③). 진동은 C(probe필터)+경로제외로 실요청만 관측 + 7일 창 히스테리시스로 차단. findDue* 3쿼리의 lastSeenAt staleCutoff 술어를 `activity_status='ACTIVE'` 로 교체.
- **재활성**: (②) 요청 재개→upsert 가 lastSeenAt+ACTIVE flip→다음 틱 재포함. (③ 확정) **수동 스캔(scan-now/scan)은 즉시 스캔 + activity_status=ACTIVE flip 으로 주기 스캔 대상 승격** — 이후 7일 무요청 시 sweep 이 다시 INACTIVE.
- **구현·배포 완료(2026-07-20, main `5bced89`, 이미지 `4d876686b814` .197 배포·health UP·롤백 `prev-d82`=a171edf)**. 566 테스트 green(실 PG 포함). ★uri 필터 구현 함정: 이중따옴표 LogQL 문자열은 `\.` 이스케이프 규칙 위반→Loki **400** → **백틱 raw 문자열**로 확정(운영 2분창 status=200 실검증). ★마이그레이션: `columnDefinition default 'ACTIVE'`(D79 boolean 패턴)로 ddl-auto ADD 시 기존 57,566행 ACTIVE(안 그러면 NULL≠ACTIVE 로 스캔 전면 중단). 배포 후 첫 discovery: vector=16,986·Loki 에러 0·**deactivated=37,190**(초기 all-ACTIVE→무접속 7일+ 강등 수렴)·scannable(enabled&ACTIVE)=**20,377**(P3D 시절 selectable ~8.9k 대비 P7D 확대). 잔여=사후 관찰(1~2일).

### D83. endpoint-yield 게이트 — 지속 0-endpoint 유령 억제(ghost_suppressed) (2026-07-22, doc/43 §5, main `c9e93b5`·이미지 `a2b31a1c8c4b`)
- **배경(3단계 조사)**: ACTIVE(봇 트래픽으로 유지)인데 스캔하면 self-endpoint 0 인 유령 ~1,837(74% 신규 유입). 봇/foreign-host/spoofed Host. **엣지 제외(doc/42 A) 불가** — 최상위 유입 엣지 `new-PAJ11`(947도메인)이 **실서비스 501 + 유령 439 혼합 catch-all** 이라 엣지째 빼면 실서비스 501 손실. (부수 발견: `EdgeExclusions` 대소문자 구분이라 `new-PAJ11`(소문자)이 `NEW-PAJ*` 제외 회피 — 그러나 실서비스 보유라 **제외하면 안 됨**, 대소문자 무시 수정 금지.)
- **결정**: 엣지가 아니라 **endpoint 산출 기반 가역 억제**. `domain_config.ghost_suppressed`(boolean·ddl-auto default false·D79 패턴) 신설. **"스캔 대상"=enabled AND activity_status=ACTIVE AND NOT ghost_suppressed** (3축).
- **게이트**(discovery 틱, sweep 후): `scan.ghost-after`(기본 P7D·0=off) — `discoveredAt < now−ghost-after`(지속성) + 스캔이력(last_scan_attempt) + self-endpoint 0 + 무설정(interval_override·base_path_strip·spec_record·documented_api = 안전, doc/42 기준) → `ghost_suppressed=true`. discoveredAt null(수동 등록) 제외.
- **가역**: 수동 스캔(`markActive`, scan-now/scan)이 `ghost_suppressed=false` 로 해제 → 재스캔·재평가. 봇 트래픽 재관측(upsert)은 해제 안 함(억제 유지). ★자동 복구 없음(GHOST가 실서비스로 변하면 수동 스캔 필요) — doc/42 하드삭제 트레이드오프의 가역 버전.
- **배포 결과(2026-07-22)**: 첫 게이트 `ghostSuppressed=537` 억제 → scannable 10,729→**10,153**. 실서비스(new-PAJ11 501 등) 보존 확인. 571 테스트 green(실 PG 게이트·markActive 해제). DDL 경고 0.
- **관련**: doc/42(유령 A/B/C·엣지 제외 한계), D82(activity_status), D81.

### D84. scan-status 유형별 API 목록 + /result reason 필드 재배치 (2026-07-22, 사용자 요청)
- **배경**: 운영자가 scan-status 에서 유형별 API 목록을 한눈에 보고 싶어 함. `/result` finding 순서는 `classification` 다음에 `reason` 이 오길 원함.
- **결정 ①(scan-status apis)**: `GET /scan-status` 응답에 `apis{discovered,active,shadow,zombie,unused}` 가산(additive). 각 원소 `"GET [https://host/pathTemplate]"`. **출처=per-scan report_json finding**(summary 와 동일 집합 → 개수 일관, 사용자가 우려한 /result 대비 불일치 없음). ★report_json 의 finding 은 타입 판별자 없이 직렬화(classification 미저장)이라, `/result` 인라인과 **동일한 forHost 판단근거 매칭**(EndpointIdentity.key)으로 분류. `rationaleByKey` 헬퍼로 두 경로 공용화. report_json null(미스캔)=빈 목록·forHost 미호출.
- **결정 ②(/result 순서)**: inlineBasis 에서 `reason` 을 떼어내 `classification` 직후 재부착(미매칭 finding 은 끝으로). 최종 순서 `…confidence, params, low_confidence, classification, reason, basis`.
- **트레이드오프**: scan-status 가 forHost 호출로 무거워짐(원래 doc/07 §3.2 "경량 상태 메타"). 사용자 명시 요청이라 채택하되, 중앙이 고빈도 폴링 시 부하 재검토 여지. scheme 미저장 → `https` 고정(WAAP API 트래픽 전제).
- **영향**: scan-status=additive(무파괴), /result=필드 **순서 변경**(값·ETag 불변). 매뉴얼 반영 완료(api-rest-manual). build green 573.
- **결정 ③(엔드포인트 경로 개명, 사용자 요청 2차)**: `GET /scan-status` → **`GET /scan-result`**, `GET /result` → **`GET /scan-result/detail`**. `ScanController` @GetMapping 2곳 + 통합테스트 MockMvc 4곳 수정(Java 메서드명 status()/result() 은 유지 — 경로만 변경, 불필요 churn 회피). Spring 경로 매칭 무충돌(`/scan-result` vs `/scan-result/detail` 별개). ★**breaking**(경로 변경) — 중앙 연동 반영 필요. build green 573.
- **배포(2026-07-22)**: PR #77 squash-merge `main dafbb69` + 매뉴얼 반영 → 이미지 `100606f00f38` `.197` 배포·health UP·롤백 `prev-d84`(a2b31a1c8c4b). 실측 검증: `/scan-result` apis 정상(shadow 105=summary.shadow)·`/scan-result/detail` reason 이 classification 뒤·구 경로 `/scan-status`·`/result` 404.
- **관련**: D55(/result 인라인), doc/07 §3.2/§3.3, doc/35 M4/M5.

### D85. /discovery 요약·상세 분리 + Finding 에 classification 직렬화 (2026-07-23, 사용자 요청)
- **배경**: `/discovery` 가 full CombinedDiscovery(findings **type-erased**·classification 은 별도 `rationale[]` 병렬 배열)라 (1) 요약으로 보기 불편 (2) **각 finding 에 유형(shadow/zombie) 미표시**. 사용자: `/scan-result` 처럼 요약 + **각 API 유형 노출** 요구.
- **결정 ①(분리)**: `GET /discovery` = **요약**(`DiscoverySummaryView`: host·summary·apis) — forHost findings 를 `classification()` 로 유형별 카운트+목록(`ApiLists`, `/scan-result` 와 동형·창 무관 누적). `GET /discovery/detail` = 기존 full CombinedDiscovery(findings+rationale+effectiveClassification). `ApiLists.label()` 공용 추출(scan-result·discovery 공유). `summary.discovered`=전체 finding 수.
- **결정 ②(근본)**: `Finding.classification()` 에 `@JsonProperty` 추가 → **findings[] 가 유형을 인라인 직렬화**. 그동안 Finding 에 타입 판별자가 없어 미직렬화됐고(`/scan-result` 는 serve-time `inlineBasis` 로 보완), `/discovery` findings 엔 유형이 없었다. 이제 `/discovery/detail`·scan-now·report_json 의 모든 finding 이 self-describing. report_json 은 **additive**(기존 소비 무파괴)·ETag 는 새 스캔부터 반영. `/scan-result/detail` 은 `inlineBasis` 가 여전히 serve-time 재계산 우선(reason 순서·basis 유지, 무회귀).
- **영향**: `/discovery` **breaking**(응답이 요약으로 축소·findings 제거) → 상세 필요 시 `/discovery/detail`. 중앙/매뉴얼 반영 필요. build green **575**(CombinedDiscoveryControllerTest 요약/상세 + 통합테스트 /discovery 요약·/discovery/detail classification).
- **관련**: D84(scan-result·apis), doc/34(rationale/basis), doc/26(결합 뷰).

### D86. 응답 배열 전역 {count, items} 래핑 (2026-07-23, 사용자 요청)
- **배경**: 소비자(파서)가 배열 원소 개수를 함께 받고 싶어함. 후보였던 `<name>Count` 형제 필드는 **배열마다 count 필드명이 달라져 파서가 번거롭다**(사용자 지적) → 사용자 선택: 배열을 `{count, items}` 로 감싸 **어디서든 `.count`·`.items` 두 키로 일관 접근**.
- **결정**: 모든 API JSON 응답의 **모든 배열**(필드값·최상위·중첩·배열 원소 내부)을 `{"count":n,"items":[...]}` 로 재귀 변환. `ArrayCountJson.wrap`(순수 재귀 트리 변환) + `ArrayCountResponseAdvice`(`@RestControllerAdvice(basePackages="..api")` ResponseBodyAdvice) 로 **전역 적용**(DTO→valueToTree→wrap). `/scan-result/detail` 은 String 본문(report_json 재직렬화)이라 어드바이스 우회 → `inlineBasis` 에서 직접 `wrap`.
- **스코프**: api 패키지 컨트롤러만(actuator 등 타 패키지 무영향). String(이미 래핑)·null(204/202)·비-JSON 은 통과. 요청 body(입력)는 대상 아님(응답만).
- **영향**: **breaking**(배열→객체, 소비자 `.items` 접근 필요). 단위테스트(컨트롤러 직접호출)는 무영향, MockMvc 통합/컨트롤러 테스트(Postgres·Classification)·`inlineBasis` 단위테스트는 `.items`/`.count` 로 갱신. build green **575**. 매뉴얼 전역 콜아웃+전 예시 배열 래핑.
- **배포(2026-07-23, D85 와 함께)**: PR #79 squash-merge `main 944bd0f` → 이미지 `217429868306` `.197` 배포·health UP(~15s)·롤백 `prev-d85`(100606f00f38). 실측: `/scan-result`·`/discovery` `apis.*`={count,items}, `/scan-result/detail`·`/discovery/detail` `findings`={count,items}·중첩(`params.query`·`basis.signals`)도 래핑·finding `classification` 인라인(D85), 루트배열(`/domains`·`/spec`)={count,items}·중첩 `hostnames` 래핑.
- **관련**: D84·D85(scan-result·discovery apis·finding classification), doc/07.

### D87. 경로 정규화 — 숫자ID(라벨) 세그먼트 → {id} (2026-07-23, 사용자 요청)
- **배경**: `/campaigns/1337477(체험단)`·`594387(체험단)`·`990312(체험단)` 이 논리적으로 한 엔드포인트인데 3개로 분리돼 검출됨. 원인 = `PathNormalizer` 의 `NUMERIC=^\d+$` 는 **순수 숫자만** `{id}` 치환하고, `숫자(라벨)` 형태(앱이 URL 에 사람용 라벨을 삽입)는 미치환 + 표본<20 이라 2차 통계 승격(`statVarMinDistinct=20`)도 미발동.
- **결정**: `PathNormalizer.classify()` 에 `NUMERIC_LABELED=^\d+\(.*\)$` 규칙 추가 → `{id}`. 라벨은 흡수(캠페인 상세를 단일 `/campaigns/{id}` 로 수렴). `summer(2026)` 처럼 숫자로 시작하지 않으면 유지(오병합 방지).
- **영향**: 새 스캔부터 반영(재배포 필요). 기존 검출 행은 다음 스캔 때 새 템플릿으로 재수렴. build green 576.
- **관련**: doc/02 §3.2(정규화 휴리스틱), doc/13(고카디널리티 2-pass).

### D14. 세션 메모리 문서 운용
`doc/TASKS.md`(할일/완료), `doc/PROJECT_LOG.md`(작업로그), `doc/DECISIONS.md`(결정)를 세션 메모리로 운용.
새 세션은 항상 이 3개를 참고해 이어서 작업(CLAUDE.md 에 명시). 기존 checklist.md·context-notes.md 는 이 문서들로 흡수·일원화.
