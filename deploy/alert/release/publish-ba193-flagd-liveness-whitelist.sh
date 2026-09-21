#!/bin/bash
# BA-193 部署窗执行（195，只跑一次）：flagd 活性三症状补登 canary 白名单
# 现役 50 条 + 3 症状 × 2 形态（裸 + |job=flagd）= 56 条。照 BA-189 同律。
set -u
. /opt/build/b2tree/e2e-r7-common.sh
DIR="$(cd "$(dirname "$0")" && pwd)"
CONTENT="$DIR/bundle-ba193-flagd-liveness-whitelist.content.json"
RUNS="${R7_RUNS_DIR}/ba193-whitelist-$(date +%s)"; mkdir -p "$RUNS"
echo "== 0) 发布前激活指针 =="
curl -s -H "Authorization: Bearer ${R7_RELEASE_BEARER}" http://127.0.0.1:8080/api/config-bundles/active || true; echo
echo "== 1) publish + qualify + activate =="
DA="$(r7_publish_bundle "$CONTENT" ba193)" \
  && r7_qualify "$DA" "$RUNS" >/dev/null \
  && r7_activate "$DA" "$RUNS" >/dev/null \
  && echo "bundle 激活 ok digest=$DA" \
  || { echo "BA-193 ABORT：bundle 未激活"; exit 2; }
echo "== 2) 激活后核对 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select b.content->>'policy_version', jsonb_array_length(b.content->'canary'->'whitelist'), b.content->'canary'->'whitelist' ? 'alertname=FlagdDown|job=flagd' from config_bundle b join config_bundle_active a on a.bundle_digest=b.bundle_digest;"
