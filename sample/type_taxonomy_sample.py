# 운영 Loki 에서 $type taxonomy 를 부하보호 준수로 샘플링·교차집계 (doc/21 §A)
import requests
import sys
from datetime import datetime, timedelta
from collections import Counter, defaultdict
import pytz

KST = pytz.timezone("Asia/Seoul")
LOKI_URL = "http://192.168.8.100:3200/loki/api/v1/query_range"

# 부하 보호 상한 (D7/운영주의, doc/21 §A) — 절대 우회 금지
MAX_LIMIT = 2000          # limit=1e8 금지
MAX_WINDOW_MIN = 10       # 창 ≤ 10분
MAX_PAGES = 2             # 페이지 1~2개

# 필드 인덱스 (LogLineParser 와 동일)
F_REQUEST = 5   # "GET /path HTTP/1.1" → method = 첫 토큰
F_STATUS = 9
F_TYPE = 19


def status_class(s):
    try:
        n = int(s.strip())
    except (ValueError, AttributeError):
        return "??"
    return f"{n // 100}xx"


def fetch_window(hostname, domain, start_kst, window_min, limit, max_pages):
    """단일 (host,domain) 윈도우를 forward 페이지네이션(≤max_pages)으로 조회. raw 라인 리스트 반환."""
    assert limit <= MAX_LIMIT, f"limit {limit} > {MAX_LIMIT} (부하보호 위반)"
    assert window_min <= MAX_WINDOW_MIN, f"window {window_min} > {MAX_WINDOW_MIN}분 (부하보호 위반)"
    assert max_pages <= MAX_PAGES, f"pages {max_pages} > {MAX_PAGES} (부하보호 위반)"

    end_kst = start_kst + timedelta(minutes=window_min)
    start_ns = int(start_kst.astimezone(pytz.utc).timestamp() * 1e9)
    end_ns = int(end_kst.astimezone(pytz.utc).timestamp() * 1e9)
    query = f'{{job="access_log", hostname="{hostname}"}} |= `{domain}`'

    lines = []
    cur_start = start_ns
    for page in range(max_pages):
        params = {
            "query": query,
            "start": str(cur_start),
            "end": str(end_ns),
            "limit": limit,
            "direction": "forward",
        }
        r = requests.get(LOKI_URL, params=params, timeout=30)
        r.raise_for_status()
        data = r.json()
        page_entries = []
        max_ts = cur_start
        for stream in data.get("data", {}).get("result", []):
            for ts, log_line in stream["values"]:
                page_entries.append((int(ts), log_line))
                if int(ts) > max_ts:
                    max_ts = int(ts)
        page_entries.sort(key=lambda x: x[0])
        lines.extend(log_line for _, log_line in page_entries)
        print(f"    [page {page+1}] {len(page_entries)} lines "
              f"({datetime.fromtimestamp(cur_start/1e9, KST):%H:%M:%S}~)")
        # 다음 페이지: 마지막 timestamp 직후부터. 페이지가 limit 미만이면 더 없음 → 중단
        if len(page_entries) < limit or page + 1 >= max_pages:
            break
        cur_start = max_ts + 1
    return lines


def aggregate(lines):
    """raw 라인 → $type 별 (count, status-class 분포, method 분포, 예시 path) 집계."""
    type_count = Counter()
    type_status = defaultdict(Counter)
    type_method = defaultdict(Counter)
    type_examples = defaultdict(list)
    skipped = 0

    for line in lines:
        f = line.split("^|^")
        if len(f) <= F_TYPE:
            skipped += 1
            continue
        t = f[F_TYPE].strip()
        if t == "" or t == "-":
            t = "(empty)"
        req = f[F_REQUEST].strip()
        method = req.split(" ", 1)[0] if req else "?"
        sc = status_class(f[F_STATUS])
        # 예시 path: request_uri(index 8) 의 path 부분
        path = f[8].split("?", 1)[0].strip() if len(f) > 8 else "?"

        type_count[t] += 1
        type_status[t][sc] += 1
        type_method[t][method] += 1
        if len(type_examples[t]) < 5 and path not in type_examples[t]:
            type_examples[t].append(path)
    return type_count, type_status, type_method, type_examples, skipped


def print_report(label, lines):
    tc, ts, tm, tex, skipped = aggregate(lines)
    total = sum(tc.values())
    print(f"\n=== {label} ===")
    print(f"총 파싱 라인: {total} (skip {skipped})")
    if total == 0:
        return tc
    print(f"distinct $type: {len(tc)}")
    for t, c in tc.most_common():
        pct = 100.0 * c / total
        sc_str = " ".join(f"{k}={v}" for k, v in sorted(ts[t].items()))
        m_str = " ".join(f"{k}={v}" for k, v in tm[t].most_common())
        ex = ", ".join(tex[t][:3])
        print(f"  {t:<14} count={c:<5} {pct:5.1f}%  [{sc_str}]  [{m_str}]  ex: {ex}")
    return tc


if __name__ == "__main__":
    # 인수: hostname domain start(YYYY-MM-DD HH:MM) [window_min] [limit] [pages]
    hostname = sys.argv[1]
    domain = sys.argv[2]
    start_str = sys.argv[3]
    window_min = int(sys.argv[4]) if len(sys.argv) > 4 else 10
    limit = int(sys.argv[5]) if len(sys.argv) > 5 else 2000
    pages = int(sys.argv[6]) if len(sys.argv) > 6 else 1

    start_kst = KST.localize(datetime.strptime(start_str, "%Y-%m-%d %H:%M"))
    print(f"[{hostname} / {domain}] start={start_str} KST window={window_min}m limit={limit} pages={pages} dir=forward")
    lines = fetch_window(hostname, domain, start_kst, window_min, limit, pages)
    print_report(f"{hostname} / {domain}", lines)
