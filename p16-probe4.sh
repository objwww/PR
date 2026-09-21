#!/bin/sh
echo '=== bundle151 content 顶层键 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select string_agg(k, ', ') from (select jsonb_object_keys(content) k from config_bundle where revision=151) t;"
echo '=== prompts 段（若有） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(content->'prompts'::text, 400) from config_bundle where revision=151;" 2>/dev/null | head -6
echo '=== 当前激活 bundle ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select b.revision, left(b.bundle_digest::text,8), a.activated_at from config_bundle_active a join config_bundle b on b.bundle_digest=a.bundle_digest limit 1;"
echo '=== 激活包是否含最新 primary prompt 的完整 digest ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from config_bundle_active a join config_bundle b on b.bundle_digest=a.bundle_digest where b.content::text like (select '%' || asset_digest::text || '%' from release_asset where asset_kind='PROMPT' order by created_at desc limit 1);"
