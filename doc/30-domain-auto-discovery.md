# 도메인 자동 디스커버리 — 인프로세스 @Scheduled + Loki 서버측 집계 (P3, A)

> 브랜치 `feature/domain-discovery-deploy`. P3 'Loki 도메인 목록 추출'(TASKS) 구체화. 근거: doc/02 §1.1(필드 레이아웃)·doc/05 §2.4/§6(Loki 부하보호)·doc/06(배포모델)·doc/26(DomainConfig). 근거 결정 **DECISIONS D42**.
> **설계만. 코드는 dev.** dev 항목은 TASKS subitem(D26). 사용자 확정(재논의 금지): coalesce full fidelity(host 비었/`-`→real_host), 부트스트랩 1h 1회 후 롤링.

## 0. 목적 / 범위

수집 중 access log 에서 **API 도메인(Host)을 자동 열거**해 `DomainConfig` 를 부트스트랩/증분 갱신한다(수동 등록 부담 제거). 원시 로그를 받지 않고 **Loki 서버측 메트릭 집계**로 (도메인 × 엣지 hostname) 카운트만 수신한다. 인프로세스 `@Scheduled`(별도 스케줄러), 운영 Loki 부하보호 준수.

## 1. LogQL 확정형 (서버측 집계, 라인 미수신) — ★클라이언트 coalesce(성능 정정)

도메인은 Loki 라벨이 아니라 **로그 라인 내용**(필드 15 host / 16 real_host, DELIM `^|^`)이다. `hostname` 은 스트림 라벨(엣지 서버). → 라인을 서버에서 파싱·집계하되, **coalesce 는 클라이언트(Java)에서**:

```logql
sum by (host, real_host, hostname) (
  count_over_time(
    {job="access_log"}
      | pattern "<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<_>^|^<host>^|^<real_host>^|^<_>"
    [W]
  )
)
```
- **pattern**: `<_>^|^` ×15(인덱스 0–14 skip) → `<host>`(15) `^|^` `<real_host>`(16) → `^|^<_>`(나머지 흡수). `|` 는 따옴표 안 리터럴(파이프 아님). 필드 미달 라인은 미매칭→제외(LogLineParser FIELD_COUNT 동형).
- **★서버 `label_format` coalesce 제거(성능 — 실 Loki 측정)**: `count_over_time(...|pattern [5m])` = **2.2s** 인데 `| label_format domain="{{coalesce}}"` 를 붙이면 **20.2s(10배)** — Go 템플릿이 라인마다 평가돼 과중 → 1h 부트스트랩·12m 롤링 모두 query-timeout(30s) 초과 → 디스커버리 실패. 따라서 서버에선 `host`·`real_host`·`hostname` 으로만 `group by` 하고, **coalesce(host 빈/`-`→real_host)·도메인 필터·FQDN 검증은 클라이언트**(`DomainDiscoveryService` 루프)에서 수행. 운영 Loki CPU 부하도 감소.
- **클라이언트 coalesce(충실도 동일)**: `domain = firstNonEmpty(normalizeDomain(host), normalizeDomain(real_host))` — `normalizeDomain` 이 trim·소문자·빈/`-`→null. = **LogLineParser line 83 `firstNonEmpty(nullIfDash(host), nullIfDash(real_host))` 와 동일**(host(15)+real_host(16) 폴백, 사용자 확정 full fidelity). null(둘 다 부재)·FQDN 미일치는 제외(rejected).
- **`by (host, real_host, hostname)`**: 같은 도메인이 여러 (host,real_host)·여러 hostname 으로 나올 수 있어, 클라이언트가 **coalesce 후 domain 키로 합산**(hostnames 합집합·count 합) → `DomainConfig.hostnames` 자동 채움. 응답 = 집계 벡터(라인 미전송).
- **부하 인식(정직)**: `count_over_time(...|pattern...)` 은 W 내 전 라인을 **서버에서 파싱** → Loki CPU 비용 실재(단 label_format 제거로 ~10배 경감). W 작게(롤링), 부트스트랩 1h 는 1회·off-peak 권장, throttle/concurrency 준수(§3·§4).

## 2. LokiClient 확장 — instant 메트릭 쿼리

현재 `LokiClient` 은 `queryRange`(스트림)만. **instant 벡터 쿼리 메서드 신설**(`/loki/api/v1/query?query=&time=<ns>`), `data.result[]` = `{metric:{domain,hostname}, value:[ts, "count"]}` 파싱 → `List<(labels, double)>`.
- **부하보호 재사용(우회 금지)**: 기존 `throttle()`·`concurrencyGate`(max-concurrent=2)·지수 백오프(429/5xx, MAX_ATTEMPTS=4)·`queryTimeout` 그대로. → 권장: `requestWithRetry` 를 **URL 인자형으로 소폭 추출**해 query_range·instant 양쪽이 공유(queryRange 동작 불변). 신규 의존성 0(JDK HttpClient).
- instant 라 윈도우 분할/페이지네이션 불요(집계 1행), `[W]`는 쿼리 문자열 내 range.

## 3. 스케줄러 — 별도 @Scheduled, 스캔과 공존

- **신규 `DomainDiscoveryScheduler`**(@Component) — 기존 `DiscoveryScheduler.scanEnabledDomains`(per-domain 스캔)와 **분리**. `@Scheduled(fixedDelayString="${apidiscover.discovery.interval}", initialDelayString="${apidiscover.discovery.initial-delay}")`.
- **주기**: 분단위 설정(@ConfigurationProperties, 기본 10분, §7). **동적 주기는 fixedDelayString 프로퍼티로 충분(권장)** — 런타임 무중단 변경은 불요(설정 변경=재기동 허용, 테스트 환경). `SchedulingConfigurer`/`Trigger`(런타임 동적)는 YAGNI→후속(§10).
- **공존/저우선순위**: `initialDelay` offset 으로 스캔 스케줄과 **stagger**(동시 Loki 타격 회피). 공유 Loki 예산에서 디스커버리는 보조 — concurrencyGate 를 스캔과 공유하므로 자연히 양보(동시 2 상한). 1회 1 instant 쿼리(+폴백 시 소수)라 부하 경량.

## 4. 윈도우 — 롤링 + 부트스트랩 1h

- **롤링 W ≈ interval + 오버랩**(경계 트래픽 누락 방지, 예 interval 10m → W 12m). 디스커버리는 "최근 활성 도메인" 파악이라 watermark 증분 불요(매 실행 최근 W 재집계, 누적은 DB 업서트가 담당).
- **부트스트랩**: 첫 실행(빈 도메인 DB 또는 미부트 플래그) **1h 1회**(사용자 확정) → 초기 도메인 일괄 확보. 이후 롤링. (instant 쿼리의 `[1h]` range; Loki 서버 부담 크면 off-peak.)
- **필드 레이아웃 공유 상수화**: DELIM `^|^`·F_HOST=15·F_REAL_HOST=16 이 LogLineParser(Java) 와 §1 LogQL **양쪽에 중복** → doc/02 §1.1 단일 근거 명시 + **교차검증 테스트**(LogQL pattern 의 host/real_host 포지션 == LogLineParser 상수)로 드리프트 차단(§8).

## 5. 업서트 — ★삭제 절대 없음, 사용자 설정 보존

- **신규 도메인 INSERT**: `host` + 발견 `hostnames` + 기본값(`enabled=true`·`specMergeStrategy=MERGE`·`basePathStrip=null`) + `discoveredAt`·`lastSeenAt`.
- **기존 도메인 UPDATE**: 발견 `hostnames` **합집합 머지**(기존 ∪ 신규, 중복 제거) + `lastSeenAt` 갱신만. **사용자 설정 필드(`basePathStrip`·`specMergeStrategy`·`enabled`·`intervalOverride`) 절대 덮어쓰기 금지**.
- **삭제 금지(불변식)**: 어떤 경우에도(트래픽 소멸·미관측) 도메인/설정 자동 삭제 안 함. 삭제는 **사용자 수동만**. `lastSeenAt` 은 staleness 가시화용(정보성), 삭제 트리거 아님.
- **DomainConfig 가산 필드**(ddl-auto, nullable): `discoveredAt`(최초 자동발견 시각)·`lastSeenAt`(최근 집계 관측 = **집계 윈도우 끝 데이터 ts**, now() 지양 — 프로젝트 원칙). 수동 등록 도메인은 null(자연 구분). DomainConfig 직렬화 경로 없음→ETag 무관(doc/29 §3).
- **P3-1(동시성·구현, 정정)**: `DomainConfig` 에 `@DynamicUpdate`(dirty 컬럼만 UPDATE) + **per-domain upsert 를 managed 엔티티 단일 트랜잭션**으로 수행해야 설정 lost-update 가 막힌다.
  - ★`@DynamicUpdate` 단독으론 불충분: 비-tx 면 `findById`·`save` 가 별 tx → entity detached → `save=em.merge` 가 detached 의 stale 전 필드를 fresh managed 에 복사 → 설정 컬럼이 dirty 로 잡혀 동시 PUT 값을 덮어씀.
  - 정정: 별도 빈 `DomainUpserter` 의 `@Transactional upsert()`(self-invocation 프록시 우회 회피) — `findById→mutate→커밋`이 한 tx → entity 내내 managed → 커밋 dirty-check 가 **load 스냅샷** 기준 → 안 건드린 설정 컬럼=비-dirty → `@DynamicUpdate` 가 UPDATE 에서 제외. 업데이트는 명시 save 없이 dirty-check flush, 관리 컬렉션은 in-place 갱신.
  - `discover()` 는 비-@Transactional 유지(Loki 네트워크 호출 tx 밖). `@Version` **미도입**(D18 §5 last-writer-wins 와 일관, 컬럼 범위 축소일 뿐).
  - 한계(정직): 단일스레드론 managed-tx 와 detached-merge 가 동일 동작이라 동시변경 배제를 결정적으로 보이는 테스트는 불가 → 구조(별도 @Transactional 빈)+주석+정상경로 통합테스트(`PostgresIntegrationTest` 실 PG upsert 보존)로 의도 고정.
- 멱등: 같은 도메인 재발견 = hostnames 합집합(불변 수렴)·lastSeenAt 갱신. 머지는 순서무관.

## 6. 가드 — 자동등록 폭증 차단

- **호스트 형식 검증(신규)**: LogLineParser 는 `-`/빈값만 제외(`nullIfDash`)하고 **도메인 형식 미검증** → 디스커버리에 **FQDN 정규식**(예 `^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$`, 소문자화·점 1개 이상·scheme/port/path 불가) 추가 적용 → 변조·랜덤 Host 헤더 자동등록 차단. 정규식 설정 가능(§7).
- **개수 상한**: `max-domains-per-run`(설정) — **0=무제한(전수 등록, 기본·사용자 지시)**, `>0` 이면 카운트 desc 정렬 후 상위 N 캡(초과분 drop+로그=가시화, 폭증 1회 영향 격리). 무제한 시 정렬·드롭 생략(dropped=0). ★이 캡은 **DB 업서트량만 제한**(벡터는 이미 수신)이라 Loki 부하와 무관 — 관측 ~14k 도메인 전수 등록 의도로 기본 0. 무제한 시 1회 ~14k upsert(대부분 lastSeenAt UPDATE, @DynamicUpdate) = PostgreSQL 감당 범위.
- **서버측 topk(선택)**: 병적 카디널리티 방어로 `topk(N, sum by (domain)(...))` 래핑 가능(전송량·집계 상한). 단 결과가 이미 집계 벡터(경량)라 **client 측 max-domains-per-run 이 권위**, topk 는 belt-and-suspenders.
- **셀렉터**: `{job="access_log"}` 단일 시작. Loki 타임아웃/429 빈발 시 **hostname 라벨별 분할 폴백**(`{job="access_log", hostname="X"}` 순회) — 측정 후 도입(§10).

## 7. 설정 (@ConfigurationProperties)

`ApiDiscoverProperties` 에 nested `Discovery` 레코드 추가(기존 Loki/Schedule/Central 패턴):
```
apidiscover.discovery:
  enabled: true
  interval: PT10M            # 롤링 주기(분단위)
  window: PT12M              # interval + 오버랩
  bootstrap-window: PT1H     # 첫 실행 1회
  initial-delay: PT2M        # 스캔 스케줄과 stagger offset
  max-domains-per-run: 200   # 폭증 가드
  host-pattern: "^[a-z0-9]...$"  # FQDN 검증(기본값 내장)
```
- `enabled:false` 면 스케줄러 no-op(무회귀 토글). 신규 키만 추가(기존 설정 불변).

## 8. 무회귀 / 리스크 (정직)

- **무회귀**: 신규 컴포넌트/메서드/설정만 가산. `queryRange`·기존 스캔·DomainConfig 기존 필드 불변. `discovery.enabled=false`=완전 비활성. DomainConfig 가산 필드 ddl-auto·기존행 null.
- **리스크①(필드 레이아웃 LogQL 중복)**: §1 pattern 포지션이 LogLineParser 와 어긋나면 잘못된 도메인 추출 → §4 교차검증 테스트로 고정.
- **리스크②(label_format coalesce 동작 확인)**: Loki 3.0 Go 템플릿 `label_format` 의 `or`/`eq` 동작·`-` 리터럴 처리를 **실 Loki 소창으로 1회 확인 필요**(LokiLiveIntegrationTest 패턴, 작은 창/off-peak). pattern 캡처가 `-`/빈값을 어떻게 주는지(빈 문자열 vs 미존재)도 함께 확인.
- **리스크③(서버측 파싱 CPU)**: 대용량 W 의 라인 전수 파싱은 Loki 부하 → W 작게·부트스트랩 off-peak·throttle 준수.
- **리스크④(자동등록 범위)**: 가드(§6)에도 정상 트래픽의 신규 도메인은 등록됨 — 의도된 동작. 과등록 우려 시 max-domains/host-pattern 강화로 운영 조정(삭제 없이).

## 9. dev 구현 체크리스트 (TASKS subitem, D26)

- [x] `LokiClient` instant 벡터 쿼리 메서드(`/loki/api/v1/query`, vector 파싱) — `requestWithRetry` URL 인자형 추출로 throttle/concurrency/backoff/timeout 재사용(queryRange 불변). `MetricSample(labels,value)` 반환.
- [x] `ApiDiscoverProperties.Discovery` nested 레코드 + application.yml 기본값(§7).
- [x] `DomainDiscoveryService` — LogQL(§1) 빌드·instant 쿼리·벡터→(domain→hostnames) 집계·host-pattern 검증·max-domains 상한(카운트 desc).
- [x] 업서트(§5, 무삭제·설정보존·hostnames 합집합) + `DomainConfig.discoveredAt`/`lastSeenAt` 가산(ddl-auto). `DomainConfigRepository` 기존 메서드로 충분(findById/save/count) — 추가 메서드 불요.
- [x] `DomainDiscoveryScheduler`(@Scheduled fixedDelay+initialDelay, enabled 토글, 예외 격리) — 스캔과 분리·stagger.
- [x] 윈도우(롤링 + 부트스트랩 1h 1회, 빈 도메인 DB=`repo.count()==0` 감지. 영속 플래그 YAGNI).
- [x] 테스트 — 벡터 파싱/coalesce LogQL/host-pattern 거름/max-domains 상한/업서트 머지(합집합·설정보존·무삭제)/부트스트랩 vs 롤링/필드포지션 교차검증, 13건 green. 실 Loki 검증=`DomainDiscoveryLiveIntegrationTest`(LokiLive 게이트 `-Dloki.live`, 기본 빌드 미실행).
- [ ] doc/18 sync(technical_writer): `domain_config.discovered_at`/`last_seen_at` 컬럼.

## 10. 범위 밖 / 후속

- **hostname 라벨별 셀렉터 폴백 실구현** — §6, Loki 타임아웃 측정 후.
- **SchedulingConfigurer 런타임 동적 주기** — 재기동 허용이라 현재 불요(YAGNI).
- **targeted 스캔 경로(`{hostname=X}|=domain`)로 전환** — hostnames 자동확보의 후속 활용(스캔 효율), 별도 항목.
- **stale 도메인 비활성 제안 UI** — 삭제 없음 원칙 하 `lastSeenAt` 기반 운영 가시화는 후속(자동 비활성도 안 함).
