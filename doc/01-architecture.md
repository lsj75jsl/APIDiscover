# 아키텍처

## 1. 전체 파이프라인

> MSA: 본 파이프라인은 **API Discovery Worker** 서비스 내부 흐름이다. 중앙 서버 연동(도메인 설정 수신·결과 제공)은 07 문서.

```
   ┌───────────┐ 도메인설정/결과조회 ┌──────────────────────────┐
   │ 중앙 서버 │◀───── REST API ────▶│ (G) Worker REST API      │  ← 07 문서
   └───────────┘                     └────────────┬─────────────┘
                                                   │ 도메인 설정(DB)
   ┌───────────┐  주기 실행   ┌──────────────────────────┐       │
   │ Scheduler │─────────────▶│ (S0) Loki Fetcher        │  ← 05 │
   │ @Scheduled│              │  도메인별 watermark·덤프 │◀──────┘
   └───────────┘              └────────────┬─────────────┘
                                           │ raw log lines (^|^, jsonl)
                                           ▼
                  ┌──────────────────────────────────────────────┐
   사내 Loki ─────▶│  (A) Log Ingestor & Parser                   │
   (LogQL)        │      필드 분리 → method/path/status/host 추출 │
                  └───────────────┬──────────────────────────────┘
                                  │ ParsedRequest[]
                                  ▼
                  ┌──────────────────────────────────────────────┐
                  │  (B) Normalizer & Inventory Builder           │
                  │      경로 정규화 → 엔드포인트 시그니처 집계   │
                  └───────────────┬──────────────────────────────┘
                                  │ Discovered 집합 D (메트릭 포함)
                                  │
  [업로드 시점·스캔과 분리]        │
   중앙 PUT /spec ─┐               │
   (OpenAPI/        ▼              │
    Postman/   ┌────────────────────────┐ │
    CSV)       │ (C) Spec Loader        │ │
               │  파싱·검증 → Canonical │ │
               │  → Spec Store 영속     │ │
               └───────────┬────────────┘ │
                   ┌────────▼─────────┐    │
                   │   Spec Store     │    │  도메인×specVersion
                   │ (Canonical 영속) │    │  스캔 시 로드(재파싱X)
                   └────────┬─────────┘    │
                   │ Spec 집합 S(버전)│    │
                   ▼               ▼
              ┌──────────────────────────────────────┐
              │  (D) Matching Engine                  │
              │     템플릿 컴파일 → D×S 매칭          │
              └───────────────┬───────────────────────┘
                              │ 매칭 결과
                              ▼
              ┌──────────────────────────────────────┐
              │  (E) Classifier                       │
              │     Shadow / Zombie / Active / Unused │
              │     + 신뢰도 점수                     │
              └───────────────┬───────────────────────┘
                              │
                              ▼
              ┌──────────────────────────────────────┐
              │  (F) Reporter → JSON 리포트 / 대시보드 │
              └──────────────────────────────────────┘
```

## 2. 컴포넌트 책임

| ID | 컴포넌트 | 책임 | 상세 문서 |
|---|---|---|---|
| G | **Worker REST API** | 중앙 서버에 도메인 설정 수신·스캔 상태·결과(조건부 GET) 제공 | 07 |
| S0 | **Loki Fetcher (Scheduler)** | 주기 실행, 도메인별 LogQL range query, watermark 증분 수집·dedup, raw 덤프 | 05 |
| A | **Log Ingestor & Parser** | `^|^` 구분 라인 파싱, `$request`/`$request_uri`에서 method·path 추출, 잘못된 라인 폐기 | 02 |
| B | **Normalizer & Inventory Builder** | 경로를 템플릿으로 정규화, 시그니처별 트래픽 메트릭 집계 → D | 02 |
| C | **Spec Loader + Spec Store** | **업로드 시점에** 포맷 감지·파싱·검증 → Canonical 을 도메인×버전으로 **영속**. 스캔은 저장소에서 로드(재파싱 없음) | 03 §7 |
| D | **Matching Engine** | 문서 템플릿을 매처로 컴파일, D의 각 시그니처를 S에 매칭 | 04 |
| E | **Classifier** | 매칭 결과로 4종 분류 + 신뢰도/노이즈 필터 | 04 |
| F | **Reporter** | 분류 결과를 JSON 리포트로 직렬화 | 본 문서 §4 |

## 3. 내부 데이터 모델

### 3.1 ParsedRequest (A 출력)
로그 한 줄을 파싱한 결과.
```jsonc
{
  "method": "GET",
  "raw_path": "/users/12345",        // query string 제거된 경로
  "query_keys": ["expand"],          // 쿼리 파라미터 '키'만 (값 제외)
  "status": 200,
  "host": "api.example.com",
  "client_ip": "203.0.113.5",
  "user_agent": "okhttp/4.9",
  "ts": "2026-06-22T09:00:00+09:00",
  "resp_time_ms": 35,
  "body_bytes": 812,
  "https": true
}
```

### 3.2 DiscoveredEndpoint (B 출력 — 집합 D의 원소)
정규화된 시그니처 + 누적 메트릭.
```jsonc
{
  "signature": "GET api.example.com /users/{id}",
  "method": "GET",
  "host": "api.example.com",
  "path_template": "/users/{id}",
  "template_source": "spec|inferred",   // 스펙 매칭으로 얻었는지, 휴리스틱 추론인지
  "endpoint_kind": "api_candidate",     // web_page|static|api_candidate|unknown (02 §5)
  "kind_confidence": 0.0,               // endpoint_kind 신뢰도 0~1 (dormant 시 0)
  "metrics": {
    "hits": 14820,
    "first_seen": "2026-06-01T00:03:11+09:00",
    "last_seen": "2026-06-22T08:59:50+09:00",
    "status_dist": { "2xx": 14010, "3xx": 100, "4xx": 600, "5xx": 110 },
    "distinct_clients": 320,
    "p50_resp_ms": 30, "p95_resp_ms": 120
  }
}
```

### 3.3 CanonicalEndpoint (C 출력 — 집합 S의 원소)
3종 포맷이 공통으로 변환되는 내부 표현. 상세는 03 문서.
```jsonc
{
  "method": "GET",
  "path_template": "/users/{id}",
  "host": "api.example.com",          // null 가능(호스트 미지정 스펙)
  "deprecated": false,
  "version": "1.0.0",                 // null 가능
  "source_ref": "openapi#getUserById" // 추적용 원본 참조
}
```

## 4. 출력 리포트 스키마 (F)

```jsonc
{
  "generated_at": "2026-06-22T09:10:00+09:00",
  "log_window": { "from": "2026-06-01", "to": "2026-06-22" },
  "spec_source": { "format": "openapi", "version": "3.0.1", "endpoint_count": 142 },
  "summary": {
    "discovered": 168, "active": 120, "shadow": 26, "zombie": 8, "unused": 22
  },
  "findings": [
    {
      "classification": "shadow",
      "method": "POST",
      "host": "api.example.com",
      "path_template": "/internal/debug/{id}",
      "confidence": 0.92,            // 04 문서의 신뢰도 산식
      "evidence": {
        "hits": 240, "status_dist": { "2xx": 230, "4xx": 10 },
        "first_seen": "...", "last_seen": "...", "distinct_clients": 3
      },
      "reason": "트래픽 존재, 문서 내 매칭 템플릿 없음"
    },
    {
      "classification": "zombie",
      "method": "GET",
      "host": "api.example.com",
      "path_template": "/v1/orders/{orderId}",
      "confidence": 1.0,
      "spec_ref": "openapi#getOrderV1 (deprecated:true)",
      "evidence": { "hits": 540, "last_seen": "2026-06-22T08:40:00+09:00" },
      "reason": "문서에 deprecated 표기, 그러나 최근까지 트래픽 발생"
    }
  ]
}
```

## 5. 기술 스택

- 소스는 사내 Loki, 실행은 주기적 배치(스트리밍·인라인 불필요). 상세는 05 문서.
- 언어/환경 추천(Python 3.12+ 등)은 06 문서.
- 핵심 자료구조는 정규식·해시맵·trie. 대용량 로그는 라인 스트리밍 + 시그니처 해시맵 집계로 메모리 상수화.
