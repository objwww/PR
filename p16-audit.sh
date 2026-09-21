#!/bin/sh
echo '=== SQL 对账 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'PROMPT版本总数(面板46)=' || count(*) from release_asset where asset_kind='PROMPT';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select '角色数(面板4)=' || count(distinct content->>'role') from release_asset where asset_kind='PROMPT' and content->>'role' is not null;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select '有调用记录版本数(面板11)=' || count(distinct r.content->>'role_digest') from release_asset r where r.asset_kind='PROMPT' and r.content->>'role_digest' in (select distinct role_digest from rca_model_call);"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select '激活包(面板#151)=' || b.revision from config_bundle_active a join config_bundle b on b.bundle_digest=a.bundle_digest limit 1;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select '投产量抽查: b3fa6124(1d5b2ad2同策略v3)调用数=' || count(*) from rca_model_call m join release_asset r on r.asset_kind='PROMPT' and r.asset_digest::text like 'b3fa6124%' and m.role_digest = r.content->>'role_digest';"
echo '=== 恢复原口令（收口） ==='
BK=$(cat /tmp/p16shot-bk-path)
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
