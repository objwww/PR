#!/bin/sh
echo '=== control-app 的 prometheus/loki 基址（非密钥） ==='
docker exec deploy-control-app-1 env | grep -E 'PROMETHEUS|LOKI' | sed 's/=$/=<empty>/'
echo '=== prometheus-am0 网络别名 ==='
docker inspect prometheus-am0 --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
docker inspect deploy-control-app-1 --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
echo '=== 从 control-app 网内直查 checkout（与工具同视角） ==='
docker exec deploy-control-app-1 sh -c "wget -qO- --timeout=8 'http://prometheus:9090/api/v1/query?query=count(%7Bservice%3D%22checkout%22%7D)' 2>/dev/null" | head -c 200
echo ''
echo '=== query_range 形状探针（与模型调用同形） ==='
docker exec deploy-control-app-1 sh -c "wget -qO- --timeout=8 'http://prometheus:9090/api/v1/query_range?query=count%28%7Bservice%3D%22checkout%22%7D%29&start=1789211700&end=1789212300&step=60' 2>/dev/null" | head -c 250
echo ''
exit 0
