# PROJECT LOG — 작업 내역 (세션 메모리)

> 새 세션 시작 시 참고. 최신 항목이 위로 오도록 역순 기록.
> 형식: `## YYYY-MM-DD 세션 N — 제목` + 한 일/결과/다음 단계.

---

## 2026-06-22 세션 2 — 참고 설계 평가 → 린(lean) 채택 결정

### 한 일
- 타 프로젝트(body·헤더 있는 환경) API Discovery 설계 문서를 검토.
- **무조건 채택 대신 비판적 평가**(장단점) 수행 → **린 채택**으로 결정.
  - 채택: 프로파일(high/middle/low/custom 임계), 중앙 API 전역·도메인 튜닝, 가용 신호만의 린 점수 모델.
  - 미채택(불가): Content-Type/Accept/AJAX/body(로그 부재) → `$type`+UA 클래스로 부분 대체.
  - 보류: endpoint decision cache, 참고의 정확한 가중치 값(보정 전 임의값).
  - 핵심 판단: 참고 설계의 판정력은 우리가 없는 강신호에서 나옴 → 점수화는 정보량 증가 없이 복잡도만 ↑.
- `doc/08` 을 "평가 → 린 채택 + 보류 + 보정 선행" 구조로 (재)작성. api_confidence vs shadow confidence 역할 분리 명시.
- 연결: doc/00 인덱스, doc/04 ApiScorer 게이트 전제, doc/07 튜닝 API. `DECISIONS.md` D15, `TASKS.md` 디스코프.

### 결과
- 코드 변경 없음(설계+계획). 가중치는 **잠정값**, **실데이터 보정이 선행 작업**.

### 다음 단계
- 적용 전 **가중치 보정**(샘플 라벨링) 선행. 이후 `ApiScorer`(린 점수 게이트)+프로파일+중앙 설정 API 순.

## 2026-06-22 세션 1 — 설계부터 수집·분석 파이프라인 구현까지

### 한 일
1. **개념·설계**: WAAP API Discovery 개념 정리, 주어진 nginx 로그로 가능/불가 항목 구분.
   문서 업로드 기반 Shadow/Zombie 탐지로 범위 확정. 설계 문서 `doc/00~07` 작성.
2. **스택 결정**: Java 21 + Spring Boot 3.3 (사내 표준), 상주 서비스 + Spring Batch + `@Scheduled`.
   MSA Worker 서비스로 중앙 서버와 REST 연동(Pull + 조건부 GET).
3. **스캐폴딩**: Gradle 프로젝트, 패키지 구조(api/ingest/parse/normalize/spec/match/classify/report/domain/batch/config).
4. **컴포넌트 구현(테스트 동반)**: OpenApiSpecParser → SpecStore → EndpointMatcher → Classifier →
   InventoryBuilder → ReportBuilder → DiscoveryJobService → LokiClient → watermark/dedup.
5. **실데이터 검증**: `sample/loki_sample.py` 기반으로 실 Loki(192.168.8.100:3200) 조회.
   로그가 **24필드**(문서 20 + geo/`0`/`request_id` 32hex)임을 확인, `$type` 필드(document/library) 발견.
6. **실 e2e 검증**: 우리 Java 파이프라인을 실 Loki에 직접 호출. `.js/.css`가 `$type=document`로 찍혀
   WEB_PAGE 오분류되는 버그 발견 → **확장자 판정을 `$type`보다 우선**하도록 수정.

### 결과
- 58 tests green (라이브 1건은 `-Dloki.live=true` 가드). 풀 빌드(bootJar) 성공.
- 수집(증분·dedup·부하보호) → 파싱 → 정규화/인벤토리 → 매칭 → 분류 → 리포트 → 영속 전 구간 동작.
- REST API 4종(도메인 CRUD, 스펙 업로드, 스캔상태/결과 조건부GET, hostname→domains/온디맨드 query).

### 환경 메모
- 이 분석 환경엔 JDK/Gradle 미설치였음 → JDK 21은 dnf로 설치, Gradle 8.10.2는 스크래치패드에 받아 사용.
  빌드: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build`.
- 실 Loki 라이브 테스트: `./gradlew test --tests "*LokiLiveIntegrationTest" -Dloki.live=true`.

### 다음 단계
- `doc/TASKS.md` 의 TODO 참고. 우선순위 후보: Postman/CSV 파서 실구현, 서비스 간 인증,
  운영 메트릭/off-peak, low_confidence/warnings 리포트 노출.
