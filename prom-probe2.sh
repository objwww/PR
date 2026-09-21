#!/bin/sh
# 查 service=checkout 序列是否已回流（指标名任意）
docker exec deploy-control-app-1 sh -c 'wget -q -O- --post-data="query=count({service=\"checkout\"})" http://prometheus:9090/api/v1/query 2>/dev/null'
echo
echo ---names---
docker exec deploy-control-app-1 sh -c 'wget -q -O- http://prometheus:9090/api/v1/label/__name__/values 2>/dev/null' | grep -oE '"(http|rpc|db|otel)[a-z_]*"' | sort -u | head -8
