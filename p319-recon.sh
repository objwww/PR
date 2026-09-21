#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '--- 当前窗（24h）:'
P "select count(*), sum(cost_micros), count(*) filter (where state = 'FAILED'), round(avg(latency_ms)) from rca_model_call where created_at >= now() - interval '24 hours';"
echo '--- 上一窗（24h~48h）:'
P "select count(*), sum(cost_micros), count(*) filter (where state = 'FAILED'), round(avg(latency_ms)) from rca_model_call where created_at >= now() - interval '48 hours' and created_at < now() - interval '24 hours';"
J=/tmp/p319.cookie
echo '--- 端点实测:'
curl -s -b $J "http://127.0.0.1:8080/api/agent-ops/perf-trend"
echo
