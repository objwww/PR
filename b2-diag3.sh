#!/bin/sh
RUN=$(cat /tmp/b2cl06/run-id.txt | cut -d= -f2)
echo "== 工具调用列名 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select column_name from information_schema.columns where table_name='rca_tool_invocation' order by ordinal_position"
echo "== 工具调用 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select action_seq, tool_name, state from rca_tool_invocation where run_id='$RUN' order by action_seq"
echo "== 子任务检查点/最后错误 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select task_id, round_id, phase, steps_used, coalesce(last_error,'') from rca_primary_checkpoint where run_id='$RUN'"
echo "== 委派决策载荷 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select decision_json::text from rca_delegation_decision where run_id='$RUN'"
echo "== 回执表结构 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c '\d rca_delegation_receipt'
echo "== 回执行 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select * from rca_delegation_receipt where run_id='$RUN'"
