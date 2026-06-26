# 컨테이너 배포·실행·CLI 런북 (테스트 배포, doc/31 C4)

> app + postgres 2컨테이너 1 pod(podman). 산출물: `Dockerfile`·`wait-for-db.sh`·`adc.yaml`·`application-container.yml`(container 프로파일).
> ★대상 Loki(192.168.8.100:3200)는 **운영 서버** — 컨테이너 스캔·디스커버리도 LokiClient 부하보호(윈도우·page-limit·throttle·동시성) 준수, 대용량은 **off-peak**(01:00–06:00). 임시 확인도 창/limit 작게.
> ★네트워크: `adc.yaml` 은 **hostNetwork: true** — 기본 bridge 는 LAN 운영 Loki 에 egress 불가(connect timeout)라, pod 가 host 네트워크를 써야 Loki 도달(§3). app↔db 는 동일 host netns 의 `localhost:5432`.
> ★기동 순서: app 컨테이너는 **`wait-for-db.sh`** 로 DB(`localhost:5432`) 준비를 대기한 뒤 기동 — 동시 기동(pod) 시 DB initdb 지연으로 인한 **재시작 폭주(크래시 루프) 방지**(§2).

## 0. 사전 준비 (host, rootful=root 로그인 기준)

```bash
mkdir -p /opt/adc /opt/adc-exports          # PGDATA / CSV 출력(서로 분리 — initdb 충돌 회피, doc/31 B3)
chcon -Rt container_file_t /opt/adc /opt/adc-exports   # SELinux Enforcing 시 컨테이너 접근 허용(Rocky 등)
# /opt/adc 는 비워둔다(postgres entrypoint 가 root 로 시작해 PGDATA 하위 pgdata 를 chown·initdb).
# rootless(uid 1000) 환경이면 위 chcon 대신 chown -R 1000:1000 로 조정.
```

## 1. 이미지 빌드·전송

```bash
podman build -t localhost/apidiscover:test .     # 멀티스테이지 bootJar→JRE + wait-for-db.sh
# adc.yaml 의 image: localhost/apidiscover:test 와 태그 일치
```

원격 테스트서버로 전송 시(레지스트리 미사용) — ★**이미지를 개별 tar 로 저장·전송**한다:

```bash
podman save localhost/apidiscover:test -o app.tar          # ★개별 저장
podman save docker.io/library/postgres:16-alpine -o pg.tar # ★개별 저장
scp app.tar pg.tar <vm>:/root/ && ssh <vm> 'podman load -i /root/app.tar; podman load -i /root/pg.tar'
```

> ⚠️ **`podman save img1 img2 -o one.tar`(결합 저장) 금지** — load 시 두 태그가 한 이미지로 뭉개져(태그 collision) postgres 태그가 app 이미지를 가리키는 사고 발생. 반드시 개별 tar. (또는 VM 인터넷 가용 시 `podman pull docker.io/library/postgres:16-alpine` 로 직접 받는다.)
> 로드 후 검증: `podman images`에서 app(약 401MB)·postgres(약 297MB)의 **이미지 ID 가 서로 달라야** 한다.

## 2. 기동 (pod = app + postgres, hostNetwork)

```bash
podman play kube adc.yaml
podman pod ps && podman ps --pod          # adc pod 의 db·app Running 확인
```
- `hostNetwork: true` → postgres=host:5432, app=host:8080 직접 바인드(포트 충돌 없게 host 의 5432/8080 비워둘 것).
- app 은 `wait-for-db.sh` 로 `localhost:5432` 준비를 대기 후 기동(기본 최대 120s) → **DB 미준비로 인한 크래시 루프 없음**.
- app 은 `SPRING_PROFILES_ACTIVE=container` → PostgreSQL(`localhost:5432/adc`)·`ddl-auto=update` 로 스키마 생성.
- 첫 기동 시 postgres 가 `/opt/adc/pgdata` 에 initdb(root entrypoint 가 chown 후 강등).

## 3. 기동 확인

```bash
curl -s localhost:8080/actuator/health      # {"status":"UP"} — DB 연결 포함
curl -s localhost:8080/api/v1/domains/<host>/discovery   # 결합 Discovery(검출∪스펙, DB 조회)
podman logs adc-app | grep 'domain discovery:'           # 디스커버리 1회 실행 로그(bootstrap/vector/inserted)
```
- 디스커버리/스캔 스케줄러는 **운영 Loki 를 호출**한다(hostNetwork 로 도달). `apidiscover.discovery.initial-delay`(기본 2분) 후 첫 실행, 이후 `interval`(기본 10분). 운영검증 전 부팅만 보려면 `apidiscover.discovery.enabled=false`·`spring.batch.job.enabled=false`(기본) 로 스케줄러를 끈다.
- 컨테이너→LAN Loki 도달은 hostNetwork 전제(§위). bridge 로 두면 `HttpConnectTimeoutException` 발생.

## 4. CLI 명령 (one-off, 서빙 무부하)

> 이미지가 `SPRING_PROFILES_ACTIVE=container` 를 기본값으로 baking(Dockerfile) → one-off CLI 도 **env 없이 PG(localhost:5432) 접속**(빠뜨리면 빈 H2 인메모리에 붙어 결과 0). `--network host` 로 실행 중 pod 의 postgres 와 동일 host netns 공유.

```bash
# (a) 수집 도메인 목록 확인 — stdout
podman run --rm --network host localhost/apidiscover:test -domain -ls

# (b) 특정 도메인 API 결과 CSV
podman run --rm --network host -v /opt/adc-exports:/exports \
  localhost/apidiscover:test --adc.cli.export-domain=<domain>
# → /opt/adc-exports/<domain>-<epochmillis>.csv

# (c) 특정 도메인 즉시 스캔(운영자 온디맨드, watermark 미전진)
podman run --rm --network host localhost/apidiscover:test \
  --adc.cli.scan-domain=<domain> [--window=PT30M] [--edge=<hostname>]
```
- 인자는 ENTRYPOINT(`wait-for-db.sh`→`java -jar /app/app.jar`) 뒤에 append → CLI 모드(웹·스케줄러 미기동, 1 명령 후 종료). 목록/내보내기는 Loki 미호출(DB only), scan-domain 만 Loki 호출(부하보호 준수).
- 목록(`-domain -ls`)은 단일대시 `-domain`+`-ls`, 내보내기/스캔은 `--adc.cli.X=` 스타일(혼재, D47).
- export exit: 0 성공 / 2 도메인 미지정 / 3 도메인 미존재·검출 0건 / 4 쓰기 실패.
- CSV 15열+헤더: host·method·path_template·status·source·confidence·severity·estimated·spec_ref·preflight_ambiguous·low_confidence·param_query·param_path·first_seen·last_seen. (score 는 미영속 → 범위 밖.)
- 대안: `podman exec adc-app java -jar /app/app.jar --adc.cli.export-domain=<domain>`(서빙 컨테이너 2nd JVM, 자원 점유 — exec 는 ENTRYPOINT 우회라 `java -jar` 명시).

## 5. 종료/정리

```bash
podman pod stop adc && podman pod rm adc     # /opt/adc(PGDATA)·/opt/adc-exports 는 보존(hostPath)
```

## 6. 배포 검증 체크리스트 (운영 Loki 주의)

- [ ] 이미지 개별 전송·로드 후 app/postgres **이미지 ID 분리** 확인(결합 save 금지).
- [ ] `podman play kube adc.yaml`(hostNetwork) 기동 + `wait-for-db` 로 크래시 루프 없이 1회 기동 + `/actuator/health` UP + DB 스키마 생성.
- [ ] **컨테이너→LAN Loki(192.168.8.100:3200) 도달**(hostNetwork) — `domain discovery:` 로그에 `vector=N` 출현(timeout 아님).
- [ ] 실 Loki `label_format` coalesce·디스커버리 1회(소창·off-peak) 후 `domain_config` 에 도메인 적재 확인.
- [ ] CLI CSV 가 실제 도메인에서 정상 생성·컬럼 정합.
