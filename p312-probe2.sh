#!/bin/sh
echo '=== drill_event 列 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='drill_event' order by ordinal_position;"
echo '=== 唯一一次演练的事件 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select event_type, occurred_at from drill_event order by occurred_at limit 15;" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select * from drill_event limit 2;"
echo '=== drill_job 完整行 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_name, target_env, state, outcome, related_incident_id is not null, related_run_id is not null, created_at, closed_at from drill_job;"
