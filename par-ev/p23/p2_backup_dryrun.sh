#!/bin/sh
# P2: 备份 rules 目录 + Prometheus API dry-run（阈值字面量/乘法回退/滞回表达式语法）
OUT=/tmp/p2_dryrun.txt
RAW=/tmp/p2_dryrun_json
: > "$OUT"
mkdir -p "$RAW"

{
echo "===== [B1] backup tar ====="
tar czf /root/prom-rules-backup-20260909.tgz -C /opt/build/pr/deploy/alert/prometheus rules
ls -la /root/prom-rules-backup-20260909.tgz
echo "--- sha256 ---"
sha256sum /root/prom-rules-backup-20260909.tgz
echo "--- tar content ---"
tar tzf /root/prom-rules-backup-20260909.tgz
} >> "$OUT" 2>&1

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

q 01_memavail_true 'node_memory_MemAvailable_bytes{job="node-exporter-host1"}'
q 02_suffix_1_2Gi 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 1.2Gi'
q 03_suffix_768Mi 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 768Mi'
q 04_suffix_512Mi 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 512Mi'
q 05_mult_1_2Gi 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 1.2*1024*1024*1024'
q 06_mult_768Mi 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 768*1024*1024'
q 07_mult_512Mi 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 512*1024*1024'
q 08_suffix_100Gi_positive 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 100Gi'
q 09_tier3_full_expr_with_ALERTS 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 512Mi or on(instance) (ALERTS{alertname="MemoryGateTier3",alertstate="firing"} == 1 and on(instance) node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 1536Mi)'
q 10_tier3_recovery_probe 'node_memory_MemAvailable_bytes{job="node-exporter-host1"} < 1536Mi'
echo "DRYRUN_DONE"
