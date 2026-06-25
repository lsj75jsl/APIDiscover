#!/usr/bin/env bash
# DB(예: postgres) TCP 준비 대기 후 앱 기동 — 컨테이너 동시 기동(pod) 시 DB 미준비로 인한 기동 실패→재시작 폭주(크래시 루프) 방지 (doc/32).
# bash /dev/tcp 사용(런타임 이미지 eclipse-temurin:21-jre 에 bash 존재, nc 등 추가 의존 불요).
# 무인자=서버 모드, --adc.cli.export-domain= 인자=CLI 모드 — "$@" 로 그대로 전달(ENTRYPOINT 계약 보존).
HOST="${DB_WAIT_HOST:-localhost}"
PORT="${DB_WAIT_PORT:-5432}"
RETRIES="${DB_WAIT_RETRIES:-60}"      # 최대 대기 = RETRIES * INTERVAL (기본 120s)
INTERVAL="${DB_WAIT_INTERVAL:-2}"

echo "wait-for-db: ${HOST}:${PORT} 대기 (retries=${RETRIES}, interval=${INTERVAL}s)"
i=0
while ! (echo > "/dev/tcp/${HOST}/${PORT}") 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -ge "$RETRIES" ]; then
    echo "wait-for-db: ${HOST}:${PORT} 미준비 — $((RETRIES * INTERVAL))s 경과, 그대로 기동 시도(실패 시 컨테이너 재시작 정책에 위임)" >&2
    break
  fi
  sleep "$INTERVAL"
done
echo "wait-for-db: DB 도달(또는 타임아웃) → 앱 기동"
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
