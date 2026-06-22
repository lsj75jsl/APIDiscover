# TASKS — 작업 목록 (세션 메모리)

> 새 세션 시작 시 **반드시 이 파일을 먼저 읽고** todo/done 을 파악한 뒤 작업을 이어간다.
> 완료한 항목은 `[x]` 로 표기하고 **Done** 섹션으로 옮긴다.
> 의사결정은 `doc/DECISIONS.md`, 진행 로그는 `doc/PROJECT_LOG.md`, 설계 상세는 `doc/00~07`.

---

## TODO

### API 후보 점수화 모델 + 프로파일 (08 문서, **린 채택** — 평가 완료)
> 전체 복제 아님. 우리 가용 신호만으로 린하게. **가중치 보정 완료(08 §8)** — 보정된 weight 사용.
- [x] **(완료) 가중치 보정**: api.weble.net(API) vs dreampark(웹) 실데이터 → html penalty 제거, host_api_subdomain+cors_preflight 추가, static 강화. 분리 마진 0.82 vs 0.27 (08 §8)
- [x] `ApiScorer` — 가용 신호 가산식 점수(clamp 0..1), Classifier 앞단 게이트 (보정 weight)
- [x] 신호 추출: path shape(api/version/id/graphql/machine), write_method, query, non_browser_ua,
      host_api_subdomain, cors_preflight(OPTIONS sibling), static penalty(확장자/library), repeat_bonus (html penalty 미사용)
- [x] api_confidence(후보성) vs shadow/zombie confidence(실재성) 역할 분리 — Classifier 게이트 후 매칭/분류
- [x] `min_api_confidence` 게이트 → 미달 unmatched 는 보고 안 함, OPTIONS 는 CORS 신호로만(미보고)
- [x] 기존 `EndpointKindClassifier`(static/web_page)를 점수 penalty 입력으로 흡수
- [x] 프로파일 HIGH/MIDDLE/LOW preset (threshold+weights) — **custom(override)는 설정 연동 시**
- [ ] 설정 저장: 전역 classification(DB 단일 레코드) + 도메인 override(custom weights/threshold), 병합 규칙
- [ ] 중앙 API: `GET/PUT /classification`(전역), `GET/PUT /domains/{host}/classification`(도메인, effective 노출)
- [ ] non_api dropped observation 메트릭 (현재 단순 제외)

### 보류 (08 §9 — 현 시점 미채택)
- [ ] (보류) endpoint decision cache — 배치 재집계 구조라 이득 작음, 필요 시 재검토
- [ ] (보류) 참고 설계의 정확한 가중치 값 — 우리 데이터 보정 후 확정
- [ ] (확장) `$type` taxonomy 에서 API성 값(xhr/json) 확인 시 `response_type_api` 양성 가중치 추가

### 정규화 고카디널리티 방지 (02/08 통합)
- [ ] 통계적 `{var}` 승격 + 도메인 endpoint template 상한 + endpoint별 param/field 상한, 초과 시 `dropped_limit`
- [ ] 파라미터 후보(body 없음 → query/path만): query param name/presence/length bucket, path param 후보
- [ ] sensitive key matcher (query key 저장 시 마스킹/제외)

### 스펙 파서 / Spec Store (03 문서)
- [ ] `PostmanSpecParser` 실구현 (item 트리 DFS, url.path join, `:var`/`{{var}}`→`{var}`, `[DEPRECATED]` 규약)
- [ ] `CsvSpecParser` 실구현 (헤더 검증, deprecated 파싱, `:var`→`{var}`)
- [ ] 매처 캐시 무효화 — SpecStore 업로드 시 `(host, specVersion)` EndpointMatcher 캐시 evict
- [ ] 멀티 스펙 업로드(여러 문서 병합) — 1차 범위 밖, 후속

### 정규화/인벤토리 (02 문서)
- [ ] 통계적 정규화 보정 3단계 (구조 클러스터링 + 카디널리티 → slug 변수 추론)
- [ ] 실재성 404-only 필터 (인벤토리 단계에서 명시 적용)
- [ ] endpoint_kind referer 보조 신호 (현재 `$type`+확장자만 사용)
- [ ] `$type` 전체 taxonomy 광범위 샘플링으로 확정 (다양한 status/method)
- [ ] distinct/분위수 대용량 근사 (HLL/t-digest) — 현재 정확 Set/nearest-rank, 규모 보고 교체

### 분류 (04 문서)
- [ ] 버전 기반 Zombie 추정 (04 §5, deprecated 미표기 구버전)
- [ ] Zombie severity 산정
- [ ] (한계) preflight vs 진짜 OPTIONS 구분 불가로 스펙 OPTIONS operation Unused 오판 가능

### 리포트/출력 (01 문서)
- [ ] `low_confidence` 분리 노출, `spec_source.warnings` 리포트 반영

### MSA/연동 (07 문서)
- [ ] 서비스 간 인증 실구현 (mTLS 또는 OAuth2 client-credentials) — 현재 `SecurityConfig` permitAll
- [ ] 완료 웹훅 (Worker→중앙 scan-events push) 실구현
- [ ] 도메인별 `intervalOverride` 스케줄 반영

### 수집/운영 (05/06 문서)
- [ ] off-peak 시간대 제한
- [ ] 부하/운영 메트릭 (쿼리수·바이트·429) Actuator/Micrometer 노출 + 알람
- [ ] Spring Batch JobRepository 실연결 (현재 `@Scheduled`만, `batch.job.enabled=false`)
- [ ] HA 단일 실행 보장 (ShedLock 또는 Quartz 클러스터)

### 품질/테스트
- [ ] 엔티티 캡슐화 (현재 스캐폴딩상 public 필드)
- [ ] 통합 테스트 (Testcontainers: 실제 PostgreSQL/JPA, REST API e2e, 조건부 GET 304)
- [ ] 매칭 엣지 케이스(04 §7) 회귀 테스트
- [ ] 3종 포맷 파싱 → Canonical 동일성 테스트 (Postman/CSV 구현 후)

---

## Done

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
> 후속(TODO 유지): 설정 저장(DB)·중앙 API(`GET/PUT /classification`)·non_api dropped 메트릭
