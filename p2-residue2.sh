#!/bin/sh
echo '--- checkout 告警原文（含 value/activeAt）---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/alerts'" | tr ',' '\n' | grep -E '"alertname":"checkout"|"activeAt"|"value"' | head -12
echo '--- checkout 规则的表达式 ---'
docker exec prometheus-am0 sh -c "grep -B 8 -A 12 'alert: checkout' /etc/prometheus/rules/*.yml | head -30"
echo '--- 最近10分钟 checkout 错误/可用性指标 ---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=up%7Bjob%3D%22checkout%22%7D' | head -c 200" || true
echo
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/label/__name__/values' | tr ',' '\n' | grep -i checkout | head -8"
