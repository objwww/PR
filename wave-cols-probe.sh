#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'rca_model_call :: '||string_agg(column_name,', ' order by ordinal_position) from information_schema.columns where table_name='rca_model_call'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'rca_tool_invocation :: '||string_agg(column_name,', ' order by ordinal_position) from information_schema.columns where table_name='rca_tool_invocation'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'rca_run :: '||string_agg(column_name,', ' order by ordinal_position) from information_schema.columns where table_name='rca_run'"
exit 0
