#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '--- change_event 列:'
P "select string_agg(column_name, ',' order by ordinal_position) from information_schema.columns where table_name='change_event';"
echo '--- change_event 样例:'
P "select * from change_event order by id desc limit 1;" 2>/dev/null | head -3
echo '--- drill_job 列:'
P "select string_agg(column_name, ',' order by ordinal_position) from information_schema.columns where table_name='drill_job';"
echo '--- rca_event 7d 各类型计数(供合并轴取舍):'
P "select event_type, count(*) from rca_event where created_at >= now() - interval '7 days' group by 1 order by 2 desc limit 8;"
