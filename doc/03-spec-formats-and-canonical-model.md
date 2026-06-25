# 문서 포맷과 Canonical 모델

컴포넌트 (C) Spec Loader 의 상세 설계.
지원 포맷 3종을 단일 내부 모델(**CanonicalEndpoint**)로 통합한다.

## 1. Canonical 엔드포인트 모델

모든 포맷이 변환되는 공통 표현. 매칭 엔진은 이 모델만 본다.

```jsonc
{
  "method": "GET",                 // 대문자 정규화
  "path_template": "/users/{id}",  // 정규화 규칙 적용(아래 §1.1)
  "host": "api.example.com",       // null 가능
  "deprecated": false,
  "version": "1.0.0",              // null 가능
  "source_ref": "openapi#getUserById"  // 원본 추적용
}
```

### 1.1 path_template 정규화 규칙 (포맷 공통)
세 포맷의 표기를 동일 규칙으로 맞춰야 매칭이 성립한다.

- 파라미터 표기 통일: `{id}`, `:id`, `{{id}}` → **`{id}`** (중괄호 형태).
  - 매칭 시 파라미터 **이름은 무시**하고 위치만 본다. 이름은 리포트 가독성용으로만 보존.
- 선행 `/` 보장, 후행 `/` 제거(루트 제외).
- host 는 소문자화.
- base path/server prefix 가 있으면 path 앞에 결합(§2.1).

## 2. OpenAPI (Swagger) 3.x / 2.0

YAML 또는 JSON. REST 명세의 사실상 표준.

### 2.1 추출 규칙
- 엔드포인트 = `paths` 객체의 각 path × 각 HTTP method 조합.
- `path` 키가 곧 템플릿(`/users/{id}`) — 이미 `{}` 표기라 변환 최소.
- **deprecated** = operation 의 `deprecated: true` (없으면 false).
- **host/basePath**:
  - 3.x: `servers[].url` 의 host + base path. 여러 server 면 각각에 대해 host 후보 생성(또는 host=null로 host-agnostic 매칭).
  - 2.0: `host` + `basePath` 필드.
- **version** = `info.version`.
- **source_ref** = `operationId` (없으면 `"{method} {path}"`).

### 2.2 예시 (OpenAPI 3.0, YAML)
```yaml
openapi: 3.0.1
info: { title: Example API, version: 1.0.0 }
servers:
  - url: https://api.example.com/v2
paths:
  /users/{id}:
    get:
      operationId: getUserById
      responses: { '200': { description: ok } }
  /v1/orders/{orderId}:
    get:
      operationId: getOrderV1
      deprecated: true          # ← Zombie 판정의 기준
      responses: { '200': { description: ok } }
```
변환 결과(S):
```jsonc
[
  { "method":"GET","path_template":"/v2/users/{id}","host":"api.example.com",
    "deprecated":false,"version":"1.0.0","source_ref":"openapi#getUserById" },
  { "method":"GET","path_template":"/v2/v1/orders/{orderId}","host":"api.example.com",
    "deprecated":true,"version":"1.0.0","source_ref":"openapi#getOrderV1" }
]
```
> 주의: `servers.url` 의 base path(`/v2`)가 각 path 앞에 결합된다(canonical=결합형, SoT 보존).
> 프록시가 prefix 를 떼는 환경 대응은 **at-match strip**(doc/27 §3, D38)으로 구현: 파싱/canonical 은
> 결합형 그대로 두고, 매칭 시점에 `DomainConfig.basePathStrip` prefix 를 **재부착해 추가 시도**한다
> (`EndpointMatcher.match(...,stripPrefix)` — as-is 우선, 미매칭 시 `stripPrefix+path` 재시도).
> parse-time 결합 토글(미결합 canonical)은 재파싱·SoT 손실로 미채택(doc/27 §2). 기본 null=off=현행.

## 3. Postman Collection v2.1

JSON. 협업·테스트용으로 널리 쓰이며 폴더 트리 구조.

### 3.1 추출 규칙
- `item[]` 트리를 DFS 순회. leaf 의 `request` 가 엔드포인트.
- **method** = `request.method`.
- **path** = `request.url.path[]` 배열을 `/` 로 join.
  - Postman 변수 표기 `:var` 또는 `{{var}}` → `{var}` 로 변환.
- **host** = `request.url.host[]` join (없으면 null). `{{baseUrl}}` 같은 변수 host 는
  collection `variable` 에서 치환 시도, 실패 시 host=null.
- **deprecated**: Postman 표준 필드 없음 → **규약 정의**(§3.3).
- **version** = collection `info` 또는 변수에서(없으면 null).
- **source_ref** = item 경로(`"FolderA/FolderB/Get User"`).

### 3.2 예시 (Postman v2.1, 발췌)
```jsonc
{
  "info": { "schema": "...collection/v2.1.0/..." },
  "item": [
    {
      "name": "Get User",
      "request": {
        "method": "GET",
        "url": {
          "raw": "https://api.example.com/v2/users/:id",
          "host": ["api","example","com"],
          "path": ["v2","users",":id"]
        }
      }
    },
    {
      "name": "[DEPRECATED] Get Order v1",
      "request": {
        "method": "GET",
        "url": { "raw": "https://api.example.com/v1/orders/:orderId",
                 "host":["api","example","com"], "path":["v1","orders",":orderId"] }
      }
    }
  ]
}
```

### 3.3 Postman deprecated 규약 (우리가 정의)
표준 필드가 없으므로 아래 중 하나로 deprecated 판정(우선순위 순).
1. item 또는 상위 폴더 `name` 에 `[DEPRECATED]` 또는 `(deprecated)` 토큰 포함 (대소문자 무시).
2. `request.description` 에 `deprecated: true` (front-matter 또는 키워드) 포함.
3. collection `variable` 에 엔드포인트별 표기(고급, 선택).

> 규약은 문서화하여 고객에게 안내해야 한다. 위 예시는 폴더/이름 규칙(1번)을 따름.

## 4. CSV (포맷 정의)

가장 단순한 입력. 스키마를 본 설계에서 확정한다.

### 4.1 스키마
- 인코딩 UTF-8, 구분자 `,`, **헤더 행 필수**.
- 컬럼:

| 컬럼 | 필수 | 설명 | 예 |
|---|---|---|---|
| `method` | ✅ | HTTP 메서드 | `GET` |
| `path` | ✅ | path 템플릿(`{param}` 표기) | `/users/{id}` |
| `host` | ❌ | 호스트(빈 값이면 host-agnostic) | `api.example.com` |
| `deprecated` | ❌ | `true`/`false` (빈 값=false) | `true` |
| `version` | ❌ | API 버전 | `1.0.0` |
| `description` | ❌ | 설명(추적용) | `사용자 조회` |

- `path` 의 파라미터는 `{id}` 형태 권장. `:id` 입력 시에도 변환 허용.
- `deprecated` 파싱: `true/false/1/0/y/n` 허용(대소문자 무시).

### 4.2 예시
```csv
method,path,host,deprecated,version,description
GET,/users/{id},api.example.com,false,1.0.0,사용자 조회
POST,/users,api.example.com,false,1.0.0,사용자 생성
GET,/v1/orders/{orderId},api.example.com,true,1.0.0,주문 조회(구버전)
DELETE,/sessions/{token},api.example.com,false,1.0.0,세션 종료
```
변환 결과(S): 각 행 → CanonicalEndpoint 1개. `source_ref = "csv#row{n}"`.

## 5. 포맷 자동 감지 (Spec Loader 진입)

업로드 파일의 포맷을 다음 순서로 판별.
1. 확장자 `.csv` 또는 첫 줄에 `method,path` 헤더 → **CSV**.
2. JSON/YAML 파싱 성공 후:
   - 최상위 `openapi` 또는 `swagger` 키 존재 → **OpenAPI**.
   - `info.schema` 가 Postman collection 스키마 URL 포함 → **Postman**.
3. 어느 것도 아니면 오류 반환(지원 포맷 안내).

## 6. 검증/에러 처리
- 필수 필드 결손 행/오퍼레이션은 스킵 + 경고 리스트에 기록(전체 실패 아님).
- 중복 (method, host, path_template) 은 dedupe, deprecated 는 OR 결합(하나라도 true면 검토 필요로 본다 — 정책 선택지로 둠).
- 파싱 경고는 리포트 `spec_source.warnings[]` 에 노출.

## 7. Spec Store — 파싱 1회, 캐논 영속, 스캔마다 재사용

스펙은 **스캔할 때마다 원본 문서를 읽고 파싱·정규화하지 않는다.** 비효율적이고, 매 회차 결과가
흔들릴 수 있다. 대신 **업로드 시점에 1회 파싱**해 Canonical 형태로 영속하고, 스캔은 그것을 읽어 쓴다.

### 7.1 공급 경로
```
고객 업로드 ──▶ 중앙 서버 ──▶ (PUT /domains/{host}/spec) ──▶ Worker Spec Store
```
Worker 는 중앙이 전달한 문서를 받는다. provenance(`uploadedBy=central`, 원본 파일명 등)를 함께 기록.

### 7.2 업로드 시 처리(동기)
1. 포맷 자동 감지(§5) → 파싱 → Canonical 변환 → **검증**.
2. 유효하면 **새 spec 버전** 으로 저장(아래 §7.3). 무효면 즉시 오류 반환(중앙에 동기 피드백, 07 §3.1).
3. 저장 직후 해당 도메인의 **매처 캐시 무효화**(다음 스캔이 새 버전 사용). 정책에 따라 즉시 재스캔 트리거 가능.

### 7.3 무엇을 저장하나 (도메인 × spec 버전)
| 저장물 | 용도 | 비고 |
|---|---|---|
| **원본 문서(raw)** | 감사·로직 업그레이드 시 재파싱 | blob |
| **Canonical 엔드포인트 집합** | 매칭의 진실원(S) | 쿼리 가능하게 영속 |
| 파싱 경고·메타 | 리포트/디버깅 | format, endpointCount, uploadedAt, checksum |
| (캐시) 컴파일된 매처 인덱스 | 매칭 가속(04 §1) | **Canonical 에서 재생성 가능** → 캐시 취급 |

> 진실원은 **Canonical 집합**이다. 매처 인덱스는 그로부터 재생성 가능한 파생 캐시이므로
> 영속은 선택(메모리 캐시 + 버전 키로 충분, 기동 시 lazy 빌드).

### 7.4 버전 관리
- 업로드마다 `specVersion` 증가(또는 내용 해시). 도메인별 **active 버전** 유지.
- 각 스캔은 사용한 `specVersion` 을 결과에 기록 → 스펙이 바뀌면 결과 `version(ETag)` 도 바뀐다(07 §3.3).
- 이전 버전 보관(롤백/이력) 여부는 정책. 최소 active 1개는 보존.

### 7.5 스캔 시 사용 흐름
```
스캔 시작 → Spec Store 에서 (host, activeSpecVersion) 의 Canonical 로드
         → 매처 인덱스 캐시 조회(없으면 Canonical 로 1회 빌드)
         → 매칭/분류(04 문서)   ※ 원본 문서 재파싱 없음
```
- 매처 인덱스는 `(host, specVersion)` 키 메모리 캐시. 업로드로 버전이 바뀌면 evict 후 재빌드.
