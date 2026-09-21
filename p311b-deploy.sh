#!/bin/sh
set -e
cd /opt/build/pr
tar xf /tmp/p311b-mig.tar -C /opt/build/pr
cd deploy
docker compose up migrate 2>&1 | tail -1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version from flyway_schema_history where success order by installed_rank desc limit 1;"
