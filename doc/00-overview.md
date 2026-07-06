# API Discovery 시스템 설계 — 개요

> nginx(reverse proxy) 액세스 로그를 입력으로 **API 엔드포인트 인벤토리**를 구축하고,
> 고객이 업로드한 **API 문서(OpenAPI / Postman / CSV)** 를 기준으로
> **Shadow API / Zombie API** 를 탐지하는 시스템의 설계 문서.

## 1. 배경

WAAP 제품군의 API Discovery 기능 중, 주어진 nginx 로그 필드로 **구현 가능한 범위**는
"엔드포인트 인벤토리 + 트래픽 가시성 + Shadow/Zombie 후보 식별" 까지다.
(요청/응답 body·헤더가 로그에 없으므로 스키마 추론·인증 방식·응답 PII 탐지는 범위 밖이다.)

본 설계는 그 가능 범위 중에서도 **문서 기반 Shadow/Zombie 탐지**에 초점을 둔다.

## 2. 목표 (Goals)

1. nginx 액세스 로그를 파싱해 **관찰된 엔드포인트 집합(D, Discovered)** 을 구축한다.
2. 고객이 업로드한 API 문서를 파싱해 **문서화된 엔드포인트 집합(S, Spec)** 을 구축한다.
3. D 와 S 를 **경로 템플릿 단위로 매칭**하여 다음을 분류한다.
   - **Shadow API** — 트래픽엔 존재하나 문서엔 없는 엔드포인트
   - **Zombie API** — 문서에 deprecated 로 표기됐으나 여전히 트래픽이 발생하는 엔드포인트
   - **Active(정상)** — 문서에 있고 트래픽도 정상
   - **Unused(문서 전용)** — 문서엔 있으나 트래픽이 전혀 없는 엔드포인트
4. 결과를 구조화된 리포트(JSON)로 출력해 대시보드/정책 적용의 입력이 되게 한다.

## 3. 핵심 정의

| 용어 | 정의 | 집합 표현 |
|---|---|---|
| **D (Discovered)** | 로그에서 관찰된 (method + 정규화된 path) 집합 | 관찰 집합 |
| **S (Spec)** | 업로드 문서에서 추출한 (method + path 템플릿) 집합 | 문서 집합 |
| **Shadow API** | 트래픽엔 있으나 문서에 매칭되는 엔드포인트가 없음 | `D \ S` |
| **Zombie API** | 문서에서 deprecated 로 표기됐는데 트래픽이 계속 발생 | `S_deprecated ∩ D` |
| **Active** | 문서에 있고(미deprecated) 트래픽 발생 | `S_active ∩ D` |
| **Unused** | 문서에 있으나 트래픽 없음 | `S \ D` |

> Shadow 는 **관찰 측(D)** 에서, Zombie 는 **문서 측(S)** 에서 출발한다. 방향이 반대임에 유의.

> **구현 위치** — 위 분류는 `model/Classification.java`(enum)·`model/Finding.java`(하위 레코드 `Shadow`/`Zombie`/`Active`/`Unused`/`WebPage`)로 표현되고, 관찰 집합 D 와 문서 집합 S 를 대조해 `classify/Classifier.java` 의 `classifyWithMetrics()` 가 산출한다(상세 [04-matching-and-classification](04-matching-and-classification.md)). 위 4종 외에, 트래픽은 있으나 API 가 아닌 웹 페이지로 판정된 관측을 `UNDOCUMENTED_WEB_PAGE`(`Finding.WebPage`) 로 따로 분리한다.

## 4. 범위

### 포함 범위 (in scope)
- nginx 로그 파싱 및 엔드포인트 정규화
- OpenAPI 3.x/2.0, Postman Collection v2.1, CSV 문서 파싱
- 경로 템플릿 매칭 엔진과 Shadow/Zombie 분류
- 결과 리포트(JSON) 스키마

### 제외 범위 (out of scope, 로그 한계로 불가)
- 요청/응답 스키마·파라미터 타입 추론 (body 없음)
- 인증 방식 식별 (Authorization/Cookie 헤더 미로깅)
- 응답 본문 민감정보(PII) 노출 탐지 (body 없음)
- 실시간 인라인 차단 (본 설계는 오프라인/배치 분석 기준)

## 5. 전제 / 결정사항

- **대표 문서 포맷 2종 = OpenAPI(Swagger) + Postman Collection.** REST API 명세로 가장 널리 쓰이며 path 템플릿·method·deprecated 정보를 구조적으로 담는다. OpenAPI 는 3.x 와 Swagger 2.0 을 모두 받는다(2.0 은 업로드 시 3.0 으로 자동 변환, [DECISIONS](DECISIONS.md) D70).
- **세 번째 = CSV.** 스키마는 본 설계에서 정의한다([03-spec-formats-and-canonical-model](03-spec-formats-and-canonical-model.md) 참조).
- 로그 구분자는 `^|^`, 필드 순서는 사용자가 제시한 20개 필드 고정([02-log-parsing-and-normalization](02-log-parsing-and-normalization.md)).
- 로그 소스는 **사내 Loki** 서버. 특정 도메인의 access log 를 주기적으로 내려받아 분석한다([05-log-ingestion-from-loki](05-log-ingestion-from-loki.md)).
- 본 시스템은 **배치 분석** 을 기본 모드로 한다(주기 수집 → 분석 → 리포트). 실시간 처리는 하지 않는다.
- **MSA**: 본 모듈은 **API Discovery Worker** 서비스로, 중앙 서버와 REST API 로 연동한다([07-msa-and-central-integration](07-msa-and-central-integration.md)).
- 구현 스택은 **Java 21 + Spring Boot** 다. 주기 실행은 Spring Batch 잡이 아니라 인프로세스 `@Scheduled` 스케줄러(`batch/DiscoveryScheduler`·`batch/DomainDiscoveryScheduler`)로 돌린다([06-implementation-stack](06-implementation-stack.md)).

## 6. 문서 구성 (성격별 그룹)

설계 문서는 데이터 흐름(수집 → 파싱 → 대조 → 출력) 순서에 맞춰 그룹으로 묶었다.
각 문서는 해당 기능의 **근거·상세 설계**이고, 구현 상태의 단일 기준은 [TASKS.md](TASKS.md) 다.

### A. 개요·아키텍처·스택
| 문서 | 내용 |
|---|---|
| [00-overview](00-overview.md) | 본 문서 — 목표·정의·범위·전체 문서 지도 |
| [01-architecture](01-architecture.md) | 파이프라인·컴포넌트·데이터 모델·출력 스키마 |
| [06-implementation-stack](06-implementation-stack.md) | 구현 스택(Java 21 + Spring Boot)·개발 환경 |

### B. 로그 수집·파싱·정규화 (관찰 집합 D 구축)
| 문서 | 내용 |
|---|---|
| [05-log-ingestion-from-loki](05-log-ingestion-from-loki.md) | Loki 에서 access log 를 주기 배치로 수집 |
| [02-log-parsing-and-normalization](02-log-parsing-and-normalization.md) | 로그 라인 파싱과 경로 템플릿 정규화 |
| [13-normalization-cardinality](13-normalization-cardinality.md) | 정규화 고카디널리티 방지(통계 승격·param 후보·민감키) |
| [27-base-path-strip](27-base-path-strip.md) | base-path 제거로 거짓 Shadow 방지 |
| [19-existence-filter](19-existence-filter.md) | 404-only 실재성 필터(존재하지 않는 경로 제외) |
| [20-endpoint-kind-referer](20-endpoint-kind-referer.md) | endpoint_kind 판정에 Referer 보조 신호 |
| [21-type-taxonomy-sampling](21-type-taxonomy-sampling.md) | 파라미터 값 $type 분류 샘플링 |
| [22-hll-tdigest-approximation](22-hll-tdigest-approximation.md) | distinct·분위수 대용량 근사(HLL/KLL) |
| [23-options-preflight-detection](23-options-preflight-detection.md) | CORS preflight 와 실제 OPTIONS 구분 |

### C. 문서(스펙) 파싱·Canonical 모델·인벤토리 (문서 집합 S 구축)
| 문서 | 내용 |
|---|---|
| [03-spec-formats-and-canonical-model](03-spec-formats-and-canonical-model.md) | 3종 포맷 파싱 + Canonical 엔드포인트 모델 |
| [14-spec-parsers](14-spec-parsers.md) | Postman/CSV 파서 실구현 |
| [26-multi-spec-merge](26-multi-spec-merge.md) | 검출/업로드 데이터모델 통합 + 멀티 스펙 병합 |
| [36-spec-multidoc-status](36-spec-multidoc-status.md) | 멀티문서 관리 + API 상태추적(ADDED/DELETED/UPDATED) |
| [37-spec-inventory-reconcile](37-spec-inventory-reconcile.md) | 영속 API 인벤토리 + 업로드 reconcile(M7 재설계) |
| [38-spec-inventory-p2](38-spec-inventory-p2.md) | M7 재설계 P2(매칭 source·백필·param diff·merged 뷰) |

### D. 매칭·분류·점수화 (D ↔ S 대조)
| 문서 | 내용 |
|---|---|
| [04-matching-and-classification](04-matching-and-classification.md) | 매칭 엔진 + Shadow/Zombie/Active/Unused 분류 |
| [08-api-scoring-and-profiles](08-api-scoring-and-profiles.md) | API 후보 점수화 + high/middle/low/custom 프로파일 |
| [09-explicit-hint-matcher](09-explicit-hint-matcher.md) | explicit-hint 매처 + 매처 설정 |
| [15-matcher-cache](15-matcher-cache.md) | 매처 캐시 무효화 |
| [16-version-zombie-severity](16-version-zombie-severity.md) | 버전 기반 Zombie 추정 + severity |
| [24-cross-scan-recency-zombie-severity](24-cross-scan-recency-zombie-severity.md) | cross-scan recency 로 Zombie severity 보강 |
| [17-response-type-api](17-response-type-api.md) | response_type_api 양성 가중치 |
| [12-non-api-dropped-metric](12-non-api-dropped-metric.md) | 비-API 제외(dropped) 관측 메트릭 |

### E. 설정·REST API·리포트 출력 (중앙 서버 연동)
| 문서 | 내용 |
|---|---|
| [07-msa-and-central-integration](07-msa-and-central-integration.md) | MSA 구조·중앙 서버 연동 API·설정 분리 |
| [10-classification-config-store](10-classification-config-store.md) | 분류 설정 DB 저장 + effective 병합 |
| [11-classification-rest-api](11-classification-rest-api.md) | 분류 설정 중앙 REST API + 캐시 활성화 |
| [25-report-output-enhancements](25-report-output-enhancements.md) | 리포트/출력 보강 |
| [34-api-rationale-exposure](34-api-rationale-exposure.md) | API 판단 근거(점수 산출 내역) 응답 노출 |
| [35-rest-api-batch](35-rest-api-batch.md) | REST API 대규모 변경(삭제·수정·신규) 종합 |

### F. 도메인 자동 발견·스캔 운영·DB·배포·테스트
| 문서 | 내용 |
|---|---|
| [30-domain-auto-discovery](30-domain-auto-discovery.md) | 도메인 자동 디스커버리(@Scheduled + Loki 집계) |
| [33-scan-load-policy](33-scan-load-policy.md) | 스캔 Loki 부하 운영정책 + 온디맨드 스캔 CLI |
| [18-db-schema](18-db-schema.md) | 내부 DB 테이블 스키마(엔티티 역설계) |
| [29-entity-encapsulation](29-entity-encapsulation.md) | 엔티티 캡슐화(public 필드 → 접근자) |
| [28-testcontainers-pg-integration](28-testcontainers-pg-integration.md) | Testcontainers(PostgreSQL) 통합 테스트 |
| [31-cli-export-and-deploy](31-cli-export-and-deploy.md) | CLI CSV 내보내기 + 컨테이너 패키징 |
| [32-container-deploy-runbook](32-container-deploy-runbook.md) | 컨테이너 배포·실행·CLI 런북 |

### 세션 메모리 (설계 문서 아님)
| 문서 | 내용 |
|---|---|
| [TASKS.md](TASKS.md) | 작업 목록(TODO/Done) — **구현 상태의 단일 기준** |
| [PROJECT_LOG.md](PROJECT_LOG.md) | 세션별 작업 내역 로그(역순) |
| [DECISIONS.md](DECISIONS.md) | 의사결정 기록(D1~) 과 근거 |

> 운영자·개발자용 HTML 매뉴얼은 `doc/manual/` 에 따로 있다(스캔 정책·REST API·DB 스키마·배포 가이드).

## 7. 구현 진입점 (소스 맵)

개념(§3)과 파이프라인(§4)이 실제 어디서 도는지의 빠른 이정표다. 상세는 각 그룹 문서를 참조한다.
아래 경로는 모두 `src/main/java/com/pentasecurity/apidiscover/` 기준이다.

| 단계 | 핵심 소스 | 진입 함수 |
|---|---|---|
| 로그 수집(Loki) | `ingest/LokiClient.java` | `queryRange()` |
| 로그 파싱 | `parse/LogLineParser.java` | `parse()` |
| 경로 정규화·인벤토리 | `normalize/PathNormalizer.java`, `normalize/InventoryBuilder.java` | `inferTemplate()`, `buildWithLimits()` |
| 스펙 업로드·파싱 | `spec/SpecFormatDetector.java`, `spec/SpecStore.java`, `spec/{OpenApi,Postman,Csv}SpecParser.java` | `detect()`, `upload()`, `parse()` |
| 매칭 | `match/EndpointMatcher.java` | `match()` |
| 분류(Shadow/Zombie/Active/Unused) | `classify/Classifier.java` | `classifyWithMetrics()` |
| 리포트(JSON) | `report/ReportBuilder.java` | `build()` |
| 스캔 오케스트레이션 | `batch/DiscoveryJobService.java`, `batch/DiscoveryScheduler.java` | `runScan()`, `runScanBatched()` |
