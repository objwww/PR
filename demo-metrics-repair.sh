#!/bin/sh
# demo 指标管道修复（重启 otel-collector；留证）+ 恢复验证
echo '=== restart otel-collector ==='
docker restart otel-collector
sleep 45
echo '=== targets 汇总（重启后） ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=5 'http://localhost:9090/api/v1/targets' 2>/dev/null" | grep -oE '"health":"[a-z]+"' | sort | uniq -c
echo '=== 近 2m demo 序列（重启后 top） ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=8 'http://localhost:9090/api/v1/query?query=topk(8,count%20by(service)(%7Bservice%3D~%22.%2B%22%7D))' 2>/dev/null" | head -c 800
echo ''
echo '=== checkout 专项 ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=8 'http://localhost:9090/api/v1/query?query=count(%7Bservice%3D%22checkout%22%7D)' 2>/dev/null" | head -c 200
echo ''
exit 0
