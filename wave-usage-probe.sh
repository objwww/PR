#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== rca_model_call.usage 样本 ==='
Q "select state||' | usage='||coalesce(usage::text,'null')||' | latency='||coalesce(latency_ms::text,'-')||' | model='||coalesce(requested_model,'-') from rca_model_call where created_at > now() - interval '24 hours' limit 3"
echo '=== 24h 计数与 token 合计（目标口径） ==='
Q "select 'calls='||count(*)||' tokens='||coalesce(sum(nullif(usage->>'total_tokens','')::bigint),0) from rca_model_call where created_at > now() - interval '24 hours'"
echo '=== 工具 TopN（目标口径） ==='
Q "select tool_name||' x'||count(*) from rca_tool_invocation where started_at > now() - interval '24 hours' group by tool_name order by count(*) desc limit 5"
exit 0
