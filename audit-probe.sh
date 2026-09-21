#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select table_name||': '||(select string_agg(column_name,',') from information_schema.columns c2 where c2.table_name=t.table_name) from information_schema.tables t where table_name in ('change_event','auth_event')"
