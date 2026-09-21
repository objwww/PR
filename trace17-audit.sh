#!/bin/sh
# 3.17 验收对账：面板数字 vs SQL（run 786f1c55-d03c-48d0-acfd-0250b79b66d9）
RID='786f1c55-d03c-48d0-acfd-0250b79b66d9'
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo "面板: 任务尝试 1 ← SQL:"
Q "select count(*) from rca_attempt a join rca_task t on t.id=a.task_id where t.run_id='$RID';"
echo "面板: 模型调用 12 ← SQL:"
Q "select count(*) from rca_model_call where run_id='$RID';"
echo "面板: 工具调用 12 ← SQL:"
Q "select count(*) from rca_tool_invocation where run_id='$RID';"
echo "面板: Token 合计 26114（已回报下限）← SQL:"
Q "select coalesce(sum((usage->>'total_tokens')::bigint),0) from rca_model_call where run_id='$RID' and usage is not null;"
echo "面板: 未回报笔数（应为 0 才不显示提示）← SQL:"
Q "select count(*) from rca_model_call where run_id='$RID' and (usage is null or usage->>'total_tokens' is null);"
echo "面板: 费用 21146 微单位 ← SQL:"
Q "select sum(cost_micros) from rca_model_call where run_id='$RID';"
echo "面板: 窗口 11.2 秒 ← SQL（attempt 起止）:"
Q "select extract(epoch from (max(finished_at)-min(started_at)))::int || ' 秒' from rca_attempt a join rca_task t on t.id=a.task_id where t.run_id='$RID';"
echo '=== 临时口令恢复原口令（收口） ==='
BK=$(cat /tmp/tr17shot-bk-path)
cd /opt/build/pr/deploy
cp "$BK" .env
cmp -s .env "$BK" && echo '.env 已恢复=OK'
docker compose up -d control-app >/dev/null
sleep 30
i=1
while [ $i -le 6 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
echo '=== 原口令登录自证（operator/operator 应回 200；若回 401 说明原口令非 e2e 口令，以备份为准已复原） ==='
rm -f /tmp/t17r.cookie
T=$(curl -s -c /tmp/t17r.cookie http://127.0.0.1:8080/api/auth/csrf -o /dev/null; grep XSRF-TOKEN /tmp/t17r.cookie | awk '{print $NF}')
curl -s -b /tmp/t17r.cookie -c /tmp/t17r.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=operator'
