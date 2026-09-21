#!/bin/sh
echo '--- 最近两个调查 run 的 last_error ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(r.id::text,8)||' | state='||r.state||' | err='||coalesce(r.last_error::text,'-') from rca_run r where r.incident_id=(select id from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1) order by r.created_at desc limit 2;"
echo '--- attempt 状态（无 message 列）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select a.attempt_no||' | '||a.status||' | '||coalesce(a.error_class,'-')||'/'||coalesce(a.error_code,'-') from rca_attempt a where a.run_id in (select r.id from rca_run r where r.incident_id=(select id from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1)) order by a.started_at desc limit 4;"
