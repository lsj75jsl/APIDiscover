-- 유령(endpoint 0)·제외엣지전용 도메인 하드 정리 runbook (doc/42 §7 · B/B-2/D). 사용자 결정=hard DELETE·백업 없음·자기치유.
--
-- 상태: 2026-07-15 실행 완료 — ghost 29,361 + excluded-only 2,624 = 31,985 도메인 삭제(due 53.3k→20.4k).
--   이 파일은 그 절차의 기록이자, 재발 시(신규 유령 누적·C 불충분) 재사용 템플릿이다. ★재실행은 파괴적 — 사용자 최종 승인 하에만.
--
-- ★★ 백업 없음(사용자 지시): 백업 테이블을 만들지 않는다. 삭제는 되돌릴 수 없다.
--   유일한 복구 = 자기치유 — C(probe status 필터)로 프로브 재유입이 막힌 상태에서 실 트래픽이 오면 discovery 가 재등록.
--   그래서 삭제 전 반드시 dry-run(단계 A)으로 대상·건수를 눈으로 확인하고, 본 실행엔 pre/post-guard(예상 밴드 이탈 시 롤백)를 둔다.
--
-- ★ 데드락 회피(2026-07-15 실측 교훈): 앱 가동 중 대량 멀티테이블 DELETE 는 discovery/scan 의 domain_config·watermark
--   동시 쓰기와 **데드락** → 원자 트랜잭션이 통째로 롤백된다(무손실이나 미완). ∴ 삭제 전 adc-app 컨테이너를 잠깐 멈춰
--   경합을 없앤다(adc-db 는 유지). 다운타임 ~2분(백그라운드 워커 — REST/스케줄러만 잠깐 멈춤, 재기동 시 워터마크에서 이어감).
--
-- ★ FK 실측(운영 PG information_schema): 앱 FK 는 domain_hostnames.host→domain_config.host 단 1개.
--   watermark·scan_result·discovered_endpoint 는 host-키이나 FK 없음(앱레벨 조인).
--   삭제 순서 = domain_hostnames(자식) → watermark → scan_result → discovered_endpoint → domain_config(부모).
--   spec_record·documented_api·domain_classification_config 는 삭제하지 않는다(§3 (d) 안전기준이 스펙/문서/분류정책 보유 도메인을 대상에서 제외).
--
-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- 실행 래퍼 (shell, ssh root@192.168.8.197) — SQL 아님. 반드시 이 순서.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--   # 0) [필수] dry-run 프리뷰 (읽기 전용) — 아래 '단계 A' 블록을 psql 에 붙여 실행, 건수·unsafe=0 확인 후 종료(무변경).
--   podman exec -i adc-db psql -U adc -d adc      # 단계 A 붙여넣기
--
--   # 1) ★앱 워커 정지 — 경합 제거(데드락 회피). adc-db 는 유지.
--   podman stop -t 30 adc-app
--   podman ps -a --format '{{.Names}} {{.State}}' | grep 'adc-'   # adc-app exited / adc-db running 확인
--
--   # 2) 원자 삭제 실행(이 파일 본문 = 단계 B).
--   podman exec -i adc-db psql -U adc -d adc -f - < sample/ghost_domain_cleanup.sql
--
--   # 3) 앱 워커 재기동 + health UP 확인.
--   podman start adc-app
--   for i in $(seq 1 30); do curl -sf -m5 localhost:8080/actuator/health | grep -q '"status":"UP"' && { echo UP; break; }; sleep 10; done
--
-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- 단계 A — DRY-RUN 프리뷰 (읽기 전용; 별도 psql 세션에서 먼저 실행, 변경 없음). 건수 예상 크게 벗어나면 중단·재검토.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--   SET statement_timeout='180s';
--   CREATE TEMP TABLE del_hosts AS <아래 단계 B 의 ghost SELECT>;  -- ghost 버킷
--   INSERT INTO del_hosts <아래 단계 B 의 excluded-only SELECT>;    -- excluded-only 버킷
--   CREATE INDEX ON del_hosts(host);
--   SELECT bucket, count(*) FROM del_hosts GROUP BY bucket ORDER BY bucket;                 -- 예상: ghost ~29k / excluded-only ~2.6k
--   SELECT 'domain_hostnames' tbl,count(*) FROM domain_hostnames    WHERE host IN (SELECT host FROM del_hosts)
--   UNION ALL SELECT 'watermark',          count(*) FROM watermark            WHERE host IN (SELECT host FROM del_hosts)
--   UNION ALL SELECT 'scan_result',        count(*) FROM scan_result          WHERE host IN (SELECT host FROM del_hosts)
--   UNION ALL SELECT 'discovered_endpoint',count(*) FROM discovered_endpoint  WHERE host IN (SELECT host FROM del_hosts)
--   UNION ALL SELECT 'domain_config',      count(*) FROM domain_config        WHERE host IN (SELECT host FROM del_hosts);
--   -- SAFETY(반드시 0): 삭제 대상 중 사용자/운영자 흔적 보유 도메인
--   SELECT count(*) unsafe FROM del_hosts d JOIN domain_config dc USING(host)
--   WHERE dc.interval_override IS NOT NULL OR dc.base_path_strip IS NOT NULL
--      OR EXISTS(SELECT 1 FROM spec_record s WHERE s.host=d.host)
--      OR EXISTS(SELECT 1 FROM documented_api a WHERE a.host=d.host)
--      OR EXISTS(SELECT 1 FROM domain_classification_config c WHERE c.host=d.host);
--   -- (세션 종료 = temp 소멸·무변경.) dry-run 건수를 아래 단계 B PRE-GUARD 밴드에 반영해 튜닝한다.
--
-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- 단계 B — 원자 하드 DELETE (이 파일 본문; ★반드시 adc-app 정지 상태에서 실행)
-- ════════════════════════════════════════════════════════════════════════════════════════════════

\set ON_ERROR_STOP on
SET statement_timeout = '180s';

-- 안전 체크: 활성(비-idle) 앱 백엔드가 없어야 한다(=adc-app 정지 확인). 0 아니면 STOP 후 재시도.
SELECT count(*) AS other_active_backends FROM pg_stat_activity
  WHERE datname = 'adc' AND pid <> pg_backend_pid() AND state <> 'idle';

-- 1) 삭제 host 집합 고정 — 세션 temp 테이블(★백업 아님: host+bucket 만, 세션 종료 시 소멸). BEGIN 밖(autocommit)에서 빌드해 트랜잭션을 짧게.
--    한 번 고정해 두 버킷·전 FK 테이블에 동일 집합을 적용(삭제 도중 술어 재평가로 인한 드리프트 차단).
CREATE TEMP TABLE del_hosts AS
SELECT dc.host, 'ghost'::text AS bucket
FROM domain_config dc
WHERE dc.enabled
  AND dc.last_scan_attempt_at IS NOT NULL                          -- (a) 스캔 이력 있음
  AND dc.discovered_at <= now() - interval '7 days'                -- (b) 7일 지속(일시요인 배제)
  AND dc.interval_override IS NULL AND dc.base_path_strip IS NULL   -- (d) 사용자 설정 흔적 없음
  AND NOT EXISTS (SELECT 1 FROM discovered_endpoint de WHERE de.host = dc.host)  -- endpoint 0
  AND NOT EXISTS (SELECT 1 FROM spec_record      sr WHERE sr.host = dc.host)     -- (d) 스펙 업로드 도메인 절대 제외
  AND NOT EXISTS (SELECT 1 FROM documented_api   da WHERE da.host = dc.host)     -- (d) 문서 API 보유 도메인 제외
  AND NOT EXISTS (SELECT 1 FROM domain_classification_config dcc WHERE dcc.host = dc.host);  -- (d) 도메인별 분류정책=운영자 흔적 보호

INSERT INTO del_hosts
SELECT dc.host, 'excluded-only'
FROM domain_config dc
WHERE dc.enabled
  AND NOT EXISTS (SELECT 1 FROM del_hosts g WHERE g.host = dc.host)  -- ghost 중복 제외(index anti-join)
  AND dc.interval_override IS NULL AND dc.base_path_strip IS NULL
  AND NOT EXISTS (SELECT 1 FROM spec_record      sr WHERE sr.host = dc.host)
  AND NOT EXISTS (SELECT 1 FROM documented_api   da WHERE da.host = dc.host)
  AND NOT EXISTS (SELECT 1 FROM domain_classification_config dcc WHERE dcc.host = dc.host)  -- (d) 분류정책=운영자 흔적 보호
  AND EXISTS (SELECT 1 FROM domain_hostnames dh WHERE dh.host = dc.host)         -- hostnames 존재(무-엣지 도메인 제외)
  AND NOT EXISTS (                                                               -- 비제외 엣지가 하나도 없음 = 전부 제외
        SELECT 1 FROM domain_hostnames dh
        WHERE dh.host = dc.host
          AND NOT (dh.hostname LIKE 'P%' OR dh.hostname LIKE 'NEW-PAJ%'
                   OR dh.hostname IN ('AAJ11','AAJ12','AAJ13','AAJ14','AAJ21','AAJ22','AAJ23','AAJ24',
                                      'AAJ31','AAJ32','AAJ33','AAJ34','AAJ41','AAJ42','AAJ43','AAJ44',
                                      'AAJ51','AAJ52','AAJ53','AAJ54','AAJ61','AAJ62','AAJ63')));
CREATE INDEX ON del_hosts(host);
SELECT bucket, count(*) FROM del_hosts GROUP BY bucket ORDER BY bucket;

-- 2) PRE-GUARD (데이터 손대기 전): 집합이 예상 밴드 밖이면 중단(무변경). ★밴드는 실행 시점 dry-run 값으로 재조정.
DO $$ DECLARE n int; g int; BEGIN
  SELECT count(*), count(*) FILTER (WHERE bucket = 'ghost') INTO n, g FROM del_hosts;
  IF g NOT BETWEEN 27000 AND 32000 OR n NOT BETWEEN 29000 AND 35000 THEN
    RAISE EXCEPTION 'PRE-GUARD abort: total=% ghost=% (예상 밴드 밖 — dry-run 재확인)', n, g;
  END IF;
  RAISE NOTICE 'pre-guard OK: total=% ghost=%', n, g;
END $$;

-- 3) ★원자 하드 DELETE — FK 순서: domain_hostnames → watermark → scan_result → discovered_endpoint → domain_config
BEGIN;
DELETE FROM domain_hostnames    WHERE host IN (SELECT host FROM del_hosts);  -- FK 자식 먼저(필수)
DELETE FROM watermark           WHERE host IN (SELECT host FROM del_hosts);  -- FK 없음, 정합 purge
DELETE FROM scan_result         WHERE host IN (SELECT host FROM del_hosts);  -- FK 없음
DELETE FROM discovered_endpoint WHERE host IN (SELECT host FROM del_hosts);  -- ghost=0행, excluded-only 만
DELETE FROM domain_config       WHERE host IN (SELECT host FROM del_hosts);  -- 부모 마지막(필수)

-- 4) D 백필(§4.5) — 남은(비삭제) 도메인의 last_access_log_at NULL 정합화. 삭제셋과 무관·멱등·never-decrease(NULL→값).
UPDATE domain_config dc SET last_access_log_at = m.max_seen
FROM (SELECT host, max(last_seen) AS max_seen FROM discovered_endpoint GROUP BY host) m
WHERE m.host = dc.host AND dc.last_access_log_at IS NULL;

-- 5) POST-GUARD (COMMIT 전, 트랜잭션 내): FK 정합(orphan=0)·enabled 잔여가 예상 밴드. 이상 시 RAISE→롤백(무변경).
DO $$ DECLARE orph int; en int; BEGIN
  SELECT count(*) INTO orph FROM domain_hostnames dh WHERE NOT EXISTS (SELECT 1 FROM domain_config dc WHERE dc.host = dh.host);
  SELECT count(*) FILTER (WHERE enabled) INTO en FROM domain_config;
  IF orph <> 0 THEN RAISE EXCEPTION 'POST-GUARD abort: orphan_hostnames=%', orph; END IF;
  IF en NOT BETWEEN 33000 AND 38000 THEN RAISE EXCEPTION 'POST-GUARD abort: enabled_after=% (예상 밴드 밖)', en; END IF;
  RAISE NOTICE 'post-guard OK: orphan=% enabled_after=%', orph, en;
END $$;

-- 6) 사후 검증(COMMIT 전 최종 확인)
SELECT count(*) FILTER (WHERE enabled) AS enabled_after, count(*) AS total_after,
       count(*) FILTER (WHERE enabled AND next_scan_due_at <= now()) AS due_after FROM domain_config;

COMMIT;   -- ← PRE/POST-GUARD·단계 6 이 정상일 때만. 이상 시: ROLLBACK;

-- 7) 사후 near-now(커밋 후) — due 잠식 해소·near-now 회복 확인
SELECT count(*) AS wm_total,
       count(*) FILTER (WHERE last_end >= now() - interval '30 min') AS near_now,
       round(100.0*count(*) FILTER (WHERE last_end >= now() - interval '30 min')/nullif(count(*),0),1) AS near_now_pct
FROM watermark;

-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- 복구(오분류 발견 시) — ★백업 없음이라 재적재 불가. 복구 = 자기치유뿐.
--   C 배포로 프로브 재유입이 막힌 상태에서 삭제 도메인에 실 트래픽이 오면 discovery 가 재등록(enabled=true).
--   삭제 후 재등장(=정상 트래픽 근거) 확인: SELECT host, discovered_at FROM domain_config WHERE discovered_at > '<삭제시각>';
--   특정 도메인 즉시 필요 시: CLI `-domain -register <host>` 또는 `-domain -scan <host>`(doc/32 §4). (이력 discovered_at 은 리셋 = 수용된 트레이드오프, DECISIONS D81.)
-- ════════════════════════════════════════════════════════════════════════════════════════════════
