#!/bin/bash
# BA-189 部署窗执行（195 上运行，只跑一次）：infra 活性四症状 alertname 补登 canary 白
# 名单——现役 bundle（ba180-ma-business-whitelist，42 条）全量 + 4 症状 × 2 形态
# （裸 alertname 与 |job=otel-collector，与 MemoryGate 三档闸既有双形态登记同律）= 50 条。
#
# 背景：canary percent=0 时非白名单 stickiness key 恒路由 BUCKETED_HOLMES → 永不铸
# run（BA-180 定谳）。otel-collector 活性告警（infra-liveness.yml）若不入白名单，
# firing 后只会投影 incident 而零调查。
#
# 用法：bash publish-ba189-infra-liveness-whitelist.sh
# 前置：/opt/build/b2tree/e2e-r7-common.sh；bundle 内容文件已就位于 195 /tmp 或本目录。
#   R7_RUNS_DIR / R7_RELEASE_BEARER / R7_PSQL_CMD / R7_PG_URL 四键按注释注入
#   （BEARER/PG 口令从 deploy/.env 读取，不落本脚本）。
set -u
. /opt/build/b2tree/e2e-r7-common.sh

DIR="$(cd "$(dirname "$0")" && pwd)"
CONTENT="$DIR/bundle-ba189-infra-liveness-whitelist.content.json"
RUNS="${R7_RUNS_DIR}/ba189-whitelist-$(date +%s)"; mkdir -p "$RUNS"

echo "== 0) 发布前激活指针 =="
curl -s -H "Authorization: Bearer ${R7_RELEASE_BEARER}" http://127.0.0.1:8080/api/config-bundles/active || true; echo

echo "== 1) publish + qualify + activate（r7 发布链原语）=="
DA="$(r7_publish_bundle "$CONTENT" ba189)" \
  && r7_qualify "$DA" "$RUNS" >/dev/null \
  && r7_activate "$DA" "$RUNS" >/dev/null \
  && echo "bundle 激活 ok digest=$DA" \
  || { echo "BA-189 ABORT：bundle 未激活（现场零改写或指针仍在旧代际）"; exit 2; }

echo "== 2) 激活后核对：指针=新 policy 且白名单含四症状 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select b.content->>'policy_version', jsonb_array_length(b.content->'canary'->'whitelist'), b.content->'canary'->'whitelist' ? 'alertname=OtelCollectorDown|job=otel-collector' from config_bundle b join config_bundle_active a on a.bundle_digest=b.bundle_digest;"

echo "== 3) 事后验证：collector 真宕时 canary_route_decision 中四症状 key 应为 WHITELISTED（铸 run）"
