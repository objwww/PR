#!/bin/sh
set -e
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select string_agg(column_name, ',' order by ordinal_position) from information_schema.columns where table_name='rca_event';"
echo '--- 样例:'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select * from rca_event where event_type='GUARDIAN_REVIEWED' order by id desc limit 1;"
