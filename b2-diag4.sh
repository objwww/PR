#!/bin/sh
RUN=$(cat /tmp/b2cl06/run-id.txt | cut -d= -f2)
echo "== checkpoint final_claims + missing =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select jsonb_pretty(final_claims), coalesce(jsonb_pretty(final_missing_information),'null') from rca_primary_checkpoint where run_id='$RUN'"
echo "== 报告包 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select substring(package::text,1,500) from rca_report where run_id='$RUN'"
echo "== 末轮 primary prompt 的 working_memory 段 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select i.prompt_text from rca_model_input i join rca_model_call c on c.id=i.model_call_id where c.run_id='$RUN' and c.role_id='primary' order by c.action_seq desc limit 1" | grep -o '"working_memory":{[^}]*}'
echo "== 模型调用 rounds =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select action_seq, round_id, role_id, state from rca_model_call where run_id='$RUN' order by action_seq"
