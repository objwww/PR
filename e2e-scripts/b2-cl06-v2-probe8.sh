#!/bin/sh
# b2-cl06-v2-probe8.sh —— BA-141 任务 DEAD 迁移审计取证
. /opt/build/r7-operator-env.sh
. /opt/build/b2tree/e2e-r7-common.sh
G() { r7_psql_ro R7_PG_URL "$1" '-At'; }
echo "== 事件/审计类表："
G "select table_name from information_schema.tables where table_schema='public' and (table_name like 'rca_%event%' or table_name like '%audit%' or table_name like 'rca_event%')"
echo "== 该 run 的 rca_event 行："
G "select left(payload::text,240) from rca_event where run_id='3564fee0-236f-4fb8-87c7-b17a197f86b4' order by created_at limit 12" 2>/dev/null || G "select string_agg(column_name,',') from information_schema.columns where table_name='rca_event'"
