#!/bin/sh
# 诊断 run 0e5097c2：prompt 协议面 + 模型决策面
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select c.action_seq, c.round_id, length(i.prompt_text) from rca_model_input i join rca_model_call c on c.id=i.model_call_id where c.run_id='0e5097c2-f208-4fdd-9c65-81b559a6ccc9' and c.role_id='primary' order by c.action_seq"
echo "== 末轮 prompt 的协议段（找 delegate 词形上下文）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select i.prompt_text from rca_model_input i join rca_model_call c on c.id=i.model_call_id where c.run_id='0e5097c2-f208-4fdd-9c65-81b559a6ccc9' and c.role_id='primary' order by c.action_seq desc limit 1" > /tmp/b2cl06/last-prompt.txt
grep -o '.\{60\}delegate.\{80\}' /tmp/b2cl06/last-prompt.txt | head -5
echo "== 工具调用序列 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select action_seq, tool_name, state from rca_tool_invocation where run_id='0e5097c2-f208-4fdd-9c65-81b559a6ccc9' order by action_seq"
