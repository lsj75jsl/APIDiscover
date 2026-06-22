# 구현 스택 / 개발 환경 — Java + Spring

## 1. 결정

- **언어/런타임**: **Java 21 (LTS)**.
- **프레임워크**: **Spring Boot 3.x** (사내 표준).
- **배포 모델**: **상주 Spring Boot 서비스**(web + batch + scheduler 단일 앱).
  k8s CronJob(회차마다 Pod 기동)이 아니라 상주 서비스라 **부팅 비용은 최초 1회**, JVM warm 유지.
- **아키텍처**: MSA. 본 모듈은 **API Discovery Worker** 서비스로, 중앙 서버와 REST API 로 통신(07 문서).

## 2. 왜 이 조합인가

- Spring 이 사내 표준 → DI·설정·보안·배치·스케줄링이 한 생태계로 일관.
- 본 앱은 **세 역할을 한 서비스**로 수행.
  1. 중앙 서버에 노출하는 **REST API**(도메인 설정 수신, 결과 제공) — Spring Web.
  2. 주기적 **분석 배치** — Spring Batch + 스케줄러.
  3. Loki 수집·파싱·매칭·분류 파이프라인(02~05 문서).
- Java 21 의 record/sealed/패턴매칭/가상 스레드가 데이터 모델·분류·병렬 수집에 적합.

## 3. 스택 구성

| 영역 | 채택 | 비고 |
|---|---|---|
| 런타임 | Java 21 LTS | record, sealed interface, virtual threads |
| 코어 | Spring Boot 3.x | |
| REST API | Spring Web (MVC) | 중앙 서버 통신(07 문서) |
| 배치 | **Spring Batch** | Reader(Loki)→Processor(분석)→Writer(리포트), JobRepository 재시작/이력 |
| 스케줄링 | Spring `@Scheduled`(기본) / **Quartz**(영속·클러스터 트리거 필요 시) | 단일 실행 보장은 §6 |
| 영속 | Spring Data JPA | 도메인 설정·**Spec Store(원본+Canonical, 버전별)**·스캔 메타·결과 저장 (03 §7) |
| DB | 사내 표준 RDB(PostgreSQL 등) / 개발은 H2 | Spring Batch JobRepository 포함 |
| 보안 | Spring Security | 서비스 간 인증(mTLS 또는 OAuth2 client-credentials, 07 §5) |
| HTTP 클라이언트(Loki) | 내장 `HttpClient` 또는 Spring `RestClient` | `query_range` 호출 |
| JSON | Jackson | Postman·리포트 직렬화 |
| YAML | jackson-dataformat-yaml / SnakeYAML | OpenAPI YAML |
| **OpenAPI 파싱** | **swagger-parser v3** (`io.swagger.parser.v3`) | 2.0/3.x, `$ref`, `deprecated` |
| Postman | Jackson + 자체 매핑(03 문서) | 표준 라이브러리 없음 |
| CSV | univocity-parsers / Commons CSV | |
| 근사 distinct + 분위수 | **Apache DataSketches** (`datasketches-java`) | HLL + KLL 분위수 |
| 단일 실행 락(HA 시) | ShedLock 또는 Quartz 클러스터 | §6 |
| 관측 | Spring Actuator + Micrometer | health/metrics |
| 로깅 | SLF4J + Logback | |
| 테스트 | JUnit 5, AssertJ, Mockito, **Testcontainers** | Loki/DB 통합 테스트 |
| 빌드 | Gradle (Kotlin DSL) | bootJar |
| 품질 | Spotless + Checkstyle(또는 Error Prone) | |

## 4. 데이터 모델링 (Java 21)

- `ParsedRequest`, `CanonicalEndpoint`, `DiscoveredEndpoint`, 리포트 → **record**.
- `Finding` 계열(Shadow/Zombie/Active/Unused/WebPage) → **sealed interface** + `switch` 패턴매칭.
- 매칭 엔진 → `java.util.regex`(named group) + 버킷/trie 는 표준 컬렉션.
- 도메인별·윈도우별 수집 병렬화 → **가상 스레드**(Java 21).

## 5. 모듈/패키지 구성(제안)

```
apidiscover-worker/                  ← 단일 Spring Boot 서비스
  api/          # REST 컨트롤러 (중앙 서버용)            ← 07 문서
  central/      # (선택) 중앙 서버 콜백/푸시 클라이언트  ← 07 문서
  ingest/       # Loki Fetcher, watermark, dedup        ← 05 문서
  batch/        # Spring Batch Job/Step 정의
  parse/        # 로그 라인 파서                          ← 02 문서
  normalize/    # 정규화·인벤토리·endpoint_kind          ← 02 문서
  spec/         # 업로드 시 파싱→Canonical, Spec Store(버전 관리) ← 03 §7
  match/        # 매처 컴파일/매칭                         ← 04 문서
  classify/     # Shadow/Zombie/Active/Unused/web_page    ← 04 문서
  report/       # 리포트 생성·저장                         ← 01 문서
  domain/       # 도메인 설정·스캔 메타 엔티티/리포지토리
  config/       # 정적 설정 바인딩(@ConfigurationProperties)
tests/
```

## 6. 배포·운영

- **배포**: bootJar 를 Docker 이미지로, k8s **Deployment** 로 상주.
- **스케줄러 단일 실행 보장**: 분석기는 **replica 1** 로 충분. HA 가 필요하면
  **ShedLock**(DB/Redis 락) 또는 **Quartz 클러스터 모드**로 리더 한 곳만 트리거(이중 실행 방지).
- **설정 외부화**(요구사항 6): 정적 설정은 ConfigMap/Secret(또는 Spring Cloud Config),
  동적 설정(도메인)은 중앙 API → DB. 구분은 07 §4.
- **관측**: Actuator `/actuator/health`(readiness/liveness), Micrometer 메트릭(수집 라인 수·소요·분류 요약).

## 7. 비범위
- 실시간 스트리밍·인라인 차단 없음(배치).
- 분산 처리 프레임워크 불필요. 도메인 병렬은 가상 스레드/배치 파티셔닝으로 충분.
