#!/bin/sh
PG=$(docker ps --format '{{.Names}}' | grep -E 'postgres' | head -1)
echo "--- databases:"
docker exec -i "$PG" psql -U postgres -t -A -c "select datname from pg_database where datistemplate=false"
echo "--- schemas in pr_agent:"
docker exec -i "$PG" psql -U postgres -d pr_agent -t -A -c "select schema_name from information_schema.schemata"
echo "--- where is oa_chaos_session:"
docker exec -i "$PG" psql -U postgres -d pr_agent -t -A -c "select table_schema||'.'||table_name from information_schema.tables where table_name like 'oa_chaos%'"
