#!/bin/sh
# bundle 内容对比：v1 时代 vs v5 的白名单+percent 实际存储值
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===ALL-BUNDLES===
$PG "select bundle_digest, created_at from config_bundle order by created_at"
echo ===V5-BUNDLE-CONTENT===
$PG "select content from config_bundle where bundle_digest='36b5879350f1e2132ab52543238a44fb04e4bf3d292c40d2510a9a697fb04ab9'"
