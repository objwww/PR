#!/bin/sh
# rr-cols.sh —— 钉 rca_tool_invocation / rca_evidence / rca_task 真实列名
docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select table_name||' :: '||string_agg(column_name,', ' order by ordinal_position) from information_schema.columns where table_name in ('rca_tool_invocation','rca_evidence','rca_task','rca_report') group by table_name"
echo "== 样例行（末轮 RR21 run 的工具调用）=="
docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select * from rca_tool_invocation where run_id='6670387c-d935-4da2-92d8-4a07c4f695b7' limit 1"
