#!/bin/sh
echo '--- history 全部版本（找缺口）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select string_agg(version, ',' order by installed_rank) from flyway_schema_history;" | tr ',' '\n' | tr '\n' ' '
echo
echo '--- 关键表/列存在性 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'eval_run:'||count(*) from eval_run;" 2>&1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'incident:'||count(*) from incident;" 2>&1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'cause_component_hit:'||count(*) from information_schema.columns where table_name='eval_case_result' and column_name='cause_component_hit';" 2>&1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'diag_session_feedback:'||count(*) from information_schema.tables where table_name='diag_session_feedback';" 2>&1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'eval_case_safety:'||count(*) from information_schema.tables where table_name='eval_case_safety';" 2>&1
echo '--- 数据量参照（是否旧快照）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'alerts='||(select count(*) from alert_event) || ' inbox=' || (select count(*) from alert_inbox);"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select max(received_at)::text from alert_inbox;"
