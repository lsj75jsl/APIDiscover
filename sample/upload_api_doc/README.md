# API 문서 업로드 샘플 (분석 가능 유형별 1개)

APIDiscover 가 파싱·대조할 수 있는 스펙 문서 형식 3종의 최소 샘플이다. 업로드 시 형식은
`SpecFormatDetector` 가 **내용으로 자동 감지**하므로 확장자·Content-Type 은 무관하다.

| 파일 | 형식 | 감지 규칙 (SpecFormatDetector) |
|---|---|---|
| `openapi-sample.yaml` | OpenAPI 3.x · Swagger 2.0 (YAML/JSON 공용) | 최상위에 `openapi:`(3.x) 또는 `swagger:`(2.0) 키 |
| `postman-sample.json` | Postman Collection v2.1 | `info.schema` 에 `getpostman.com`/`schema.postman.com`, 또는 `item`+`info` 존재 |
| `spec-sample.csv` | CSV | 헤더 행에 `method`,`path` 컬럼 |

## 업로드 방법

```bash
# {host} = 대상 도메인. 본문에 파일을 그대로 실어 PUT.
# ★Content-Type 헤더 필수 — @RequestBody byte[] 는 octet-stream/*(비-form) 만 읽는다.
#   생략하면 curl 이 application/x-www-form-urlencoded 로 보내 "request body missing"(400) 이 난다.
curl -X PUT "http://<worker>:8080/api/v1/domains/{host}/spec?filename=openapi-sample.yaml" \
     -H "Content-Type: application/octet-stream" \
     --data-binary @openapi-sample.yaml

# 업로드 후 활성 스펙 확인
curl "http://<worker>:8080/api/v1/domains/{host}/spec"
# 문서 API 인벤토리(reconcile 결과)
curl "http://<worker>:8080/api/v1/domains/{host}/spec/apis"
# 발견 트래픽과 대조된 분류 즉시 확인(동기 스캔)
curl -X POST "http://<worker>:8080/api/v1/domains/{host}/scan-now?window=PT30M"
```

> 형식은 내용으로 자동 감지되므로 Content-Type 값 자체는 무관하다(`application/octet-stream`·`application/x-yaml` 등 무엇이든 OK). 단 **form 계열이 아니어야** 본문이 byte[] 로 바인딩된다.

## 분류로 이어지는 방식

업로드는 파싱·영속만 한다. **분류(Active/Zombie/Unused/Shadow)는 스캔 때 활성 스펙과 access log
관측을 대조**해 계산된다.

- 스펙 O + 관측 O + 미deprecated → **Active**
- 스펙 O + 관측 O + `deprecated` → **Zombie**
- 스펙 O + 관측 X → **Unused**
- 스펙 X + 관측 O(+ API 게이트 통과) → **Shadow**

각 샘플은 마지막 항목(`/api/v1/legacy/...`)을 **deprecated** 로 두어, 그 경로에 트래픽이 있으면
Zombie 로 잡히도록 구성했다.

## 형식별 표기 규칙 요약

- **OpenAPI**: `servers[].url` 로 host·basePath 도출(없으면 host-agnostic·모든 host 매칭). operation `deprecated: true`, `parameters[].in`(query/path/header/cookie). **Swagger 2.0**(`swagger: "2.0"`, `host`+`basePath`)도 지원 — 업로드 시 3.0 으로 자동 변환(D70). Swagger Editor/SwaggerHub 의 2.0·3.x export 를 그대로 올려도 된다(스키마·$ref·security 등 부가 내용은 무시하고 method/path/host/deprecated/params 만 추출).
- **Postman**: 폴더/요청 트리 DFS. host 는 `url.host`(+collection `variable` 치환), 요청 이름에 `[deprecated]`/`(deprecated)` 또는 description 에 `deprecated` 면 폐기 표기. `url.query`=query·`url.variable`=path·body(urlencoded/formdata/raw)=body 파라미터.
- **CSV**: 헤더 `method,path`(필수) + `host,deprecated,version,params,description`(선택). `params` 는 `name:in:required:type` 를 `;` 로 구분(예 `id:path:true:integer;q:query:false:string`).
