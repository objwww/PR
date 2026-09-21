#!/bin/sh
echo '=== SQL 对账 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'checkout 30天事件(面板5)=' || count(*) from incident where coalesce(substring(incident_key from 'service=([^|]+)'),'（未知服务）')='checkout' and first_seen_at >= now() - interval '30 days';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select '已登记负责人=' || count(*) from service_owner;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'flyway=' || version from flyway_schema_history where success order by installed_rank desc limit 1;"
echo '=== 恢复原口令（收口） ==='
BK=$(cat /tmp/p313shot-bk-path)
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
