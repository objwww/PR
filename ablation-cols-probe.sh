#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select string_agg(column_name,', ' order by ordinal_position) from information_schema.columns where table_name='alert_inbox'"
exit 0
