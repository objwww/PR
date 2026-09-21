#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== 模型调用表的模型名与列 ==='
Q "select column_name from information_schema.columns where table_name='rca_model_call' order by ordinal_position" | tr '\n' ' '
echo
echo '=== 在用模型与 token 量 ==='
Q "select coalesce(payload->>'model','?')||' | calls='||count(*)||' | tokens='||coalesce(sum(nullif(payload->>'total_tokens','')::bigint),0) from rca_model_call group by 1 limit 5"
echo '=== cost 现状 ==='
Q "select count(*) filter (where cost_micros is not null) as with_cost, count(*) as total from rca_model_call" 2>/dev/null || echo "no cost_micros col"
