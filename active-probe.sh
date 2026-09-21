#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name||':'||data_type from information_schema.columns where table_name='config_bundle_active' order by ordinal_position" | tr '\n' ' '
echo
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select * from config_bundle_active limit 1" | cut -c1-100
