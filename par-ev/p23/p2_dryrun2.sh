#!/bin/sh
# P2: 用乘法形式验证三条最终表达式（后缀字面量已在 02-04/08 证伪）
OUT=/tmp/p2_dryrun2.txt
RAW=/tmp/p2_dryrun_json
: > "$OUT"
q() {
  NAME="$1"; QUERY="$2"
  {
  echo "===== Q: $NAME ====="
  echo "query: $QUERY"
  curl -s -G 'http://127.0.0.1:9090/api/v1/query' --data-urlencode "query=$QUERY" > "$RAW/$NAME.json"
  cat "$RAW/$NAME.json"
  echo
  echo
  } >> "$OUT" 2>&1
}

q 11_final_tier1 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 1.2*1024*1024*1024'
q 12_final_tier2 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 768*1024*1024'
q 13_final_tier3 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 512*1024*1024 or on(instance) (ALERTS{alertname="MemoryGateTier3",alertstate="firing"} == 1 and on(instance) node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 1536*1024*1024)'
q 14_tier3_positive_probe_mult 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 100*1024*1024*1024'
q 15_labels_of_series 'node_memory_MemAvailable_bytes{job="node-exporter-host1"}'
echo "DRYRUN2_DONE"
