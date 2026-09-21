#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== config_bundle 列 ==='
Q "select column_name||':'||data_type from information_schema.columns where table_name='config_bundle' order by ordinal_position" | tr '\n' ' '
echo
echo '=== 最新3条 ==='
Q "select left(digest::text,12)||'|'||coalesce(kind,'-')||'|'||coalesce(name,'-')||'|'||created_at from config_bundle order by created_at desc limit 3"
echo '=== 内容列样例(前120字) ==='
Q "select left(content::text,120) from config_bundle order by created_at desc limit 1" 2>/dev/null || echo "no content col"
