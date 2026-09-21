#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '--- 同 incident_key 多条（复发/重开史）:'
P "select incident_key, count(*), min(id::text) from incident group by incident_key having count(*) > 1 order by 2 desc limit 5;"
echo '--- change_event 最近时间分布:'
P "select started_at::date, count(*) from change_event group by 1 order by 1 desc limit 5;"
echo '--- drill_job 创建时间分布:'
P "select created_at::date, count(*) from drill_job group by 1 order by 1 desc limit 5;"
echo '--- pa3svc 的 incident:'
P "select id, substring(incident_key from 'alertname=([^|]+)'), episode_started_at, status from incident where coalesce(substring(incident_key from 'service=([^|]+)'),'')='pa3svc' order by episode_started_at desc limit 4;"
