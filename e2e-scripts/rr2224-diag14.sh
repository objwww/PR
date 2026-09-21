#!/bin/sh
# v7 RR23 取证：LDEAD/LEMPTY run 的工具链与模型链
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===LDEAD-TOOLS===
$PG "select tool_name, state, reason_code from rca_tool_invocation where run_id='abb05b66-3108-43fa-8d3b-f812de39b481' order by call_seq"
echo ===LDEAD-MODEL===
$PG "select state, error_code, created_at from rca_model_call where run_id='abb05b66-3108-43fa-8d3b-f812de39b481' order by created_at"
echo ===LDEAD-RUN===
$PG "select state, created_at, finished_at from rca_run where id='abb05b66-3108-43fa-8d3b-f812de39b481'"
echo ===PROM-ENV-CHECK===
grep -E 'PROMETHEUS|OPENAI_COMPAT' /opt/build/pr/rr-iso/rr-iso.env
