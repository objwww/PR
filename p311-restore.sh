#!/bin/sh
BK=$(cat /tmp/p311shot-bk-path)
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
echo '=== 对账 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select '已打标实验=' || count(*) from eval_run where governance_tag is not null;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select '已打标数据集=' || count(*) from eval_dataset_tier;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'flyway=' || version from flyway_schema_history where success order by installed_rank desc limit 1;"
