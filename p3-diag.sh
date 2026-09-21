#!/bin/sh
echo '--- 事故状态/代数 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status||' | gen='||generation||' | updated='||updated_at from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1;"
echo '--- 最近调查 run ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8)||' | '||state||' | '||created_at from rca_run where incident_id=(select id from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1) order by created_at desc limit 3;"
echo '--- inbox 最新 ArenaOrderStuck 载荷 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*)||' total, latest '||max(received_at)::text from alert_inbox where convert_from(payload_raw,'UTF8') like '%\"alertname\":\"ArenaOrderStuck\"%';"
echo '--- worker 尾日志（含 WARN）---'
tail -8 /tmp/p2-worker.log
