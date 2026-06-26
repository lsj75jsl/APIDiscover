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

### D47. 운영자 도메인 목록 CLI `-domain -ls` (doc/33 §15) — 채택(구현·머지)
운영자가 수집 도메인 목록만 확인. ★사용자 지정 단일대시 `./{바이너리} -domain -ls`(기존 `--adc.cli.export-domain=` 프로퍼티 스타일과 다름).
- **main() raw-arg 감지**: `-domain` AND `-ls` 동시 존재(non-option args) → CLI 모드(web NONE·cli 프로파일). 내부적으로 `--adc.cli.list-domains=true` 주입 → `CliProperties.listDomains` → `CliListRunner`(@Profile cli) — 외부 단일대시 UX·내부 프로퍼티 구동 runner 분리(기존 패턴 균일).
- **출력**: `domainRepo.findAll()` → **stdout**(사용자 '확인' 의도, 파일 아님). 컬럼 host·enabled·#hostnames·discovered_at·last_seen_at. 빈 목록=정상 exit 0, DB 오류 비0. Loki 무관(read only). `--csv` 옵션 미포함(과설계).
- **★arg 스타일(권장 = 혼재 허용)**: 신규 목록만 `-domain -ls`(사용자 명시 존중), 기존 export/scan(`--adc.cli.X=`)은 불변(출하 명령·런북 비파괴). 전면 통일(단일대시 서브커맨드) 미채택 — 기존 명령 파괴. 통일 문법은 원하면 별도 마이그레이션(하위호환 동시), 피스밀 금지. `-domain -ls` 를 향후 통일 seed 로 기록.
- **PR 구조(권장 = 2 PR)**: PR1.1(D46, 긴급·watermark 정합 정밀리뷰) 먼저·독립, 목록 CLI 별도(DB-only 저위험). 공유 브랜치면 분리 2커밋으로 독립 리뷰. 1 PR 도 무방하나 긴급도·관심사 분리상 2 권장.

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

### D14. 세션 메모리 문서 운용
`doc/TASKS.md`(할일/완료), `doc/PROJECT_LOG.md`(작업로그), `doc/DECISIONS.md`(결정)를 세션 메모리로 운용.
새 세션은 항상 이 3개를 참고해 이어서 작업(CLAUDE.md 에 명시). 기존 checklist.md·context-notes.md 는 이 문서들로 흡수·일원화.
