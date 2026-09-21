#!/bin/sh
# RR22 诊断第三轮 v2：真实角色 + 逐案 run/模型调用时间线取证
PG="docker exec rriso-postgres-1 sh -c"
echo ==tables==
$PG 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -c "select table_name from information_schema.tables where table_schema='"'"'public'"'"' and (table_name like '"'"'rca%'"'"' or table_name like '"'"'%defer%'"'"' or table_name like '"'"'%ledger%'"'"' or table_name like '"'"'canary%'"'"' or table_name like '"'"'%task%'"'"') order by 1;"'
echo ==runs-last-2h==
$PG 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -F"|" -c "select id, state, created_at, finished_at from rca_run where created_at > now() - interval '"'"'2 hours'"'"' order by created_at;"'
echo ==modelcalls-last-2h==
$PG 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -F"|" -c "select run_id, call_seq, state, error_code, created_at from rca_model_call where created_at > now() - interval '"'"'2 hours'"'"' order by created_at;"'
