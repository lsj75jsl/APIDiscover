# CLAUDE.md — APIDiscover 프로젝트 지침

WAAP API Discovery Worker. nginx access log 를 사내 Loki 에서 주기 수집해 엔드포인트 인벤토리와
Shadow/Zombie API 를 탐지하고, 중앙 서버와 REST 로 연동한다. (Java 21 + Spring Boot 3.3)

## 세션 시작 시 필수 절차 (반드시 준수)

새 세션을 시작하거나 이전 세션 작업을 이어갈 때는 **작업 전 아래 세션 메모리 문서를 항상 먼저 읽는다.**

1. **`doc/TASKS.md`** — TODO/Done 목록. 무엇을 해야 하고 무엇이 끝났는지의 단일 기준.
2. **`doc/PROJECT_LOG.md`** — 지난 세션들의 작업 내역.
3. **`doc/DECISIONS.md`** — 그동안 내린 의사결정과 근거. (재논의 전 반드시 확인)

보조 참고: `doc/00~07`(설계 상세).

## 작업 중 갱신 의무

- 작업을 **완료하면 `doc/TASKS.md`** 의 해당 항목을 `[x]` 로 바꾸고 **Done 섹션으로 옮긴다.** 새 할 일이 생기면 TODO 에 추가한다.
- **의미 있는 작업 단위가 끝날 때마다 `doc/PROJECT_LOG.md`** 에 로그를 남긴다(역순, 날짜/한 일/결과/다음 단계).
- **새 의사결정을 하면 `doc/DECISIONS.md`** 에 근거와 함께 기록한다. 기존 결정을 바꾸면 항목을 갱신하고 사유를 남긴다.

이 세 문서는 세션 간 메모리 역할을 하므로 최신 상태 유지가 곧 인수인계 품질이다.

## 빌드/테스트

- JDK 21 필요. 빌드: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build`
- 실 Loki end-to-end: `./gradlew test --tests "*LokiLiveIntegrationTest" -Dloki.live=true` (운영 서버 호출이므로 창을 짧게 유지)
- **코드를 만졌으면 완료 선언 전 반드시 테스트를 돌린다.**

## 운영 주의

- 대상 Loki(192.168.8.100:3200)는 **운영 서버**다. 조회 시 LokiClient 의 부하 보호(윈도우 분할·페이지네이션·동시성·스로틀·백오프)를 우회하지 말 것. 임시 스크립트도 창/limit 을 작게.
- 전역 지침(`~/.claude/CLAUDE.md`)도 함께 따른다. 한국어 출력 규칙·간결성·테스트 선행 등.
