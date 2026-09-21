#!/bin/sh
echo '=== SQL 对账 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select '分类分布对账: ' || coalesce(category,'UNCLASSIFIED') || '=' || count(*) from incident group by 1 order by 2 desc;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select '事件总数(面板16)=' || count(*) from incident;"
echo '=== 恢复原口令（收口） ==='
BK=$(cat /tmp/cfg36shot-bk-path)
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
