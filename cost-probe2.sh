#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== 模型与调用量 ==='
Q "select coalesce(requested_model,'?')||' | '||count(*) from rca_model_call group by 1"
echo '=== usage 键样例 ==='
Q "select usage::text from rca_model_call where usage is not null limit 1"
echo '=== state 分布 ==='
Q "select state||' | '||count(*) from rca_model_call group by 1"
