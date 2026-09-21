#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '--- 24h 内 usage->total_tokens 非数字行:'
P "select id, usage, usage_missing from rca_model_call where created_at >= now() - interval '24 hours' and (usage->>'total_tokens' is null or usage->>'total_tokens' !~ '^[0-9]+$') limit 5;"
echo '--- 复现 cast 全窗聚合:'
P "select sum((usage->>'total_tokens')::long) from rca_model_call where created_at >= now() - interval '24 hours' and cost_micros is not null;"
