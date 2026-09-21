#!/bin/sh
# WC-5 指标真机核验（第 2 路）：otelcol-control prometheus exporter :9465
echo '--- collector containers ---'
docker ps --format '{{.Names}} {{.Ports}}' | grep -i otel
echo '--- 9465 rca_* meters ---'
docker exec deploy-otelcol-control-1 wget -qO- http://127.0.0.1:9465/metrics 2>/dev/null | grep -E '^rca_(reconcile|late_commit|cancel_to_quiesce)' | head -40
echo '--- fallback: host curl mapped port ---'
HOSTPORT=$(docker port deploy-otelcol-control-1 2>/dev/null | grep 9465 | head -1)
echo "port_map=$HOSTPORT"
if [ -n "$HOSTPORT" ]; then
  curl -s "http://127.0.0.1:${HOSTPORT##*:}/metrics" | grep -E '^rca_(reconcile|late_commit|cancel_to_quiesce)' | head -40
fi
exit 0
