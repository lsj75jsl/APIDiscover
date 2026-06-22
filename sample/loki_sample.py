import requests
import json
import pytz
from datetime import datetime, timedelta
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock

# 파일 쓰기 동기화를 위한 Lock
write_lock = Lock()

KST = pytz.timezone("Asia/Seoul")

def parse_datetime(datetime_str):
    try:
        parsed_time = datetime.strptime(datetime_str, "%Y-%m-%d %H")
        return parsed_time.strftime("%Y-%m-%d %H:00:00")
    except ValueError:
        raise ValueError("Date Form must be 'YYYY-MM-DD HH' (ex 2025-02-14 15)")

def fetch_logs_for_hostname(hostname, domain, start_timestamp, end_timestamp):
    loki_url = "http://192.168.8.100:3200/loki/api/v1/query_range"
    query = f'{{job="access_log", hostname="{hostname}"}} |= `{domain}`'

    params = {
        "query": query,
        "start": str(start_timestamp),
        "end": str(end_timestamp),
        "limit": 100000000
    }

    try:
        response = requests.get(loki_url, params=params)
        response.raise_for_status()
        data = response.json()
        logs = []

        for stream in data.get("data", {}).get("result", []):
            for entry in stream["values"]:
                timestamp, log_line = entry
                dt = datetime.fromtimestamp(int(timestamp) / 1e9, tz=pytz.utc).astimezone(KST)
                formatted_time = dt.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
                logs.append((formatted_time, log_line))
        return logs
    except Exception as e:
        print(f"[{hostname}] Error! : {e}")
        return []

def daterange(start_date, end_date):
    current = start_date
    while current <= end_date:
        yield current
        current += timedelta(days=1)

def export_logs_to_file(start_time_str, end_time_str, hostnames_str, domain):
    start_process_time = time.time()
    print(f"Job start time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")

    try:
        start_time = datetime.strptime(parse_datetime(start_time_str), "%Y-%m-%d %H:%M:%S")
        end_time = datetime.strptime(parse_datetime(end_time_str), "%Y-%m-%d %H:%M:%S")
        start_time = KST.localize(start_time)
        end_time = KST.localize(end_time)
        print(f"Search start time: {start_time}")
        print(f"Search end time: {end_time}")
    except ValueError as e:
        print(f"시간 형식 오류: {e}")
        return

    hostnames = [h.strip() for h in hostnames_str.split(',')]

    for day in daterange(start_time.date(), end_time.date()):
        day_start_kst = KST.localize(datetime.combine(day, datetime.min.time()))
        day_end_kst = KST.localize(datetime.combine(day, datetime.max.time()))

        day_range_start = max(start_time, day_start_kst)
        day_range_end = min(end_time, day_end_kst)

        start_ts = int(day_range_start.astimezone(pytz.utc).timestamp() * 1e9)
        end_ts = int(day_range_end.astimezone(pytz.utc).timestamp() * 1e9)

        output_file = f"{domain}_{day.strftime('%Y-%m-%d')}_{hostnames_str}.log"

        all_logs = []
        with ThreadPoolExecutor(max_workers=len(hostnames)) as executor:
            future_to_hostname = {
                executor.submit(fetch_logs_for_hostname, hostname, domain, start_ts, end_ts): hostname
                for hostname in hostnames
            }

            for future in as_completed(future_to_hostname):
                hostname = future_to_hostname[future]
                try:
                    logs = future.result()
                    if logs:
                        all_logs.extend(logs)
                        print(f"[{hostname}] logs collected")
                    else:
                        print(f"[{hostname}] Nothing log")
                except Exception as exc:
                    print(f"[{hostname}] Exception: {exc}")

        # 시간순 정렬
        all_logs.sort(key=lambda x: x[0])

        with open(output_file, "w", encoding="utf-8") as f:
            for timestamp, log_line in all_logs:
                f.write(f"{timestamp} {log_line}\n")

    print(f"Complete {domain} log export.")
    print(f"Job end time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"Process time: {time.time() - start_process_time:.2f}sec")

def main():
    start_time_str = input('Start time (YYYY-MM-DD HH): ')
    end_time_str = input('End time (YYYY-MM-DD HH): ')
    hostnames_str = input('AFC/PRON name (ex AAI11,PAI11,PAI21): ')
    domain = input('Target Domain: ')

    export_logs_to_file(start_time_str, end_time_str, hostnames_str, domain)

if __name__ == "__main__":
    main()