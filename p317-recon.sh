#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '--- 各服务 incident 数（找并发多的服务）:'
P "select coalesce(substring(incident_key from 'service=([^|]+)'),'（未知）') as svc, count(*) from incident group by 1 order by 2 desc limit 6;"
echo '--- change_event 各服务计数:'
P "select service, count(*) from change_event group by 1 order by 2 desc limit 6;"
echo '--- frontend 服务 24h 内 incident（sameService 演示对象）:'
P "select id, substring(incident_key from 'alertname=([^|]+)'), status from incident where coalesce(substring(incident_key from 'service=([^|]+)'),'')='frontend' and first_seen_at >= now() - interval '24 hours' order by first_seen_at desc limit 5;"
echo '--- checkout 服务的 incident + 该服务 change_event（合并轴演示对象）:'
P "select i.id, substring(i.incident_key from 'alertname=([^|]+)'), i.episode_started_at from incident i where coalesce(substring(i.incident_key from 'service=([^|]+)'),'')='checkout' order by i.episode_started_at desc limit 3;"
echo '--- checkout 变更事件时间:'
P "select started_at, action, source, status from change_event where service='checkout' order by started_at desc limit 3;"
echo '--- drill_job 关联的 incident:'
P "select related_incident_id, scenario_name, state from drill_job where related_incident_id is not null limit 3;"
