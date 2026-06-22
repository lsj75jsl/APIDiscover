# 로그 수집 — Loki 주기적 배치

컴포넌트 (A) 앞단의 **로그 소스/수집 계층** 설계.
nginx access log 는 사내 **Loki** 서버에 적재되어 있고, API Discovery 는
**실시간이 아니라 주기적 배치**로 특정 도메인의 로그를 내려받아 수행한다.

## 1. 동작 모델

```
 ┌──────────────┐   주기 실행(cron)   ┌────────────────────────────┐
 │  Scheduler   │────────────────────▶│ (S0) Loki Fetcher          │
 │ (cron/k8s)   │                     │  - 도메인별 watermark 관리 │
 └──────────────┘                     │  - LogQL range query       │
                                       │  - 페이지네이션/덤프        │
                                       └──────────────┬─────────────┘
                                                      │ raw log lines (jsonl)
                                                      ▼
                                       ┌────────────────────────────┐
                                       │ (A) Log Parser → 이하 기존  │
                                       │     파이프라인 (01 문서)    │
                                       └────────────────────────────┘
```

- **비실시간**: 인라인 차단이 목적이 아니라 인벤토리/리포트 생성이므로 배치로 충분.
- **도메인 단위**: 분석 대상 도메인(`$host`)별로 독립 실행/리포트.
- **주기**: 예) 1시간/1일 단위. 도메인 트래픽량과 리포트 신선도 요구에 맞게 설정.

## 2. Loki 조회 (LogQL)

> 실제 접속 정보·라벨·쿼리 형태는 `sample/loki_sample.py`(운영 export 도구)를 기준으로 한다.
> 단 그 샘플의 **수집 방식(거대 limit·무 페이지네이션·전 hostname 동시 발사)은 일회성 export 용**이며,
> 주기 분석기에서는 §2.4 부하 보호 규칙을 따른다(운영 서버 영향 방지).

### 2.1 실제 라벨 체계 (샘플 기준)
- 엔드포인트: `http://192.168.8.100:3200/loki/api/v1/query_range`
- 라벨: `job="access_log"`, `hostname="<엣지/프록시 서버명>"` (예: `AAI11`, `PAI11`, `PAI21`).
  - **`hostname` 은 도메인이 아니라 로그를 생성한 엣지 서버 인스턴스**다. 한 도메인이 여러 엣지 서버에 걸칠 수 있다.
- 도메인은 **로그 라인 안에** 있고, **라인 필터 `|=`**(정확 부분문자열)로 좁힌다.

### 2.2 LogQL 구성
```logql
{job="access_log", hostname="PAI11"} |= `api.example.com`
```
- **라벨로 1차 축소**(job + hostname)는 인덱스 기반이라 싸다. **반드시 활용**해 스캔량을 줄인다.
- `|=` 도메인 라인필터는 **볼륨 축소용**. `^|^` 20필드 **정밀 파싱·도메인 확정은 (A) Log Parser**(02 문서)가
  단일 진실원으로 수행(서버측 정밀 파싱은 fragile).
- 도메인을 서빙하는 hostname 목록은 도메인 설정에 둔다(§2.3, 07 문서). 모르면 hostname 라벨 없이
  job 만으로 조회 가능하나 **스캔량이 커지므로 비권장**.

### 2.3 도메인 ↔ hostname(엣지 서버) 매핑
- 도메인 설정에 `hostnames: [PAI11, PAI21, ...]`(해당 도메인을 처리하는 엣지 서버 라벨값)를 둔다.
- 여러 hostname 은 **순차 또는 제한된 동시성**으로 조회(§2.4). 샘플처럼 전부 동시 발사하지 않는다.

### 2.4 시간창 range query — 부하 보호형 (운영 서버 핵심)
- HTTP API: `GET /loki/api/v1/query_range` (`query`, `start`, `end` ns, `limit`, `direction=forward`).
- 샘플과 달리 **반드시 다음을 지킨다**(상세 §5의 부하 보호 설정과 연동, 6절 운영).
  - **윈도우 분할**: 큰 범위를 `chunk`(예: 5~15분) 단위로 쪼개 순차 조회. 하루 한 방 금지.
  - **유한 batch limit + 페이지네이션**: `limit` 은 1k~5k 같은 **유한값**. `limit=1e8` 금지.
    응답 수 == limit 이면 다음 `start` = 마지막 ts + 1ns 로 이어 조회(forward).
  - **동시성 상한**: Loki 로 가는 동시 쿼리 수를 작은 세마포어로 제한(예: 1~2). 도메인×hostname 폭주 방지.
  - **쿼리 간 간격/스로틀**: chunk·페이지 사이 짧은 지연으로 순간 부하 평탄화.
  - **429/5xx 백오프**: Loki 한도 초과(429)·일시 오류는 지수 백오프 재시도(6절).
- (선택) bulk 백필은 `logcli`(`--batch`, `--forward`) 사용 가능하나, **동일한 윈도우 분할·동시성 상한**을 적용.

## 3. 증분 수집(watermark) 과 정합성

### 3.1 watermark
- 도메인별 마지막 성공 수집 시각을 상태로 저장(`state[domain].last_end`).
- 다음 실행 윈도우 = `[last_end, now - ingest_lag)`.
- 성공적으로 분석/적재 완료 후에만 watermark 전진(at-least-once).

### 3.2 Loki 적재 지연 보정 (ingest_lag)
- 아주 최근 로그는 아직 Loki 에 다 안 들어왔을 수 있다.
- 윈도우 끝을 `now` 가 아니라 `now - ingest_lag`(예: 5~10분)로 잡아 **late-arriving 로그 누락** 방지.

### 3.3 경계/중복 처리
- 윈도우는 `[start, end)` 반열림으로 잡아 인접 윈도우 간 중복을 원천 차단.
- 그래도 재시도/겹침이 생길 수 있으므로 **dedup 키** 정의:
  **`request_id`(필드24, 32 hex)** — 실데이터에서 전건 고유 확인. 이것이 1순위 키.
  (없는 로그면 fallback `hash(connection + time_iso8601 + request)`)
- dedup 은 (A)→(B) 사이에서 적용. 집계(hits) 중복계수 방지.

### 3.4 retention
- Loki 보존기간을 넘어선 과거는 조회 불가. 최초 backfill 범위는 보존기간 내로 한정.

## 4. 저장과 흐름
- 다운로드 결과는 **jsonl 원본**으로 스크래치 저장 후 (A) Parser 로 투입(재현/디버깅 용이).
  - 또는 메모리 스트리밍 직결(소규모 도메인). 기본은 파일 경유.
- 상태(watermark)·설정은 경량 저장(JSON/SQLite). 분산/HA 불필요(단일 배치).

## 5. 설정 — 정적/동적 분리

설정은 두 갈래로 나뉜다(상세는 07 문서 §4).
- **정적(인프라)**: Loki 접속·전역 인터벌·ingest_lag·backfill → config 파일/ConfigMap.
- **동적(운영)**: 대상 도메인·도메인별 스펙·인터벌 override → **중앙 서버 API → DB**(07 §3.1).
  즉 도메인 목록은 config 파일에 두지 않는다(런타임에 중앙이 주입).

정적 설정 예(`application.yml`, 접속값은 `sample/loki_sample.py` 기준):
```yaml
apidiscover:
  loki:
    addr: "http://192.168.8.100:3200"     # 샘플 기준
    job-label: "access_log"               # 샘플 기준
    auth: { type: "none" }                # 운영 정책에 맞게(none/basic/bearer/mTLS)
    query-timeout: "30s"
    # --- 운영 서버 부하 보호 (§2.4 / 6절) ---
    chunk-window: "PT10M"        # range query 1회당 시간창 (작게)
    page-limit: 2000             # query_range limit (유한값, 1e8 금지)
    max-concurrent-queries: 2    # Loki 동시 쿼리 상한(도메인·hostname 합산)
    min-query-interval: "200ms"  # 쿼리 간 최소 간격(스로틀)
    retry: { max-attempts: 5, backoff: "exponential", base: "1s", on: [429, 502, 503, 504] }
  schedule:
    default-interval: "PT1H"     # 도메인별 override 없으면 적용
    ingest-lag: "PT10M"
    initial-backfill: "P7D"
    off-peak-window: "01:00-06:00"  # (선택) backfill·대용량은 저부하 시간대로 제한
```
도메인별 `hostnames`(엣지 서버 라벨, §2.3)·watermark·raw 덤프·결과는 DB/스토리지에 영속(07 §8).

## 6. 운영 서버 부하 보호 (필수)

대상 Loki 는 **운영 중**이라 과도한 조회가 서비스 로깅 파이프라인에 영향을 줄 수 있다.
`sample/loki_sample.py` 의 `limit=1e8`·무 페이지네이션·전 hostname 동시 발사 방식은 **그대로 쓰지 않는다.**

| 위험 | 보호 장치 |
|---|---|
| 거대 단일 응답(OOM/타임아웃) | **유한 `page-limit`(1k~5k) + 페이지네이션**(§2.4) |
| 넓은 윈도우가 querier/ingester 압박 | **`chunk-window` 분할**(5~15분) 순차 조회. `max_query_length` 이내 유지 |
| 도메인×hostname 폭주 | **`max-concurrent-queries` 세마포어**(예 1~2) |
| 순간 부하 스파이크 | **`min-query-interval` 스로틀** |
| Loki 한도 초과(429) | **지수 백오프 재시도**, 반복 429 시 해당 회차 중단·다음 주기로 미룸 |
| 재처리로 인한 과수집 | **watermark 증분**(3절)으로 매 회차 신규 구간만 조회 |
| 백필이 한 번에 큼 | `initial-backfill` 을 잘게 나눠 여러 회차로, **off-peak 시간대** 제한(선택) |

추가 운영 원칙.
- Loki 서버 한도(`max_entries_limit_per_query`, `max_query_length`, `max_query_parallelism`)를 **존중**하도록 설정값을 그 이하로.
- 부분 성공 시 watermark 미전진(다음 회차 재수집, dedup 흡수). 도메인별 독립 실행으로 장애 격리.
- 메트릭: 쿼리 수·반환 라인 수·바이트·소요·429 횟수를 노출해 부하를 모니터링하고 임계 초과 시 알람.

## 7. 범위 메모
- 실시간 스트리밍·인라인 차단은 범위 밖(00 문서와 일치).
- Loki 가 유일 소스. 파일 직접 투입(오프라인 분석)은 (A) Parser 가 동일 인터페이스로 수용 가능(테스트/백필 편의).
