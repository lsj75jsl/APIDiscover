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

### D14. 세션 메모리 문서 운용
`doc/TASKS.md`(할일/완료), `doc/PROJECT_LOG.md`(작업로그), `doc/DECISIONS.md`(결정)를 세션 메모리로 운용.
새 세션은 항상 이 3개를 참고해 이어서 작업(CLAUDE.md 에 명시). 기존 checklist.md·context-notes.md 는 이 문서들로 흡수·일원화.
