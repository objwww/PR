#!/bin/sh
# 指标生产者面：全容器清单 + prometheus 抓取目标状态
docker ps -a --format '{{.Names}}|{{.Status}}' | grep -viE 'buildkit|compose' | head -30
echo '=== prometheus targets（down 的） ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=5 'http://localhost:9090/api/v1/targets?state=down' 2>/dev/null" | grep -oE '"scrapeUrl":"[^"]*"' | head -10
echo '=== targets 汇总 ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=5 'http://localhost:9090/api/v1/targets' 2>/dev/null" | grep -oE '"health":"[a-z]+"' | sort | uniq -c
echo '=== 近 1h 是否有任何 up=1 序列 ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=5 'http://localhost:9090/api/v1/query?query=count(up%3D%3D1)' 2>/dev/null" | head -c 200
echo ''
exit 0
