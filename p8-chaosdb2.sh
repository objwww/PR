#!/bin/sh
echo "--- arena-chaos-admin env:"
docker inspect alert-arena-chaos-admin-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -viE 'PATH|JAVA|LANG' | sed 's/\(PASSWORD\|TOKEN\|KEY\)=.*/\1=***/'
echo "--- chaos-like tables in pr_agent:"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "select table_schema||'.'||table_name from information_schema.tables where table_name ilike '%chaos%'"
echo "--- rriso postgres dbs:"
docker exec -i rriso-postgres-1 psql -U postgres -t -A -c "select datname from pg_database where datistemplate=false" 2>/dev/null
docker exec -i rriso-postgres-1 psql -U postgres -d pr_agent -t -A -c "select schemaname||'.'||tablename from pg_tables where tablename ilike '%chaos%'" 2>/dev/null
