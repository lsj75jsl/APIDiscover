#!/usr/bin/env python3
# doc/40 §5 시뮬레이션 — discovered_endpoint 피처로 현행 ApiScorer 점수를 재계산해 8.3 신규 4신호 활성 시 과승격(비-API→API) 상한을 정량화한다. DB-only·read-only.
#
# 재현 범위: ApiScorer.scoreExplain(MIDDLE)+evaluate 게이트를 파이썬으로 정확 포팅.
#   14신호 중 13 정확 재현(pathHint 만 미재현 — NONE=0). endpoint_kind/kind_confidence 영속 컬럼 활용
#   (responseTypeApi·staticAssetPenalty·corsPreflight[OPTIONS self-join] 포함).
#   현행 설정 확인값: profile=MIDDLE·threshold=0.70·weight override 없음·정적 토큰=기본·oversize 0건·API_CANDIDATE 0건.
#
# 부하: 운영 PG(192.168.8.197)에 read-only 단일 seq scan 1회(COPY|gzip 스트림). 반복 조회 없음.
# 실행: python3 sample/log_signal_promotion_sim.py   (ssh root@192.168.8.197 + adc-db 컨테이너 psql 필요)
#       python3 sample/log_signal_promotion_sim.py --selftest   (DB 무접속, 포팅 로직 자체검증)

import subprocess
import csv
import io
import gzip
import re
import sys
from collections import Counter

SSH = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "root@192.168.8.197"]
PSQL = "podman exec adc-db psql -U adc -d adc"

# --- ApiScorer MIDDLE 가중치 (ApiScorer.java MIDDLE preset) ---
W = {
    "hostApiSubdomain": 0.40, "corsPreflight": 0.30, "apiSegment": 0.55, "graphqlSegment": 0.55,
    "versionSegment": 0.26, "pathIdSegment": 0.15, "machineEndpoint": 0.20, "writeMethod": 0.34,
    "query": 0.12, "nonBrowserUa": 0.24, "staticAssetPenalty": -0.60, "repeatBonus": 0.12,
    "responseTypeApi": 0.25,
}
THRESHOLD = 0.70
REPEAT_MIN = 3  # MIDDLE repeatMinCount

# --- 신규 4신호 (doc/40 §3 MIDDLE 제안값) ---
NEW = {"acceptJson": 0.20, "xRequestedWith": 0.28, "originHeader": 0.15, "authScheme": 0.28}

API_HOST = re.compile(r"^(api|apis|[a-z0-9-]*-api|api-[a-z0-9-]*)\.")
VERSION_SEG = re.compile(r"v\d+")  # Java ^v\d+$ + fullmatch (대소문자 구분 — 세그먼트 raw)
MACHINE = {"healthz", "health", "status", "metrics", "ping", "livez", "readyz", "actuator"}
WRITE = {"POST", "PUT", "PATCH", "DELETE"}
ID_TOKENS = {"{id}", "{uuid}", "{token}", "{date}", "{var}"}
# static_classify_rule NAME_TOKEN (운영 DB=기본값 확인) — EndpointKindClassifier.DEFAULT_NAME_TOKENS
NAME_TOKENS = ["img", "image", "thumb", "thumbnail", "resize", "icon", "logo",
               "banner", "sprite", "avatar", "favicon", "css", "link", "download", "attachment"]


def segments(t):
    if not t:
        return []
    body = t[1:] if t.startswith("/") else t
    return [] if body == "" else body.split("/")


def has_api_segment(segs):
    for s in segs:
        l = s.lower()
        if l == "api" or l == "apis" or l.endswith("-api") or l.startswith("api-"):
            return True
    return False


def has_graphql_segment(segs):
    for s in segs:
        l = s.lower()
        if l in ("graphql", "rpc", "jsonrpc"):
            return True
    return False


def any_in(segs, sset):
    for s in segs:
        if s in sset or s.lower() in sset:
            return True
    return False


def has_static_name(path):
    # EndpointKindClassifier.hasStaticResourceName: 마지막 세그먼트에 '.' + name token substring.
    if not path:
        return False
    slash = path.rfind("/")
    seg = (path[slash + 1:] if slash >= 0 else path).lower()
    if "." not in seg:
        return False
    return any(tok in seg for tok in NAME_TOKENS)


def api_host(host):
    return bool(host) and API_HOST.search(host.lower()) is not None


def score_explain(host, method, pt, had_query, non_browser_ua, hits, kind, cors):
    """ApiScorer.scoreExplain(MIDDLE) 1:1 미러. (score, 발화 신호키 tuple) 반환.
    가중치 모두 0.01 배수 → round-to-3 은 정확(부동소수 반올림 모호성 없음)."""
    segs = segments(pt)
    raw = 0.0
    fired = []
    fires = [
        ("hostApiSubdomain", api_host(host)),
        ("corsPreflight", cors),
        ("apiSegment", has_api_segment(segs)),
        ("graphqlSegment", has_graphql_segment(segs)),
        ("versionSegment", any(VERSION_SEG.fullmatch(s) for s in segs)),
        ("pathIdSegment", any_in(segs, ID_TOKENS)),
        ("machineEndpoint", any_in(segs, MACHINE)),
        ("writeMethod", (method or "").upper() in WRITE),
        ("query", had_query),
        ("nonBrowserUa", non_browser_ua),
        ("repeatBonus", hits >= REPEAT_MIN),
        ("staticAssetPenalty", kind == "STATIC" or has_static_name(pt)),
        ("responseTypeApi", kind == "API_CANDIDATE"),
    ]
    for key, hit in fires:
        if hit:
            raw += W[key]
            fired.append(key)
    return max(0.0, min(1.0, round(raw, 3))), tuple(fired)


def score(host, method, pt, had_query, non_browser_ua, hits, kind, cors):
    return score_explain(host, method, pt, had_query, non_browser_ua, hits, kind, cors)[0]


def gate(host, method, pt, kind, cors, sc):
    """ApiScorer.evaluate 게이트 (NONE hints). exclude/apiHint = NONE 이므로 미적용."""
    if pt and len(pt) > 2048:
        return "DROP_OVERSIZE"
    if kind == "STATIC":
        return "DROP_STATIC"
    if kind == "WEB_PAGE" and (method or "").upper() in WRITE and not (cors or api_host(host)):
        return "DROP_WEB_FORM"
    return "ADMIT" if sc >= THRESHOLD else "DROP_LOW_SCORE"


# --------------------------- 자체검증 ---------------------------
def selftest():
    opts = set()
    # 1) api 서브도메인 + apiSegment + version + write + query + cors → 상한 근처
    s = score("api.x.com", "POST", "/api/v1/users", True, False, 10, "WEB_PAGE", True)
    # 0.40+0.30(cors)+0.55(api)+0.26(v1)+0.34(write)+0.12(query)+0.12(repeat)=2.09 → clamp 1.0
    assert s == 1.0, s
    # 2) 순수 페이지 GET, 무신호 → 0
    assert score("www.shop.com", "GET", "/about/company", False, False, 1, "WEB_PAGE", False) == 0.0
    # 3) pathId + repeat 만 → 0.15+0.12=0.27
    assert score("www.x.com", "GET", "/users/{id}/profile", False, False, 5, "WEB_PAGE", False) == 0.27
    # 4) static name 감점: /files/download.php (download 토큰 + '.') → -0.60, clamp 0
    assert score("www.x.com", "GET", "/files/download.php", False, False, 5, "WEB_PAGE", False) == 0.0
    # 5) 게이트: WEB_PAGE + write + 무강신호 → DROP_WEB_FORM
    assert gate("www.x.com", "POST", "/contact/send", "WEB_PAGE", False, 0.34) == "DROP_WEB_FORM"
    # 6) 게이트: WEB_PAGE + write + cors 강신호 → 점수 게이트로
    assert gate("www.x.com", "POST", "/contact/send", "WEB_PAGE", True, 0.64) == "DROP_LOW_SCORE"
    # 7) 게이트: STATIC → DROP_STATIC
    assert gate("www.x.com", "GET", "/a.js", "STATIC", False, 0.0) == "DROP_STATIC"
    # 8) 게이트: ADMIT
    assert gate("api.x.com", "GET", "/api/v1/x", "UNKNOWN", False, 0.70) == "ADMIT"
    # 9) version 대소문자 구분: /V2/x 의 V2 는 미발화 (Java ^v\d+$)
    assert score("www.x.com", "GET", "/V2/users", False, False, 1, "WEB_PAGE", False) == 0.0
    print("selftest OK")


# --------------------------- DB 조회 ---------------------------
def ssh_run(remote):
    r = subprocess.run(SSH + [remote], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       universal_newlines=True, timeout=120)
    if r.returncode != 0:
        sys.exit(f"ssh 실패: {r.stderr[:500]}")
    return r.stdout


def fetch_options_set():
    out = ssh_run(f"{PSQL} -At -F'\t' -c "
                  f"\"SELECT DISTINCT host, path_template FROM discovered_endpoint WHERE method='OPTIONS'\"")
    s = set()
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) == 2:
            s.add((parts[0], parts[1]))
    return s


def fetch_counts():
    out = ssh_run(f"{PSQL} -At -F',' -c \"" +
                  "SELECT 'total', count(*) FROM discovered_endpoint "
                  "UNION ALL SELECT 'static_nonopt', count(*) FROM discovered_endpoint "
                  "WHERE method<>'OPTIONS' AND endpoint_kind='STATIC' "
                  "UNION ALL SELECT 'options', count(*) FROM discovered_endpoint WHERE method='OPTIONS'"
                  "\"")
    c = {}
    for line in out.splitlines():
        k, v = line.split(",")
        c[k] = int(v)
    return c


def stream_scoring_rows():
    """non-OPTIONS·non-STATIC 행을 COPY|gzip 로 스트림(점수 대상). STATIC 은 자동 DROP 이라 카운트만 별도."""
    sql = ("COPY (SELECT host, method, path_template, had_query, non_browser_ua, hits, endpoint_kind "
           "FROM discovered_endpoint WHERE method <> 'OPTIONS' AND endpoint_kind <> 'STATIC') "
           "TO STDOUT WITH (FORMAT csv)")
    remote = f"{PSQL} -c \"{sql}\" | gzip -1"
    p = subprocess.Popen(SSH + [remote], stdout=subprocess.PIPE)
    gz = gzip.GzipFile(fileobj=p.stdout)
    reader = csv.reader(io.TextIOWrapper(gz, encoding="utf-8"))
    for row in reader:
        yield row
    p.wait()


def pct(n, d):
    return f"{100.0 * n / d:.3f}%" if d else "n/a"


def run():
    print("== doc/40 §5 시뮬레이션 — 8.3 신규 4신호 과승격 상한 (MIDDLE·threshold 0.70) ==\n")
    print("[1/3] OPTIONS sibling 집합 조회(corsPreflight self-join)...")
    opts = fetch_options_set()
    print(f"      OPTIONS distinct(host,path_template): {len(opts)}")
    counts = fetch_counts()
    print(f"      total={counts['total']}  static_nonopt={counts['static_nonopt']}  options={counts['options']}\n")

    print("[2/3] 점수 대상(non-OPTIONS·non-STATIC) 스트림·재계산...")
    buckets = Counter()
    admit_margin = Counter()      # ADMIT: floor((score-0.70)/0.05)
    low_hist = Counter()          # DROP_LOW_SCORE: 0.01 단위 점수 카운트
    band1_combo = Counter()       # 단일신호 승격 밴드[0.42,0.70) 발화 신호조합 분포
    band1_samples = {}            # 조합별 샘플 경로
    band1_has_api_struct = 0      # 밴드 중 apiSegment/graphql/version/host 등 API 구조 신호 보유 수
    API_STRUCT = {"hostApiSubdomain", "apiSegment", "graphqlSegment", "versionSegment", "corsPreflight"}
    scored = 0
    cors_fired = 0
    for row in stream_scoring_rows():
        host, method, pt, hq, nb, hits, kind = row
        hq = (hq == "t")
        nb = (nb == "t")
        hits = int(hits)
        cors = (host, pt) in opts
        if cors:
            cors_fired += 1
        sc, fired = score_explain(host, method, pt, hq, nb, hits, kind, cors)
        g = gate(host, method, pt, kind, cors, sc)
        buckets[g] += 1
        scored += 1
        if g == "ADMIT":
            admit_margin[int((sc - THRESHOLD) / 0.05)] += 1
        elif g == "DROP_LOW_SCORE":
            low_hist[round(sc, 2)] += 1
            if sc >= 0.42 - 1e-9:  # 단일신호(최대 0.28)로 승격 가능한 밴드
                combo = tuple(f for f in fired if f != "staticAssetPenalty")
                band1_combo[combo] += 1
                if combo not in band1_samples:
                    band1_samples[combo] = f"{method} {host}{pt}  (score={sc:.2f})"
                if any(f in API_STRUCT for f in fired):
                    band1_has_api_struct += 1
    buckets["DROP_STATIC"] += counts["static_nonopt"]  # 안 끌어온 STATIC 반영
    print(f"      점수 계산 행: {scored}  (cors 발화 {cors_fired})\n")

    print("[3/3] 결과\n")
    print("--- 게이트 분포 (non-OPTIONS 전체) ---")
    total_nonopt = counts["static_nonopt"] + scored
    for g in ["ADMIT", "DROP_LOW_SCORE", "DROP_WEB_FORM", "DROP_STATIC", "DROP_OVERSIZE"]:
        print(f"  {g:16} {buckets[g]:>9}  ({pct(buckets[g], total_nonopt)})")
    print(f"  {'합계':16} {total_nonopt:>9}")

    admit = buckets["ADMIT"]
    print(f"\n--- 현행 ADMIT margin 히스토그램 (score-0.70, 0.05 bin) ---  ADMIT={admit}")
    for b in sorted(admit_margin):
        lo = 0.70 + b * 0.05
        print(f"  [{lo:.2f},{lo+0.05:.2f})  {admit_margin[b]:>8}")

    low = buckets["DROP_LOW_SCORE"]
    print(f"\n--- DROP_LOW_SCORE 점수 분포 (과승격 후보 모집단) ---  총 {low}")
    # 0.05 bin 요약
    binned = Counter()
    for sc, n in low_hist.items():
        binned[int(sc / 0.05 + 1e-9)] += n
    for b in sorted(binned):
        lo = b * 0.05
        print(f"  [{lo:.2f},{lo+0.05:.2f})  {binned[b]:>8}")

    print(f"\n--- 과승격 상한 (신규 4신호 활성 시 DROP_LOW_SCORE→ADMIT 가능 최대) ---")

    def band(min_w):
        lo = THRESHOLD - min_w
        n = sum(v for sc, v in low_hist.items() if sc >= lo - 1e-9)
        return lo, n

    scenarios = [
        ("originHeader 단독 (+0.15)", 0.15),
        ("acceptJson 단독 (+0.20)", 0.20),
        ("xRequestedWith/authScheme 단독 (+0.28, 최대 단일)", 0.28),
        ("2신호 최대 (xhr+auth, +0.56)", 0.56),
        ("전 4신호 (+0.91)", 0.91),
    ]
    for name, w in scenarios:
        lo, n = band(w)
        lo = max(0.0, lo)
        print(f"  {name:42}  score≥{lo:.2f}: {n:>8}  ({pct(n, total_nonopt)} of non-OPT, {pct(n, low)} of LOW)")

    print("\n※ 위는 '점수 밴드가 허용하는' 구조적 상한 — 해당 신호가 실제 발화한다는 가정. "
          "실발화 여부는 로그 수집 후에만 확정(현재 미수집). 발화조건(다수결)은 이 상한을 바꾸지 않고 실제 승격 건수만 줄인다.")

    band1_total = sum(band1_combo.values())
    print(f"\n--- 단일신호 승격 밴드[0.42,0.70) 진단 (총 {band1_total}) ---")
    print(f"  이 중 API 구조 신호(host/api/graphql/version/cors) 보유: {band1_has_api_struct} "
          f"({pct(band1_has_api_struct, band1_total)}) — 대체로 '옳은 승격' 후보")
    print(f"  API 구조 신호 없음(약한 path/method/ua 만): {band1_total - band1_has_api_struct} "
          f"({pct(band1_total - band1_has_api_struct, band1_total)}) — '위험한 승격' 후보")
    print("  발화 신호조합 top 12 (staticAssetPenalty 제외 표기):")
    for combo, n in band1_combo.most_common(12):
        print(f"    {n:>7}  {'+'.join(combo) or '(무)'}")
        print(f"            예: {band1_samples[combo]}")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    else:
        selftest()
        run()
