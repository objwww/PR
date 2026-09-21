#!/bin/sh
# 取证源健康面：prometheus/loki 容器与 checkout 指标
docker ps -a --format '{{.Names}} {{.Status}}' | grep -iE 'prom|loki|grafana'
echo '=== prometheus API（容器网内） ==='
docker exec deploy-control-app-1 sh -c 'wget -qO- --timeout=5 http://prometheus:9090/-/healthy 2>/dev/null || echo PROM-UNREACHABLE'
echo '=== loki API ==='
docker exec deploy-control-app-1 sh -c 'wget -qO- --timeout=5 http://loki:3100/ready 2>/dev/null || echo LOKI-UNREACHABLE'
echo '=== checkout 指标查询（up） ==='
docker exec deploy-control-app-1 sh -c "wget -qO- --timeout=5 'http://prometheus:9090/api/v1/query?query=up%7Bservice%3D%22checkout%22%7D' 2>/dev/null" | head -c 300
echo ''
exit 0
