-- 유령(endpoint 0)·제외엣지전용 도메인 하드 정리 runbook (doc/42 §7 · B/B-2/D). 사용자 결정=hard DELETE + 자기치유.
--
-- ★★ 실행 금지(파괴적). A+C 배포 → 1~2일 관찰 후, 매니저가 사용자 최종 확인 하에 psql 로 실행한다.
--    실행 경로: ssh root@192.168.8.197 → `podman exec -i adc-db psql -U adc -d adc -f -` (또는 대화형 단계 실행).
--
-- ★ FK 실측(2026-07-14 운영 PG information_schema): 앱 FK 는 아래 단 1개.
--     fkt50ge18f4g2dfb5cntslr8dsi : domain_hostnames.host → domain_config.host
--   watermark·scan_result·discovered_endpoint 는 host 를 PK/키로 갖지만 FK 없음(앱레벨 조인) → 삭제 순서 자유.
--   ∴ 무결성상 필수 순서 = domain_hostnames(자식) 먼저 → domain_config(부모) 마지막.
--     나머지 host-키 테이블은 정합·자기치유(재등록 시 stale watermark 방지)를 위해 함께 purge(순서 무관).
--   spec_record·documented_api 는 삭제하지 않는다(§3 (d) 안전기준이 스펙/문서 보유 도메인을 애초에 대상에서 제외).
--
-- 트랜잭션: 전체를 BEGIN…COMMIT 로 원자 실행. 카운트 검증(단계 1)·사후 검증(단계 4)을 눈으로 확인한 뒤에만 COMMIT.
--   이상 시 COMMIT 대신 ROLLBACK → 무변경. 백업 테이블은 COMMIT 이후에도 남아 롤백(§5) 소스가 된다.

\set ON_ERROR_STOP on
BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 0) 백업 (하드 삭제 대비 — 삭제하는 모든 테이블 스냅샷; 롤백=재적재 소스). D62 선례.
-- ─────────────────────────────────────────────────────────────────────────────

-- ghost 버킷: §3 안전기준 (a)스캔이력 + (b)7일 지속 + (d)사용자흔적/스펙/문서 없음 + endpoint 0.
--   'endpoint 0' 단독 판정 금지 — 아래 전 조건 동시 충족만 대상(오탐 방지).
CREATE TABLE ghost_cleanup_bak_domain_config AS
SELECT dc.*, now() AS bak_at, 'ghost'::text AS bucket
FROM domain_config dc
WHERE dc.enabled
  AND dc.last_scan_attempt_at IS NOT NULL                          -- (a) 스캔 이력 있음
  AND dc.discovered_at <= now() - interval '7 days'                -- (b) 7일 지속(일시요인 배제)
  AND dc.interval_override IS NULL AND dc.base_path_strip IS NULL   -- (d) 사용자 설정 흔적 없음
  AND NOT EXISTS (SELECT 1 FROM discovered_endpoint de WHERE de.host = dc.host)  -- endpoint 0
  AND NOT EXISTS (SELECT 1 FROM spec_record      sr WHERE sr.host = dc.host)     -- (d) 스펙 업로드 도메인 절대 제외
  AND NOT EXISTS (SELECT 1 FROM documented_api   da WHERE da.host = dc.host);    -- (d) 문서 API 보유 도메인 제외

-- excluded-only 버킷(B-2): hostnames 가 존재하며 전부 제외 엣지(AAJ 23 + P* + NEW-PAJ*)인 도메인 = 스캔불가.
--   ghost 와 중복(510)은 NOT IN 으로 1회만. (d) 보호는 동일 적용.
INSERT INTO ghost_cleanup_bak_domain_config
SELECT dc.*, now(), 'excluded-only'
FROM domain_config dc
WHERE dc.enabled
  AND NOT EXISTS (SELECT 1 FROM ghost_cleanup_bak_domain_config g WHERE g.host = dc.host)  -- ghost 중복 제외(index anti-join)
  AND dc.interval_override IS NULL AND dc.base_path_strip IS NULL
  AND NOT EXISTS (SELECT 1 FROM spec_record    sr WHERE sr.host = dc.host)
  AND NOT EXISTS (SELECT 1 FROM documented_api da WHERE da.host = dc.host)
  AND EXISTS (SELECT 1 FROM domain_hostnames dh WHERE dh.host = dc.host)         -- hostnames 존재(무-엣지 도메인 제외)
  AND NOT EXISTS (                                                               -- 비제외 엣지가 하나도 없음 = 전부 제외
        SELECT 1 FROM domain_hostnames dh
        WHERE dh.host = dc.host
          AND NOT (dh.hostname LIKE 'P%' OR dh.hostname LIKE 'NEW-PAJ%'
                   OR dh.hostname IN ('AAJ11','AAJ12','AAJ13','AAJ14','AAJ21','AAJ22','AAJ23','AAJ24',
                                      'AAJ31','AAJ32','AAJ33','AAJ34','AAJ41','AAJ42','AAJ43','AAJ44',
                                      'AAJ51','AAJ52','AAJ53','AAJ54','AAJ61','AAJ62','AAJ63')));

CREATE INDEX ON ghost_cleanup_bak_domain_config (host);   -- 이후 IN (SELECT host …) 조인 가속

-- 자식/부수 테이블 백업(하드 삭제 대상 전부). ghost 는 discovered_endpoint 0 이나 excluded-only 는 있을 수 있어 함께 백업.
CREATE TABLE ghost_cleanup_bak_domain_hostnames AS
  SELECT dh.* FROM domain_hostnames dh    WHERE dh.host IN (SELECT host FROM ghost_cleanup_bak_domain_config);
CREATE TABLE ghost_cleanup_bak_watermark AS
  SELECT w.*  FROM watermark w            WHERE w.host  IN (SELECT host FROM ghost_cleanup_bak_domain_config);
CREATE TABLE ghost_cleanup_bak_scan_result AS
  SELECT s.*  FROM scan_result s          WHERE s.host  IN (SELECT host FROM ghost_cleanup_bak_domain_config);
CREATE TABLE ghost_cleanup_bak_discovered_endpoint AS
  SELECT de.* FROM discovered_endpoint de WHERE de.host IN (SELECT host FROM ghost_cleanup_bak_domain_config);

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) 카운트 검증 — 승인값과 대조(설계 실측 예상: ghost ~28.7k, excluded-only ~2.6k). 불일치 시 ROLLBACK.
-- ─────────────────────────────────────────────────────────────────────────────
SELECT bucket, count(*) AS domains FROM ghost_cleanup_bak_domain_config GROUP BY bucket ORDER BY bucket;
SELECT 'domain_hostnames' AS tbl, count(*) FROM ghost_cleanup_bak_domain_hostnames
UNION ALL SELECT 'watermark',           count(*) FROM ghost_cleanup_bak_watermark
UNION ALL SELECT 'scan_result',         count(*) FROM ghost_cleanup_bak_scan_result
UNION ALL SELECT 'discovered_endpoint', count(*) FROM ghost_cleanup_bak_discovered_endpoint;
-- ↑ 값 확인. 원자 트랜잭션이라 최종 COMMIT 전까지 실 반영 없음. 예상과 크게 다르면 ROLLBACK 후 기준 재검토.

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) ★ 하드 DELETE — FK 순서: 자식(domain_hostnames) → 부수(watermark/scan_result/discovered_endpoint) → 부모(domain_config)
-- ─────────────────────────────────────────────────────────────────────────────
DELETE FROM domain_hostnames    WHERE host IN (SELECT host FROM ghost_cleanup_bak_domain_config);  -- FK 자식 먼저(필수)
DELETE FROM watermark           WHERE host IN (SELECT host FROM ghost_cleanup_bak_domain_config);  -- FK 없음, 정합 purge
DELETE FROM scan_result         WHERE host IN (SELECT host FROM ghost_cleanup_bak_domain_config);  -- FK 없음
DELETE FROM discovered_endpoint WHERE host IN (SELECT host FROM ghost_cleanup_bak_domain_config);  -- ghost=0행, excl-only 만
DELETE FROM domain_config       WHERE host IN (SELECT host FROM ghost_cleanup_bak_domain_config);  -- 부모 마지막(필수)

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) D 백필(§4.5) — 남은(비삭제) 도메인의 last_access_log_at NULL 정합화. 삭제셋과 무관·멱등·never-decrease(NULL→값).
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE domain_config dc SET last_access_log_at = m.max_seen
FROM (SELECT host, max(last_seen) AS max_seen FROM discovered_endpoint GROUP BY host) m
WHERE m.host = dc.host AND dc.last_access_log_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4) 사후 검증(COMMIT 전 최종 확인)
-- ─────────────────────────────────────────────────────────────────────────────
SELECT count(*) FILTER (WHERE enabled) AS enabled_after, count(*) AS total_after FROM domain_config;
SELECT count(*) AS orphan_hostnames FROM domain_hostnames dh                        -- 0 이어야(자식 선삭제 확인)
  WHERE NOT EXISTS (SELECT 1 FROM domain_config dc WHERE dc.host = dh.host);

COMMIT;   -- ← 단계 1·4 검증을 모두 통과했을 때만. 조금이라도 이상하면: ROLLBACK;

-- ═════════════════════════════════════════════════════════════════════════════
-- 5) 롤백 (COMMIT 이후 오분류 발견 시 — 백업에서 재적재). FK 순서 역: 부모 먼저 → 자식.
--    자기치유 전제상 핵심 복원 = domain_config(enabled·discovered_at·설정) + domain_hostnames(엣지 매핑).
--    watermark/scan_result/discovered_endpoint 는 재스캔이 재생성하므로 복원 선택(아래 (b) 참고).
-- ═════════════════════════════════════════════════════════════════════════════
-- (a) 필수 복원 — sticky 설정(재스캔으로 복구 불가한 것):
-- BEGIN;
-- INSERT INTO domain_config (host, base_path_strip, created_at, discovered_at, enabled, interval_override,
--        last_seen_at, spec_merge_strategy, updated_at, last_scan_attempt_at, next_scan_due_at, last_access_log_at)
--   SELECT host, base_path_strip, created_at, discovered_at, enabled, interval_override,
--        last_seen_at, spec_merge_strategy, updated_at, last_scan_attempt_at, next_scan_due_at, last_access_log_at
--   FROM ghost_cleanup_bak_domain_config
--   ON CONFLICT (host) DO NOTHING;
-- INSERT INTO domain_hostnames (host, hostname)
--   SELECT host, hostname FROM ghost_cleanup_bak_domain_hostnames ON CONFLICT DO NOTHING;
-- COMMIT;
--
-- (b) 선택 복원 — 운영 상태(대개 재스캔이 재생성; 즉시 필요 시에만):
--   watermark:   INSERT INTO watermark (host, last_end) SELECT host, last_end FROM ghost_cleanup_bak_watermark ON CONFLICT (host) DO NOTHING;
--   scan_result: INSERT INTO scan_result SELECT * FROM ghost_cleanup_bak_scan_result ON CONFLICT (host) DO NOTHING;
--   discovered_endpoint: id 가 GENERATED IDENTITY 이므로 explicit id 복원은 OVERRIDING SYSTEM VALUE 필요 —
--     INSERT INTO discovered_endpoint OVERRIDING SYSTEM VALUE SELECT * FROM ghost_cleanup_bak_discovered_endpoint ON CONFLICT DO NOTHING;
--
-- (c) C 배포 결합 복구 후보 — 비활성이 아닌 하드삭제라 "재관측=재등록"이 자동. 참고용, 삭제 후 재등장 도메인:
--   SELECT dc.host, dc.last_seen_at FROM domain_config dc JOIN ghost_cleanup_bak_domain_config b USING (host)
--   WHERE dc.discovered_at > b.bak_at;   -- C 배포로 신뢰 status 트래픽이 재등록시킨 도메인(정상 자기치유 증거)
--
-- 백업 정리(정착 확인 후, 수일 뒤): DROP TABLE IF EXISTS ghost_cleanup_bak_domain_config,
--   ghost_cleanup_bak_domain_hostnames, ghost_cleanup_bak_watermark, ghost_cleanup_bak_scan_result,
--   ghost_cleanup_bak_discovered_endpoint;
