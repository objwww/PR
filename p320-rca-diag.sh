#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '--- 告警中 6 条事件的调查状态与等待原因:'
P "select substring(incident_key from 'alertname=([^|]+)') as alertname, status, waiting_reason, (select count(*) from rca_run r where r.incident_id = i.id) as runs from incident i where status = 'FIRING' order by first_seen_at desc;"
echo '--- rca_run 按事件分布（哪些被调查过）:'
P "select substring(i.incident_key from 'alertname=([^|]+)') as alertname, count(r.id), max(r.state) from incident i left join rca_run r on r.incident_id = i.id group by 1 order by 2 desc limit 12;"
echo '--- 放量名单源探测（capability/route 相关表或配置）:'
P "select table_name from information_schema.tables where table_schema='public' and (table_name like '%route%' or table_name like '%capab%' or table_name like '%allow%' or table_name like '%canary%');"
