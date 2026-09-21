#!/bin/sh
RUN=$(cat /tmp/b2cl06/run-id.txt | cut -d= -f2)
echo "run=$RUN"
echo "== checkpoint =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select round_id, phase, decision_seq, steps_used, batches_used, revision, schema_version, coalesce(memory_id::text,'') from rca_primary_checkpoint where run_id='$RUN'"
echo "== 记忆行 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select checkpoint_revision, schema_version, coalesce(parent_memory_id::text,''), memory_json::text from rca_working_memory where run_id='$RUN' order by checkpoint_revision"
echo "== primary 调用 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select action_seq, round_id, state from rca_model_call where run_id='$RUN' and role_id='primary' order by action_seq"
echo "== 工具调用 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select action_seq, tool_name, state, reason_code from rca_tool_invocation where run_id='$RUN' order by action_seq"
