# MSA 구조와 중앙 서버 연동

API Discovery 를 MSA 의 **작업(Worker) 서비스**로 두고, **중앙 서버(Control Plane)** 와
REST API 로 연동하는 설계. 요구사항(도메인 원격 설정, 결과 제공, 마지막 검사 시점 확인, 설정 분리)을 반영한다.

## 1. 역할 분리

```
        ┌─────────────────────────────┐
        │      중앙 서버 (Control)     │
        │  - 도메인 설정 관리          │
        │  - 다수 Worker 오케스트레이션 │
        │  - 결과 수집/조회·대시보드    │
        └───────────┬─────────────────┘
        (1) 도메인 설정 │  ▲ (3) status 확인 → (4) 결과 조회(조건부)
            POST/PUT    │  │
                        ▼  │
        ┌─────────────────────────────┐        ┌──────────┐
        │  API Discovery Worker        │───────▶│  Loki    │
        │  (Spring Boot 상주 서비스)    │ (2) 수집│ (사내)   │
        │  - REST API (중앙 노출)       │        └──────────┘
        │  - Scheduler + Spring Batch  │
        │  - 분석 파이프라인(02~05)     │
        │  - 도메인/스캔메타/결과 DB    │
        └─────────────────────────────┘
```

- **중앙 서버**: 어떤 도메인을 검사할지 결정하고(설정 주입), 결과를 모아 본다. 다수 Worker 관리.
- **Worker(본 모듈)**: 설정된 도메인의 Loki 로그를 주기적으로 분석하고, 결과를 **API 로 제공**한다.

## 2. 통신 모델 — 중앙 주도 Pull + 조건부 GET

요구사항 "결과 전송 전 마지막 검사 시점 확인 → 동일 결과 중복 호출 최소화" 를 만족하는 핵심 설계.

1. 중앙이 Worker 에 **도메인 설정을 주입**(push, 중앙→Worker API 호출).
2. Worker 가 **주기적으로 자체 분석**(scheduler+batch). 결과·메타를 DB 에 저장.
3. 중앙이 **가벼운 `scan-status`** 를 조회 → 도메인의 `lastScanAt`·`version(etag)` 확인.
4. 중앙이 가진 버전과 다를 때만 **`result` 를 조회**. 같으면 호출 생략, 또는 조건부 GET 으로 `304`.

> 즉 무거운 결과 전송은 **변경됐을 때만** 일어난다. 메타(작은 응답)로 먼저 거른다.
> (선택) 즉시성 필요 시 Worker→중앙 **완료 웹훅**으로 보강 가능(§6).

## 3. Worker REST API (중앙 서버에 노출)

base: `/api/v1`. 인증은 §5. 응답은 JSON.

### 3.1 도메인 설정 (요구사항: 원격 세팅)
| Method | Path | 설명 |
|---|---|---|
| `GET` | `/domains` | 등록 도메인 목록 + 각 스캔 상태 요약 |
| `POST` | `/domains` | 도메인 등록 `{ host, specRef?, intervalOverride?, enabled }` |
| `GET` | `/domains/{host}` | 단건 설정 + 상태 |
| `PUT` | `/domains/{host}` | 설정 수정(스펙·인터벌·enabled) |
| `DELETE` | `/domains/{host}` | 등록 해제 |
| `PUT` | `/domains/{host}/spec` | 해당 도메인 API 문서 업로드(OpenAPI/Postman/CSV) → **업로드 시 파싱·저장** |
| `GET` | `/domains/{host}/spec` | 현재 스펙 메타(format, specVersion, endpointCount, uploadedAt, warnings) |
| `GET` | `/domains/{host}/spec/raw` | (선택) 저장된 원본 문서 다운로드 |

#### 분류 설정 튜닝 API (doc/08 — 미탐/과탐 조정)
운영자가 중앙에서 **전역·도메인별 프로파일과 가중치**를 조정해 API Discovery 정확도를 튜닝한다.
| Method | Path | 설명 |
|---|---|---|
| `GET` | `/classification` | 시스템 전역 분류 설정 조회 |
| `PUT` | `/classification` | 전역 분류 설정(profile/min_api_confidence/weights/path matchers) |
| `GET` | `/domains/{host}/classification` | 도메인 override + effective(병합 결과) 조회 |
| `PUT` | `/domains/{host}/classification` | 도메인 override 설정 |

> 설정 항목·병합 규칙·프로파일(high/middle/low/custom)은 doc/08 §4~§5. `custom` 일 때만
> `min_api_confidence`·`weights.*` 가 도메인에서 override 된다.

도메인 설정 모델(예):
```jsonc
{
  "host": "api.example.com",
  "enabled": true,
  "hostnames": ["PAI11", "PAI21"], // 이 도메인을 서빙하는 엣지 서버(Loki hostname 라벨, 05 §2.3)
  "intervalOverride": "PT1H",      // null 이면 전역 기본 인터벌 사용(config)
  "spec": { "format": "openapi", "specVersion": 3, "endpointCount": 142,
            "uploadedAt": "..." },  // Spec Store 의 현재 활성 스펙 메타(03 §7)
  "createdAt": "...", "updatedAt": "..."
}
```

#### 스펙 업로드 동작 (고객→중앙→Worker, 03 §7)
- 흐름: **고객 업로드 → 중앙 서버 → `PUT /domains/{host}/spec`** 로 Worker 에 전달.
- 요청: 문서 본문(+ 포맷 힌트 또는 자동감지). 멀티파트 또는 raw body.
- Worker 는 **업로드 시점에 파싱·정규화·검증**하고 Canonical 을 **내부 저장소에 영속**(스캔마다 재파싱 안 함).
- 응답(성공): `200/201` `{ "format":"openapi", "specVersion":3, "endpointCount":142, "warnings":[...] }`.
- 응답(실패): `400` + 파싱/검증 오류 목록 → **중앙에 동기 피드백**(잘못된 문서를 스캔까지 끌고 가지 않음).
- 부수효과: 매처 캐시 무효화 + `specVersion` 증가. 다음 스캔이 새 버전 사용(즉시 재스캔은 정책).

### 3.2 스캔 상태 — 가벼운 메타 (요구사항: 마지막 검사 시점 확인)
| Method | Path | 설명 |
|---|---|---|
| `GET` | `/domains/{host}/scan-status` | 마지막 검사 시점·버전·요약(작은 응답) |

```jsonc
{
  "host": "api.example.com",
  "state": "idle",                 // idle | running | failed
  "lastScanAt": "2026-06-22T09:00:00+09:00",
  "version": "a1b2c3d4",           // 결과 ETag(스캔 단위 버전)
  "logWindow": { "from": "...", "to": "..." },
  "summary": { "discovered": 168, "active": 120, "shadow": 26, "zombie": 8, "unused": 22 }
}
```
> 중앙은 이 응답의 `version`/`lastScanAt` 만 비교해 결과 재조회 여부를 결정한다.

### 3.3 결과 — 조건부 GET (요구사항: 결과 제공 + 중복 최소화)
| Method | Path | 설명 |
|---|---|---|
| `GET` | `/domains/{host}/result` | 최신 결과 리포트(01 §4 스키마). **조건부 GET 지원** |
| `GET` | `/domains/{host}/scans` | (선택) 스캔 이력 목록 |
| `GET` | `/domains/{host}/scans/{scanId}` | (선택) 특정 스캔 결과 |

- 응답 헤더 `ETag: "a1b2c3d4"`, `Last-Modified: <lastScanAt>`.
- 요청 `If-None-Match: "a1b2c3d4"` (또는 `If-Modified-Since`) 가 현재 버전과 같으면
  **`304 Not Modified`**(본문 없음) → 동일 결과 재전송 방지.
- 변경됐으면 `200` + 전체 리포트.

### 3.4 온디맨드/운영 (선택)
| Method | Path | 설명 |
|---|---|---|
| `POST` | `/domains/{host}/scan` | 즉시 재검사 트리거 → `202 Accepted` |
| `GET` | `/actuator/health` | readiness/liveness (Actuator) |

## 4. 설정 분리 (요구사항 6)

| 구분 | 항목 | 위치/방식 |
|---|---|---|
| **정적(인프라)** | Loki addr/auth/job 라벨/타임아웃, **부하 보호값(chunk-window·page-limit·동시성·스로틀·백오프)**, 전역 인터벌, ingest_lag, backfill, 중앙 서버 URL/인증 | `application.yml` + **ConfigMap/Secret**(또는 Spring Cloud Config). `@ConfigurationProperties` 바인딩 |
| **동적(운영)** | 대상 도메인, 도메인별 **hostnames(엣지 서버)**, 스펙, 인터벌 override, enabled, **분류 설정(profile/weights/path matchers, doc/08)** | 중앙 API(§3.1) → **DB 저장**. 런타임 변경 |

`application.yml` (정적) 예:
```yaml
apidiscover:
  loki:                            # 접속값은 sample/loki_sample.py 기준 (05 §5)
    addr: "http://192.168.8.100:3200"
    job-label: "access_log"
    auth: { type: "none" }
    query-timeout: "30s"
    chunk-window: "PT10M"          # 운영 부하 보호 (05 §2.4/§6)
    page-limit: 2000
    max-concurrent-queries: 2
    min-query-interval: "200ms"
  schedule:
    default-interval: "PT1H"      # 도메인별 override 없으면 적용
    ingest-lag: "PT10M"
    initial-backfill: "P7D"
  central:
    base-url: "https://central.internal"
    auth: { type: "oauth2-client-credentials" }   # 또는 mTLS
```
> 도메인·hostnames 는 여기 두지 않는다(동적, DB). 시크릿은 Secret/시크릿 매니저.

## 5. 서비스 간 인증/보안

- **권장**: mTLS(상호 TLS) 또는 **OAuth2 client-credentials**(사내 인가 서버 사용 시). Spring Security 로 구성.
- 중앙→Worker, Worker→중앙(웹훅 시) 양방향 모두 인증.
- 도메인 설정 변경 API 는 쓰기 권한 스코프 분리. 감사 로그 기록.

## 6. (선택) 완료 웹훅 — 즉시성 보강

Pull 만으로 충분하나, 결과 신선도 즉시 반영이 필요하면 Worker→중앙 push 추가.
- 스캔 완료 시 Worker 가 중앙에 `POST {central}/workers/{id}/scan-events`
  `{ host, version, lastScanAt, summary }`(요약만) 통지 → 중앙이 필요 시 `result` 조회(여전히 조건부 GET).
- 본문(전체 리포트)은 푸시하지 않는다. **신선도 신호만 push, 데이터는 조건부 pull** 원칙 유지.

## 7. 시퀀스 — 중앙의 결과 동기화

```
중앙                         Worker
 │  GET /domains/{h}/scan-status ──────────▶│
 │ ◀── { lastScanAt, version:"v2" } ────────│
 │  (보유 버전 "v1" ≠ "v2" → 변경됨)        │
 │  GET /domains/{h}/result                 │
 │      If-None-Match: "v1"  ──────────────▶│
 │ ◀── 200 + 리포트, ETag:"v2" ─────────────│
 │  (다음 주기: 변경 없으면)                │
 │  GET .../result If-None-Match:"v2" ─────▶│
 │ ◀── 304 Not Modified ────────────────────│   ← 동일 결과 재전송 없음
```

## 8. 멱등/정합성 메모

- 도메인 등록/수정은 `host` 기준 멱등(PUT). 중복 POST 는 409 또는 upsert 정책 명시.
- `version(ETag)` = 스캔 결과의 안정적 해시(또는 scanId). 같은 입력 재분석이어도 결과 동일하면 version 유지 권장.
- Worker 재시작에도 도메인 설정·최신 결과·watermark 는 DB 영속(05 문서와 연동).
