# 컨테이너 배포·실행·CLI 런북 (테스트 배포, doc/31 C4)

> app + postgres 2컨테이너 1 pod(podman). 산출물: `Dockerfile`·`adc.yaml`·`application-container.yml`(container 프로파일).
> ★대상 Loki(192.168.8.100:3200)는 **운영 서버** — 컨테이너 스캔·디스커버리도 LokiClient 부하보호(윈도우·page-limit·throttle·동시성) 준수, 대용량은 **off-peak**(01:00–06:00). 임시 확인도 창/limit 작게.

## 0. 사전 준비 (host)

```bash
sudo mkdir -p /opt/adc /opt/adc-exports        # PGDATA / CSV 출력(서로 분리 — initdb 충돌 회피, doc/31 B3)
sudo chown -R 1000:1000 /opt/adc /opt/adc-exports   # rootless podman uid 매핑에 맞게(환경별 조정)
```

## 1. 이미지 빌드

```bash
podman build -t apidiscover:test .
# adc.yaml 의 image: localhost/apidiscover:test 와 태그 일치
```

## 2. 기동 (pod = app + postgres)

```bash
podman play kube adc.yaml
podman pod ps && podman ps --pod          # adc pod 의 db·app Running 확인
```
- app 은 `SPRING_PROFILES_ACTIVE=container` → PostgreSQL(`localhost:5432/adc`)·`ddl-auto=update` 로 스키마 생성.
- 첫 기동 시 postgres 가 `/opt/adc/pgdata` 에 initdb.

## 3. 기동 확인 (운영 Loki 미호출 경로만)

```bash
curl -s localhost:8080/actuator/health      # {"status":"UP"} — DB 연결 포함
curl -s localhost:8080/api/v1/domains/<host>/discovery   # 결합 Discovery(검출∪스펙, DB 조회 — Loki 미호출)
```
> 스캔/디스커버리 스케줄러는 운영 Loki 를 호출한다. **운영 검증 전이라면** `apidiscover.discovery.enabled=false`·`spring.batch.job.enabled=false`(기본) 등으로 스케줄러를 끄고 부팅·DB·비-Loki 엔드포인트만 확인한다. 실 Loki 도달·coalesce 검증은 §6 절차로 운영주의해서 별도 수행.

## 4. CLI CSV 내보내기 (one-off, 서빙 무부하)

```bash
podman run --rm --pod adc \
  -v /opt/adc-exports:/exports \
  localhost/apidiscover:test \
  --adc.cli.export-domain=<domain>
# → /opt/adc-exports/<domain>-<epochmillis>.csv (host /opt/adc-exports)
```
- `--adc.cli.export-domain` 인자가 CLI 모드를 켠다(웹·스케줄러 미기동 → Loki 미호출, 1 명령 후 종료).
- exit code: 0 성공 / 2 도메인 미지정 / 3 도메인 미존재·검출 0건 / 4 쓰기 실패.
- CSV 15열+헤더: host·method·path_template·status·source·confidence·severity·estimated·spec_ref·preflight_ambiguous·low_confidence·param_query·param_path·first_seen·last_seen. (score 는 미영속 → 범위 밖.)
- 대안: `podman exec adc-app java -jar /app/app.jar --adc.cli.export-domain=<domain>`(서빙 컨테이너 2nd JVM, 자원 점유).

## 5. 종료/정리

```bash
podman pod stop adc && podman pod rm adc     # /opt/adc(PGDATA)·/opt/adc-exports 는 보존(hostPath)
```

## 6. 미수행 — 배포 시 확인 항목 (운영 Loki 주의, 매니저/사용자 수행)

이 PR 은 **podman build 성공 + 단위/통합테스트(운영 Loki 미호출)까지** 검증했다. 다음은 테스트서버 배포 시 운영주의로 확인:
- [ ] `podman play kube adc.yaml` 실제 기동 + `/actuator/health` UP + DB 스키마 생성.
- [ ] **컨테이너→LAN Loki(192.168.8.100:3200) 도달**(rootless podman pod egress) — 미도달 시 `--network` 조정(doc/31 C3 리스크③).
- [ ] 실 Loki `label_format` coalesce·디스커버리 1회(소창·off-peak) — `DomainDiscoveryLiveIntegrationTest`(`-Dloki.live=true`) 또는 짧은 스케줄 1회.
- [ ] CLI CSV 가 실제 도메인에서 정상 생성·컬럼 정합.
