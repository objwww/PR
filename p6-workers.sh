#!/bin/sh
docker ps --format '{{.Names}} {{.Status}}' | grep -i eval
echo '---workers polling (worker id in recent command claims)---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select claimed_by, state, created_at from eval_run_command order by created_at desc limit 4;" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select column_name from information_schema.columns where table_name='eval_run_command' and column_name like '%worker%' or table_name='eval_run_command' and column_name like '%claim%';"
