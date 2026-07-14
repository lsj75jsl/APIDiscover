# 41. nginx access log 포맷 규약 (운영팀 전달용) — 8.3 신호 활성화 선행 조건

> 대상: 운영(인프라)팀. 목적: APIDiscover 가 8.3 API 신호(응답 Content-Type·Accept·X-Requested-With·Origin·Auth)를 소비하려면
> nginx access log 를 **정해진 포맷**으로 내보내야 한다. 이 문서는 그 포맷을 정의한다.
> 관련: [doc/02](02-log-parsing-and-normalization.md)(파서 상세)·[doc/40](40-log-signal-consumption-plan.md)(8.3 소비 설계)·DECISIONS D79.
> 상태: **코드는 배포 완료·DORMANT(무회귀).** 이 포맷 표준화가 끝나야 활성화(§6) 가능.

## 1. 배경 — 왜 포맷이 중요한가

APIDiscover 파서(`LogLineParser`)는 access log 한 줄을 구분자 **`^|^`** 로 split 한 뒤 **필드의 위치(절대 인덱스, 0부터)** 로 값을 읽는다. 필드 이름표가 로그에 없으므로 **"몇 번째 필드"가 곧 의미**다.

- 예: 응답 Content-Type 은 **24번 인덱스**에 있어야 `endpoint_kind` 판정에 쓰인다. 25번에 있으면 파서는 엉뚱한 값을 읽는다.
- 따라서 8.3 신호 5종은 **아래 규약 인덱스에 정확히** 위치해야 한다. 위치가 어긋나면 활성화해도 신호가 소비되지 않거나 오작동한다.

## 2. 현재 코어 24필드 정의 (인덱스 0~23)

APIDiscover 파서가 기대하는 코어 필드다. **`✅ 소비`** 는 파서가 실제로 읽는 위치(정확한 위치 필수), **`⬚ 미소비`** 는 파서가 읽지 않지만 **자리를 차지해야 뒤 필드의 인덱스가 맞는** 위치다.

| idx | nginx 변수 | 파서 | APIDiscover 용도 |
|----:|---|:---:|---|
| 0 | `$http_client_real_ip` | ✅ | client_ip(우선) |
| 1 | `$remote_addr` | ✅ | client_ip(fallback) |
| 2 | `$remote_port` | ⬚ | (미소비·자리) |
| 3 | `$time_iso8601` | ✅ | 타임스탬프 |
| 4 | `$upstream_cache_status` | ⬚ | (미소비) |
| 5 | `$request` | ✅ | method 추출(첫 토큰) |
| 6 | `$request_completion` | ⬚ | (미소비) |
| 7 | `$response_time` | ✅ | 응답시간 |
| 8 | `$request_uri` | ✅ | 경로 + 쿼리키 |
| 9 | `$status` | ✅ | HTTP 상태 |
| 10 | `$body_bytes_sent` | ✅ | 응답 바이트 |
| 11 | `$connection` | ⬚ | (미소비) |
| 12 | `$https` | ✅ | on/off |
| 13 | `$http_referer` | ✅ | referer(kind 보조) |
| 14 | `$http_user_agent` | ✅ | UA(봇 식별) |
| 15 | `$host` | ✅ | host(우선) |
| 16 | `$real_host` | ✅ | host(fallback) |
| 17 | `$server_addr` | ⬚ | (미소비) |
| 18 | `$server_port` | ⬚ | (미소비) |
| 19 | `$type` | ✅ | **endpoint_kind($type)** |
| 20 | (geo country, 예: `$geoip_country_code`) | ⬚ | (미소비·자리) |
| 21 | (예약, 관측='0') | ⬚ | (미소비·자리) |
| 22 | (예약, 관측='0') | ⬚ | (미소비·자리) |
| 23 | `$request_id` | ✅ | **dedup 키(32 hex)** |

> `$http_client_real_ip`·`$real_host`·`$type` 은 현재도 리치 엣지가 내보내는 **커스텀 변수**다(신규 아님). idx 20~22 는 파서가 안 읽으므로 값은 자유지만 **정확히 3개**여야 `$request_id` 가 idx23 에 온다.

## 3. ★필드 수 통일 요구 (핵심 — 안 하면 활성화 불가)

**대상 엣지의 현재 log_format 이 서로 다르다(필드 수 18/19/24 혼재).** 이 상태로 8.3 을 "끝에 append" 하면 **엣지마다 8.3 필드의 절대 인덱스가 달라져** 단일 설정(`accept-field-index=26` 등)으로 소비할 수 없다.

**2026-07-14 운영 Loki 실측 근거**(3분 창 표본, 엣지 라벨 AAJ14/PAK21/PARV2/PLDI1):

| 관측 필드 수 | 특징 | 예시 엣지 |
|---:|---|---|
| **24** | 리치 포맷 — `…host@15·real_host@16·server_addr@17·server_port@18·type@19·country@20·(0)@21·(0)@22·request_id@23`. **이 문서 §2 코어와 일치** | `*.co.kr`(game/bplive 등) |
| **19** | 린 포맷 — `…host@15·server_addr@16·server_port@17·request_id@18`, **`$type` 없음·필드 5개 부족** | `www.revu.net` 등 |
| **18** | 헬스/모니터링 트래픽(Azure Traffic Manager 등) | ryugin-web.jp 등 |

→ **요구: 8.3 을 적용할 모든 엣지를 §2 의 24필드 코어로 통일**한다(린 포맷은 누락 필드 `$type`·geo·예약·`$request_id` 자리를 채워 24필드로 맞춘다). 그 **뒤에** §4 의 8.3 필드(24~30)를 붙인다.

> 통일이 어려운 엣지는 **8.3 적용 대상에서 제외**해도 된다(그 엣지는 현행 DORMANT 그대로 무영향). 단 적용하는 엣지는 반드시 24필드 코어 + 8.3 순서를 지켜야 한다. (APIDiscover 파서는 엣지별 다른 인덱스를 지원하지 않는다 — 전역 단일 인덱스.)

## 4. 8.3 append 인덱스 규약 (24~30)

코어 24필드(idx 0~23) **뒤에** 아래 7필드를 **이 순서대로** append 한다.

| idx | nginx 변수 | 파서 설정 키 | APIDiscover 소비 |
|----:|---|---|---|
| 24 | `$sent_http_content_type` | `response-content-type-field-index` | **endpoint_kind**(2xx 응답만·과반) |
| 25 | `$content_type` | (없음 — 미소비) | 요청 CT **예약**(향후 web-form). 자리만 유지 |
| 26 | `$http_accept` | `accept-field-index` | 신호 `acceptJson` |
| 27 | `$http_x_requested_with` | `x-requested-with-field-index` | 신호 `xRequestedWith` |
| 28 | `$http_access_control_request_method` | `acrm-field-index` | CORS preflight(ACRM, 기구현) |
| 29 | `$http_origin` | `origin-field-index` | 신호 `originHeader` |
| 30 | `$auth_scheme` | `auth-scheme-field-index` | 신호 `authScheme` |

> **제외(사용자 확정)**: `$server_protocol`·`$upstream_addr` 는 넣지 않는다(효과 미미).
> idx25(`$content_type`)는 지금은 소비 안 하지만 **자리를 유지**해야 26~30 인덱스가 맞는다(빼지 말 것).

## 5. nginx `log_format` 지시문 예시

`$auth_scheme` 는 신규 변수이므로 `map` 으로 먼저 정의한다(Authorization 헤더 → 스킴). `http {}` 블록에 둔다.

```nginx
# Authorization 스킴 추출 (bearer/basic/… ; 없으면 빈 값)
map $http_authorization $auth_scheme {
    default        "";
    "~*^bearer "   "bearer";
    "~*^basic "    "basic";
    "~*^digest "   "digest";
    "~*^negotiate" "negotiate";
}

# APIDiscover 규약 로그 포맷 (코어 24필드 + 8.3 append 7필드 = 31필드)
log_format apidiscover
  '$http_client_real_ip^|^$remote_addr^|^$remote_port^|^$time_iso8601^|^'
  '$upstream_cache_status^|^$request^|^$request_completion^|^$response_time^|^'
  '$request_uri^|^$status^|^$body_bytes_sent^|^$connection^|^$https^|^'
  '$http_referer^|^$http_user_agent^|^$host^|^$real_host^|^$server_addr^|^'
  '$server_port^|^$type^|^$geoip_country_code^|^-^|^-^|^$request_id^|^'
  '$sent_http_content_type^|^$content_type^|^$http_accept^|^$http_x_requested_with^|^'
  '$http_access_control_request_method^|^$http_origin^|^$auth_scheme';

access_log /var/log/nginx/access.log apidiscover;
```

- idx20~22 는 위 예시에서 `$geoip_country_code^|^-^|^-` 로 채웠다(파서 미소비 — 기존 geo/예약 변수를 써도 되고 `-` 로 채워도 된다. **개수 3개·request_id 를 idx23 에 두는 것만 지키면 된다**).
- `$http_client_real_ip`·`$real_host`·`$type` 는 이미 정의돼 있다는 전제다(현재 리치 엣지가 사용 중). 린 엣지에 없으면 기존 정의를 이식한다.
- 헤더가 없는 요청은 nginx 가 해당 위치에 `-`(또는 빈 값)를 남긴다 → 파서가 null 로 처리(무발화). 정상이다.
- 값에 `^|^` 가 들어갈 여지는 사실상 없다(헤더·경로에 리터럴 `^|^` 부재). 혹시 몰라 신호 헤더는 원본 유지로 충분하다.

## 6. 활성화 절차 (APIDiscover 쪽 — 위 log_format 배포·검증 후)

로그 포맷 표준화가 끝나 8.3 필드가 실제로 24~30 에 들어오기 시작하면, APIDiscover 에서 인덱스를 세팅한다. **이미지 재빌드 불요**(env/yml override, doc/32 §4.5).

1. **선(先) 사실 확인**: 운영 Loki 소량 샘플로 대상 엣지가 31필드이고 8.3 값이 24~30 에 실제로 오는지 재확인(이 문서 §3 방식).
2. **인덱스 설정** — 아래 둘 중 하나(운영은 보통 `adc.yaml` env).
   - `application.yml`(재빌드) `apidiscover.parse` 주석 해제.
   - 또는 `adc.yaml` app 컨테이너 env(relaxed binding) — 권장:
     ```yaml
     - name: APIDISCOVER_PARSE_RESPONSECONTENTTYPEFIELDINDEX
       value: "24"
     - name: APIDISCOVER_PARSE_ACCEPTFIELDINDEX
       value: "26"
     - name: APIDISCOVER_PARSE_XREQUESTEDWITHFIELDINDEX
       value: "27"
     - name: APIDISCOVER_PARSE_ACRMFIELDINDEX
       value: "28"
     - name: APIDISCOVER_PARSE_ORIGINFIELDINDEX
       value: "29"
     - name: APIDISCOVER_PARSE_AUTHSCHEMEFIELDINDEX
       value: "30"
     ```
     (idx25 요청 content_type 은 미소비이므로 설정 키 없음.)
3. **적용**: `podman play kube --replace /root/adc.yaml` → `/actuator/health` UP.
4. **전/후 스냅샷 diff 로 실측**(doc/40 §4.3·§8-5): CT→endpoint_kind 재분류·격하를 활성 직후 측정(CT kind-flip 은 사전 시뮬 불가). 이상 시 인덱스 -1 로 되돌리면 즉시 DORMANT 복귀(무회귀).

> **무회귀 보장**: 인덱스를 미리(로그 변경 전) 세팅해도 파서의 `f.length > idx` 가드로 현재 필드 부족 라인은 null 처리 → 신호 부재 → 현행 동작 불변. 즉 위험은 "필드가 잘못된 위치에 오는" 경우이지, 미수집 상태 자체는 안전하다.
