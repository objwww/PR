#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
R=82cf4cbf-44fd-4ea0-9184-84115a53e9f0
echo '=== rca_claim 行数 ==='
Q "select count(*) from rca_claim where run_id='$R'"
echo '=== rca_claim 样例（3 行） ==='
Q "select kind||' | '||status||' | '||left(coalesce(reason,''),60) from rca_claim where run_id='$R' limit 3"
echo '=== rca_tool_invocation 行数 ==='
Q "select count(*) from rca_tool_invocation where run_id='$R'"
echo '=== tool_invocation 样例 ==='
Q "select tool_name||' | '||state||' | '||left(coalesce(argument_digest,argument_summary,''),40) from rca_tool_invocation where run_id='$R' limit 3"
echo '=== investigation_result ==='
Q "select count(*) from investigation_result where run_id='$R'"
Q "select left(coalesce(summary,''),120) from investigation_result where run_id='$R'"
echo '=== 全库有 claims 的 run 比例 ==='
Q "select count(distinct run_id) from rca_claim"
exit 0
