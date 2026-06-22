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

### D14. 세션 메모리 문서 운용
`doc/TASKS.md`(할일/완료), `doc/PROJECT_LOG.md`(작업로그), `doc/DECISIONS.md`(결정)를 세션 메모리로 운용.
새 세션은 항상 이 3개를 참고해 이어서 작업(CLAUDE.md 에 명시). 기존 checklist.md·context-notes.md 는 이 문서들로 흡수·일원화.
