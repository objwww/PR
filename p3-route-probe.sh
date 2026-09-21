#!/bin/sh
echo '--- ArenaOrderStuck 最新路由决策 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select created_at||' | '||decision||' | '||engine||' | run='||coalesce(run_id::text,'-') from canary_route_decision where incident_key like 'alertname=ArenaOrderStuck%' order by created_at desc limit 5;" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='canary_route_decision';"
echo '--- 事故当前 waiting 原因 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status||' | waiting='||coalesce(waiting_reason::text,'-') from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1;" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='incident' and column_name like '%waiting%';"
