#!/bin/sh
echo '=== prometheus-am0 挂载与启动参数 ==='
docker inspect prometheus-am0 --format '{{json .Mounts}}' | head -c 800
echo ''
docker inspect prometheus-am0 --format '{{.Args}}'
echo '=== 现有 config 中 service 相关（relabel 情况） ==='
docker exec prometheus-am0 sh -c 'grep -n "service" /etc/prometheus/prometheus.yml 2>/dev/null | head -10; echo ---; grep -n "metric_relabel" /etc/prometheus/prometheus.yml | head -5; echo ---; sed -n "1,40p" /etc/prometheus/prometheus.yml'
exit 0
