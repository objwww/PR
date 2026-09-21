#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='incident' order by ordinal_position" | tr '\n' ' '
echo
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select coalesce(labels->>'service','?')||' | '||coalesce(labels->>'alertname','?')||' | '||status from incident limit 3"
