#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'zong=' || count(*) from diag_session;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'events=' || count(distinct incident_id) from diag_session;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'today=' || count(*) from diag_session where created_at >= date_trunc('day', now());"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'free=' || count(*) from diag_session where question_key = 'FREE';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'runs_of_last=' || count(*) from rca_run where incident_id = (select incident_id from diag_session order by created_at desc limit 1);"
