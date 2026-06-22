# APIDiscover Worker

WAAP API Discovery 의 **Worker 서비스**. nginx access log 를 사내 Loki 에서 주기적으로
내려받아 분석하고, 엔드포인트 인벤토리 및 **Shadow/Zombie API** 를 탐지한다.
중앙 서버와 REST API 로 연동한다(도메인 설정 수신, 결과 제공).

## 설계 문서

전체 설계는 [`doc/`](doc/) 참조.

| 문서 | 내용 |
|---|---|
| `doc/00-overview.md` | 목표·정의·범위 |
| `doc/01-architecture.md` | 파이프라인·컴포넌트·데이터 모델 |
| `doc/02-...` | 로그 파싱·정규화 |
| `doc/03-...` | 스펙 포맷·Canonical·Spec Store |
| `doc/04-...` | 매칭·분류(Shadow/Zombie) |
| `doc/05-...` | Loki 주기 수집(부하 보호) |
| `doc/06-...` | 구현 스택(Java + Spring) |
| `doc/07-...` | MSA·중앙 서버 연동 |

## 기술 스택

- Java 21, Spring Boot 3.3 (Web / Batch / Data JPA / Security / Actuator)
- swagger-parser(OpenAPI), univocity(CSV), Apache DataSketches(HLL/KLL)
- 상주 서비스 + `@Scheduled` 배치 (k8s CronJob 아님)

## 빌드 / 실행

Gradle wrapper(`gradlew`)가 포함돼 있다. JDK 21 필요.

```bash
./gradlew build         # 빌드 + 테스트
./gradlew bootRun       # 실행 (기본 H2, 8080)
```

> 검증됨: JDK 21 + Gradle 8.10.2 에서 `build` 성공, 테스트 14개 통과.

## 현재 상태

**스캐폴딩 + 일부 구현 단계.** 패키지 구조·설정·REST API·엔티티·핵심 인터페이스 골격이 있다.
실제 구현 완료: `parse.LogLineParser`, `normalize.PathNormalizer`, `report.EtagUtil`,
`spec.OpenApiSpecParser`(OpenAPI 2.0/3.x). 나머지 비즈니스 로직은 `TODO`(스텁).
구현 작업 목록은 [`doc/TASKS.md`](doc/TASKS.md).
