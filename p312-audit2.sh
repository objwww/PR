#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'scenarios=' || count(distinct scenario_name) from drill_job;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'cells=' || count(*) from (select scenario_name, target_env from drill_job group by 1,2) t;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'recfail=' || count(*) from drill_job where state='RECOVERY_FAILED';"
