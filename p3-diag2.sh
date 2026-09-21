#!/bin/sh
echo '--- bc174322 失败原因 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select coalesce(last_error,'-') from rca_run where id=(select id from rca_run where incident_id=(select id from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1) order by created_at desc limit 1);"
echo '--- attempt 错误分类 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select a.status||' | '||coalesce(a.error_class,'-')||'/'||coalesce(a.error_code,'-')||' | '||coalesce(left(a.error_message,120),'-') from rca_attempt a where a.run_id=(select id from rca_run where incident_id=(select id from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1) order by created_at desc limit 1) order by a.attempt_no;"
echo '--- run 与 attempt 状态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(r.id::text,8)||' | '||r.state from rca_run r where r.incident_id=(select id from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1) order by r.created_at desc limit 2;"
