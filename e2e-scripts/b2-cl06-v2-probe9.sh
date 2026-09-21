#!/bin/sh
# b2-cl06-v2-probe9.sh —— BA-141 rca_event 结构+行取证
. /opt/build/r7-operator-env.sh
. /opt/build/b2tree/e2e-r7-common.sh
G() { r7_psql_ro R7_PG_URL "$1" '-At'; }
r=3564fee0-236f-4fb8-87c7-b17a197f86b4
echo "== rca_event 列："
G "select string_agg(column_name,',') from information_schema.columns where table_name='rca_event'"
echo "== 该 run 事件（类型+时间）："
G "select event_type||' @'||created_at from rca_event where run_id='$r' order by created_at"
echo "== 对照正常 run 事件类型谱："
G "select event_type||':'||count(*) from rca_event where run_id='22f2040d-226e-4161-a891-0efd48c0d4cf' group by event_type order by event_type"
