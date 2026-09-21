#!/bin/bash
# BA-180 部署窗执行（195 上运行，只跑一次）：M-a 业务交易链路九症状 alertname 补登
# canary 白名单——现役 bundle（afd79ffe…，policy p4-redteam-v2-whitelist-20260917T0350Z）
# 全量白名单 + 9 症状 × 2 形态（|service=order-arena 与 …|job=order-arena，两种历史
# incident_key 段形共存，与靶场三件套既有双形态登记同律）。
#
# 背景：canary percent=0 时非白名单 stickiness key 恒路由 BUCKETED_HOLMES（M6-07 第二
# 引擎已退场）→ 投影照记不铸 run + WAITING_CAPABILITY，redrive 每 30s 重路由仍是
# HOLMES 永不铸 run——S16~S25 全部业务症状告警经 alertmanager 链永远无调查。
#
# 用法：bash publish-ba180-ma-business-whitelist.sh
# 前置：/opt/build/b2tree/e2e-r7-common.sh 存在（r7_publish_bundle/r7_qualify/r7_activate
#   为既有发布链原语，与 RR 批同一家法）；脚本只新增 bundle 代际，失败即 ABORT 零半途。
set -u
. /opt/build/b2tree/e2e-r7-common.sh

DIR="$(cd "$(dirname "$0")" && pwd)"
CONTENT="$DIR/bundle-ba180-ma-business-whitelist.content.json"
RUNS="${R7_RUNS_DIR}/ba180-whitelist-$(date +%s)"; mkdir -p "$RUNS"

echo "== 0) 发布前激活指针 =="
curl -s http://127.0.0.1:8080/api/config-bundles/active || true; echo

echo "== 1) publish + qualify + activate（r7 发布链原语）=="
DA="$(r7_publish_bundle "$CONTENT" ba180)" \
  && r7_qualify "$DA" "$RUNS" >/dev/null \
  && r7_activate "$DA" "$RUNS" >/dev/null \
  && echo "bundle 激活 ok digest=$DA" \
  || { echo "BA-180 ABORT：bundle 未激活（现场零改写或指针仍在旧代际）"; exit 2; }

echo "== 2) 激活后核对：指针=新 digest 且白名单含九症状 =="
curl -s http://127.0.0.1:8080/api/config-bundles/active; echo
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select count(*) from config_bundle b join config_bundle_active a on a.bundle_digest=b.bundle_digest where b.content->'canary'->'whitelist' ? 'alertname=ArenaPaymentOrderMismatch|service=order-arena|job=order-arena' and b.content->'canary'->'whitelist' ? 'alertname=ArenaFulfillmentSlaBreach|service=order-arena|job=order-arena';"

echo "== 3) 事后验证（下一次 S16/S26 演练自然覆盖，或人工复核）："
echo "   canary_route_decision 中九症状 key 的 decision 应为 WHITELISTED（run_id 非空）"
echo "   → rca_run 铸造恢复，演练 OBSERVING 窗内 DR-06 关联命中 → outcome PASS"
