#!/bin/sh
echo '=== 近 5m 按 service_name 分布 ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=8 'http://localhost:9090/api/v1/query?query=topk(10,count%20by(service_name)(%7Bservice_name%3D~%22.%2B%22%7D))' 2>/dev/null" | head -c 900
echo ''
echo '=== checkout 关键词序列名（任意时间 label 查询） ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=8 'http://localhost:9090/api/v1/label/__name__/values/match[]=%7Bservice%3D%22checkout%22%7D' 2>/dev/null" | head -c 600
echo ''
echo '=== prometheus 抓配置（静态） ==='
docker exec prometheus-am0 sh -c 'wget -qO- --timeout=5 http://localhost:9090/api/v1/status/config 2>/dev/null' | grep -oE '[a-z_0-9]+:9[0-9]{3}' | sort -u | head -15
echo '=== collector 自身 metrics 端口直查（checkout 抓取状态） ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=5 'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22otel-collector%22%7D' 2>/dev/null" | grep -oE '"exported_instance":"[^"]*"' | head -20
exit 0
