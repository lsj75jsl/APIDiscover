# TASKS — 작업 목록 (세션 메모리)

> 새 세션 시작 시 **반드시 이 파일을 먼저 읽고** todo/done 을 파악한 뒤 작업을 이어간다.
> 완료한 항목은 `[x]` 로 표기하고 **Done** 섹션으로 옮긴다.
> 의사결정은 `doc/DECISIONS.md`, 진행 로그는 `doc/PROJECT_LOG.md`, 설계 상세는 `doc/00~07`.

---

## TODO

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
