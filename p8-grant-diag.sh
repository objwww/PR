#!/bin/sh
echo '=== eval_app 试读两真源（权限定谳）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select 'invocation_readable='||(count(*)>=0)::text from rca_tool_invocation" 2>&1
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'grants_invocation='||coalesce(string_agg(privilege_type,','),'NONE') from information_schema.role_table_grants where table_name='rca_tool_invocation' and grantee='eval_app'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'grants_evidence='||coalesce(string_agg(privilege_type,','),'NONE') from information_schema.role_table_grants where table_name='rca_evidence' and grantee='eval_app'"
echo '=== 以 eval_app 实际试读 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c "set role eval_app; select count(*) from rca_tool_invocation;" 2>&1
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c "set role eval_app; select count(*) from rca_evidence;" 2>&1
echo '=== worker 日志中的评分异常痕迹 ==='
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -iE 'permission denied|ERROR.*(invocation|evidence)|评分' | tail -5
