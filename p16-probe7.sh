#!/bin/sh
echo '=== prompt_digest 与 PROMPT 资产 digest 的匹配率 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(distinct m.prompt_digest) from rca_model_call m join release_asset r on r.asset_digest=m.prompt_digest and r.asset_kind='PROMPT';"
echo '=== PROMPT 角色/版本分布 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select content->>'role' as role, content->>'role_version' as ver, count(*) from release_asset where asset_kind='PROMPT' group by 1,2 order by 1,2;"
echo '=== 各 prompt 最近调用（前 5） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(m.prompt_digest,8), count(*), max(m.created_at)::date from rca_model_call m group by 1 order by 3 desc nulls last limit 5;"
echo '=== 重查端点是否存在 ==='
grep -rn 'reinvestigate' /opt/build/pr/control-app/src/main/java --include='*.java' -l | head -3
