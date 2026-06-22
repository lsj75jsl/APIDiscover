# API Discovery 시스템 설계 — 개요

> nginx(reverse proxy) 액세스 로그를 입력으로 **API 엔드포인트 인벤토리**를 구축하고,
> 고객이 업로드한 **API 문서(OpenAPI / Postman / CSV)** 를 기준으로
> **Shadow API / Zombie API** 를 탐지하는 시스템의 설계 문서.

## 1. 배경

WAAP 제품군의 API Discovery 기능 중, 주어진 nginx 로그 필드로 **구현 가능한 범위**는
"엔드포인트 인벤토리 + 트래픽 가시성 + Shadow/Zombie 후보 식별" 까지다.
(요청/응답 body·헤더가 로그에 없으므로 스키마 추론·인증 방식·응답 PII 탐지는 범위 밖.
근거는 직전 분석 참고.)

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

## 4. 범위

### In scope
- nginx 로그 파싱 및 엔드포인트 정규화
- OpenAPI 3.x/2.0, Postman Collection v2.1, CSV 문서 파싱
- 경로 템플릿 매칭 엔진과 Shadow/Zombie 분류
- 결과 리포트(JSON) 스키마

### Out of scope (로그 한계로 불가)
- 요청/응답 스키마·파라미터 타입 추론 (body 없음)
- 인증 방식 식별 (Authorization/Cookie 헤더 미로깅)
- 응답 본문 민감정보(PII) 노출 탐지 (body 없음)
- 실시간 인라인 차단 (본 설계는 오프라인/배치 분석 기준)

## 5. 전제 / 결정사항

- **대표 문서 포맷 2종 = OpenAPI(Swagger) + Postman Collection.** REST API 명세로 가장 널리 쓰이며 path 템플릿·method·deprecated 정보를 구조적으로 담는다.
- **세 번째 = CSV.** 스키마는 본 설계에서 정의한다(03 문서 참조).
- 로그 구분자는 `^|^`, 필드 순서는 사용자가 제시한 20개 필드 고정.
- 로그 소스는 **사내 Loki** 서버. 특정 도메인의 access log 를 주기적으로 내려받아 분석한다(05 문서).
- 본 시스템은 **배치 분석** 을 기본 모드로 한다(주기적 수집 → 분석 → 리포트). 실시간 불필요.
- **MSA**: 본 모듈은 **API Discovery Worker** 서비스로, 중앙 서버와 REST API 로 연동한다(07 문서).
- 구현 스택은 **Java 21 + Spring Boot/Spring Batch**(06 문서).

## 6. 문서 구성

| 문서 | 내용 |
|---|---|
| `00-overview.md` | 본 문서 — 목표·정의·범위 |
| `01-architecture.md` | 파이프라인·컴포넌트·데이터 모델·출력 스키마 |
| `02-log-parsing-and-normalization.md` | 로그 파싱과 경로 정규화 설계 |
| `03-spec-formats-and-canonical-model.md` | 3종 포맷 파싱 + 캐논 엔드포인트 모델 |
| `04-matching-and-classification.md` | 매칭 엔진 + Shadow/Zombie 분류 로직 |
| `05-log-ingestion-from-loki.md` | Loki 주기적 배치 수집(소스 계층) |
| `06-implementation-stack.md` | 구현 스택(Java + Spring)·개발 환경 |
| `07-msa-and-central-integration.md` | MSA 구조·중앙 서버 연동 API·설정 분리 |
| `08-api-scoring-and-profiles.md` | API 후보 점수화 모델·high/middle/low/custom 프로파일·중앙 튜닝 API |
| `TASKS.md` | 작업 목록(TODO/Done) — 세션 메모리 |
| `PROJECT_LOG.md` | 작업 내역 로그 — 세션 메모리 |
| `DECISIONS.md` | 의사결정 기록 — 세션 메모리 |
