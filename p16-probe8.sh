#!/bin/sh
echo '=== role_digest 匹配（PROMPT content.role_digest vs rca_model_call.role_digest） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(r.content->>'role_digest',8) as prole, count(*) as calls, max(m.created_at)::date as last_call from rca_model_call m join release_asset r on r.asset_kind='PROMPT' and r.content->>'role_digest' = m.role_digest group by 1 order by 3 desc nulls last limit 8;"
echo '=== 每个角色最新 PROMPT（digest 前 8 + created_at） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select content->>'role' as role, content->>'role_version' as ver, left(asset_digest::text,8), created_at::date, left(content->>'messages_template', 40) from release_asset where asset_kind='PROMPT' order by created_at desc limit 10;"
