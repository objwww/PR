#!/bin/sh
# v9 终态证据落盘：v9 窗所有 run 的模型/工具链摘要 + 决策终态
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
OUT=/opt/build/pr-logs/rr2224/v9-final-db-evidence.txt
{
echo "=== v9 窗（13:35 UTC 后）全部 run ==="
$PG "select r.id, r.state, r.created_at, r.finished_at from rca_run r where r.created_at > '2026-09-13 13:35+00' order by r.created_at"
echo ""
echo "=== 逐 run 模型调用链 ==="
$PG "select run_id, state, coalesce(error_code,'-'), created_at from rca_model_call where created_at > '2026-09-13 13:35+00' order by created_at"
echo ""
echo "=== 逐 run 工具调用链 ==="
$PG "select run_id, tool_name, state, coalesce(reason_code,'-') from rca_tool_invocation where started_at > '2026-09-13 13:35+00' order by started_at"
echo ""
echo "=== v9 窗路由决策 ==="
$PG "select stickiness_key, decision, count(*), min(created_at) from canary_route_decision where created_at > '2026-09-13 13:35+00' group by stickiness_key, decision order by min(created_at)"
echo ""
echo "=== v9 窗 logs 源 evidence（应为 0=零伪造）==="
$PG "select count(*) from rca_evidence where created_at > '2026-09-13 13:35+00' and (source ilike '%logs%' or source ilike '%loki%')"
} > "$OUT" 2>&1
wc -l "$OUT"
