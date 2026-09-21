#!/bin/sh
# 3.4 验收对账 + 恢复原口令（收口）
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo "面板: 累计问答 4 ← SQL:"
Q "select count(*) from diag_session;"
echo "面板: 涉及事件 3 ← SQL:"
Q "select count(distinct incident_id) from diag_session;"
echo "面板: 今日新增 4 ← SQL:"
Q "select count(*) from diag_session where created_at >= date_trunc('day', now());"
echo "面板: 自由问占比 0%（自由问 0 笔）← SQL:"
Q "select count(*) from diag_session where question_key = 'FREE';"
echo "面板: 快捷问答案真值抽查（history 键，answer 应含真实调查计数）← SQL:"
Q "select '该事件 rca_run 计数=' || count(*) from rca_run where incident_id = (select incident_id from diag_session order by created_at desc limit 1);"
echo '=== 恢复原口令（收口） ==='
BK=$(cat /tmp/diag34shot-bk-path)
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
