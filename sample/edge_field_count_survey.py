# 엣지(Loki hostname 스트림)별 access log 필드 수 전수 조사 — 8.3 활성화 대상 선별·매뉴얼 §8.5 표 데이터.
# ★운영 Loki 부하 극소: 엣지 목록=label_values 1쿼리, 엣지당 최근 1줄만(limit 1), 순차·스로틀·백오프. 우회 금지.
# EdgeExclusions(D62/D69: AAJ 정확목록 + 'P*' 접두) 스킵. 결과=필드수별 엣지명 전수 목록.
# 실행: python3 sample/edge_field_count_survey.py [out.txt]

import requests
import sys
import time
from datetime import datetime, timedelta, timezone
from collections import defaultdict, Counter

LOKI = "http://192.168.8.100:3200"
JOB = "access_log"
DELIM = "^|^"

# --- 부하 보호 (LokiClient 준수) ---
THROTTLE_S = 0.25       # 쿼리 간 최소 간격(>= min-query-interval 200ms)
LINE_WINDOW_H = 1       # 엣지당 조회 창(활성 엣지는 1h 내 로그 보장 — label_values 1h 로 선별)
FALLBACK_H = 6          # 1h 에 라인 없으면 1회 확대
REQ_TIMEOUT = 20
MAX_RETRY = 3           # 429/5xx/timeout 백오프 재시도

# --- EdgeExclusions (application.yml discovery.excluded-hostnames = AAJ11..AAJ63 + "P*") ---
EXCLUDE_EXACT = set(
    "AAJ11 AAJ12 AAJ13 AAJ14 AAJ21 AAJ22 AAJ23 AAJ24 AAJ31 AAJ32 AAJ33 AAJ34 "
    "AAJ41 AAJ42 AAJ43 AAJ44 AAJ51 AAJ52 AAJ53 AAJ54 AAJ61 AAJ62 AAJ63".split())
EXCLUDE_PREFIX = ["P"]  # 'P*'


def excluded(h):
    return h in EXCLUDE_EXACT or any(h.startswith(p) for p in EXCLUDE_PREFIX)


def ns(dt):
    return str(int(dt.timestamp() * 1e9))


def get_edges():
    now = datetime.now(timezone.utc)
    p = {"start": ns(now - timedelta(hours=1)), "end": ns(now), "query": '{job="%s"}' % JOB}
    r = requests.get(LOKI + "/loki/api/v1/label/hostname/values", params=p, timeout=30)
    r.raise_for_status()
    return sorted(r.json().get("data", []))


def fetch_one_line(edge, hours):
    """엣지 최근 1줄(limit 1, backward). 백오프 재시도. 반환: 라인 문자열 or None."""
    now = datetime.now(timezone.utc)
    p = {"query": '{job="%s", hostname="%s"}' % (JOB, edge),
         "start": ns(now - timedelta(hours=hours)), "end": ns(now),
         "limit": 1, "direction": "backward"}
    for attempt in range(MAX_RETRY):
        try:
            r = requests.get(LOKI + "/loki/api/v1/query_range", params=p, timeout=REQ_TIMEOUT)
            if r.status_code == 429 or r.status_code >= 500:
                raise IOError("HTTP %d" % r.status_code)  # 백오프 대상
            r.raise_for_status()
            for stream in r.json().get("data", {}).get("result", []):
                for _ts, line in stream["values"]:
                    return line
            return None  # 라인 없음
        except (requests.exceptions.Timeout, IOError, requests.exceptions.RequestException) as e:
            if attempt == MAX_RETRY - 1:
                return "__ERROR__:%s" % str(e)[:60]
            time.sleep(1.0 * (2 ** attempt))  # 1s,2s,4s 백오프
    return None


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else None
    edges = get_edges()
    targets = [h for h in edges if not excluded(h)]
    skipped = [h for h in edges if excluded(h)]
    print("총 엣지: %d, 제외(AAJ+P*): %d, 대상: %d\n" % (len(edges), len(skipped), len(targets)))

    by_count = defaultdict(list)   # 필드수 -> [엣지…]
    no_line, errors = [], []
    for i, edge in enumerate(targets, 1):
        line = fetch_one_line(edge, LINE_WINDOW_H)
        if line is None:
            line = fetch_one_line(edge, FALLBACK_H)  # 창 확대 1회
        if line is None:
            no_line.append(edge)
        elif isinstance(line, str) and line.startswith("__ERROR__:"):
            errors.append(edge)
        else:
            by_count[len(line.split(DELIM))].append(edge)
        if i % 20 == 0 or i == len(targets):
            print("  진행 %d/%d …" % (i, len(targets)))
        time.sleep(THROTTLE_S)

    # --- 결과 표 ---
    lines = []
    lines.append("=== 엣지별 access log 필드 수 전수 조사 (%s UTC) ===" % datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M"))
    lines.append("대상 %d 엣지 (제외 %d = AAJ 정확 + P* 접두). 엣지당 최근 1줄 표본.\n" % (len(targets), len(skipped)))
    lines.append("%-8s %-6s %s" % ("필드수", "엣지수", "엣지 목록"))
    lines.append("-" * 70)
    for fc in sorted(by_count):
        edges_fc = sorted(by_count[fc])
        lines.append("%-8d %-6d %s" % (fc, len(edges_fc), ", ".join(edges_fc)))
    if no_line:
        lines.append("%-8s %-6d %s" % ("(라인없음)", len(no_line), ", ".join(sorted(no_line))))
    if errors:
        lines.append("%-8s %-6d %s" % ("(오류)", len(errors), ", ".join(sorted(errors))))
    lines.append("")
    lines.append("요약: " + " · ".join("%d필드=%d" % (fc, len(by_count[fc])) for fc in sorted(by_count))
                 + (" · 라인없음=%d" % len(no_line) if no_line else "")
                 + (" · 오류=%d" % len(errors) if errors else ""))
    lines.append("한계: 엣지당 1줄 표본(부하 극소) — 동일 엣지 내 vhost별 포맷 혼재 가능성은 미탐지.")
    report = "\n".join(lines)
    print("\n" + report)
    if out_path:
        with open(out_path, "w") as f:
            f.write(report + "\n")
        print("\n결과 저장: %s" % out_path)


if __name__ == "__main__":
    main()
