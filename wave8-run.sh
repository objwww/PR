#!/bin/sh
set -e
cd /opt/build/pr
tar xzf /tmp/wave8.tar.gz
cd deploy
docker compose up migrate 2>&1 | tail -1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'PRICED='||count(*) filter (where cost_micros is not null)||'/TOTAL='||count(*)||'|SUM_CNY='||round(coalesce(sum(cost_micros),0)/1000000.0,4) from rca_model_call"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'FLYWAY='||version from flyway_schema_history where success order by installed_rank desc limit 1"
