#!/bin/sh
J=/tmp/p316.cookie
for ep in summary workers action-assessment latency-layers costs risk-events; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -b $J "http://127.0.0.1:8080/api/agent-ops/$ep")
  echo "$ep -> $code"
done
echo '--- costs verbose 头部:'
curl -s -D - -o /dev/null -b $J "http://127.0.0.1:8080/api/agent-ops/costs" | head -8
echo '--- 容器内同一 jar 的映射核查:'
docker logs deploy-control-app-1 --since 10m 2>&1 | grep -iE 'Mapped|mapping' | grep -i 'costs' || echo '(无 Mapped 日志，正常)'
