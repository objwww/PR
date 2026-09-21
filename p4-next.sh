#!/bin/sh
sleep 570
sh /tmp/p4nw.sh
echo '---SAFETY-ROWS---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select count(*) from eval_case_safety;"
echo '---RT-RUNS---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select id, state, created_at from rca_run where created_at > '2026-09-17T02:30:00Z' order by created_at limit 5;"
