#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select table_name from information_schema.tables where table_schema='public' and (table_name like '%chaos%' or table_name like '%session%');"
echo '---active sessions---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select * from chaos_session where state='ACTIVE' limit 5;" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select table_name, column_name from information_schema.columns where table_name like '%chaos%' order by table_name, ordinal_position;" | head -20
