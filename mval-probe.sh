#!/bin/sh
# 模拟 metric_value 查询（URL 外层预编码，docker exec -e 传参免引号地狱）
NOW=$(date +%s)
probe() {
  Q="$1"
  ENC=$(printf '%s' "${Q}{service=\"checkout\"}" | od -An -tx1 | tr ' ' '\n' | grep -v '^$' | sed 's/^/\\x/' | tr -d '\n')
  # 逐字符百分号编码（仅元字符）：手工拼
  ENC=$(printf '%s' "${Q}" '%7Bservice%3D%22checkout%22%7D')
  U="http://prometheus:9090/api/v1/query?query=${ENC}&time=${NOW}"
  echo "== $Q"
  docker exec -e URL="$U" deploy-control-app-1 sh -c 'wget -q -O- "$URL"' | head -c 220
  echo
}
probe "arena_traffic_rejected_total"
probe "http_server_request_duration_seconds_count"
