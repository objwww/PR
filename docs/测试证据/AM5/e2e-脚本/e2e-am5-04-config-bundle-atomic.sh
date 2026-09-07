#!/bin/sh
# ============================================================================
# e2e-am5-04-config-bundle-atomic.sh —— E2E-AM5-04：ConfigBundle 原子激活与运行中回滚
#                     （CONTROL_FIXTURE；AM5 技术方案 §12.3；落码方案 §M5-09④ 随件交付）
#
# 必断言（§12.3 E2E-AM5-04 行 + 落码方案 §M5-09④ 拆解验收）：
#   ① 发布：POST /api/config-bundles → digest 64hex；同内容重发 replayed=true（幂等锚）；
#   ② 原子激活：activate → GET active 指针落位（revision/activatedAt 齐备），无半激活态；
#   ③ 运行中回滚：RCA Run 在途时 rollback toDigest → 指针立即指回，在途 Run 按其
#     启动时 digest 完成（老 Run 固定旧 digest 面，M5-10 V25 config_digest 列后强化）；
#   ④ 历史行零改写：回滚后 config_bundle 行集合与回滚前逐字段一致（INV-AM5-5）；
#   ⑤ 竞败面：并发 activate 双写只成功一个，败者 409 且指针唯一。
#
# 【骨架】本脚本随 M5-09 交付骨架，部署段（195 + 2C4G，L5/G2 门）执行：
#   - API 面（M5-09 已落）：curl 直调 control-app:8080 /api/config-bundles*，
#     Authorization: Bearer $APP_RELEASE_API_BEARER；
#   - 断言点 [195] 标注段需真栈（Run 在途窗口依赖真实告警→调查链路）。
#
# 用法（195 部署段）：sh e2e-am5-04-config-bundle-atomic.sh
# ============================================================================

set -e

BASE="${CONTROL_BASE:-http://127.0.0.1:8080}"
BEARER="${APP_RELEASE_API_BEARER:?APP_RELEASE_API_BEARER 未注入}"
OUT_PREFIX="[E2E-AM5-04]"
AUTH="Authorization: Bearer $BEARER"

log() { echo "$OUT_PREFIX $1"; }
fail() { echo "$OUT_PREFIX FAIL: $1"; exit 1; }

# ---------------------------------------------------------------------------
# phase1 发布与幂等锚（M5-09 已落面，API 真调）
# ---------------------------------------------------------------------------
log "phase1 发布 bundle v7 / v8 并验证幂等重放"
D1=$(curl -fsS -X POST "$BASE/api/config-bundles" -H "$AUTH" \
    -H 'Content-Type: application/json' -H 'Idempotency-Key: e2e-am5-04-v7' \
    -d '{"content":{"policy_version":"policy-e2e-am5-04","prompt_version":"holmes-prompt-v7"}}' \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['bundleDigest'])") \
    || fail "发布 v7 失败"
D2=$(curl -fsS -X POST "$BASE/api/config-bundles" -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"content":{"policy_version":"policy-e2e-am5-04","prompt_version":"holmes-prompt-v8"}}' \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['bundleDigest'])") \
    || fail "发布 v8 失败"
echo "  D1=$D1"
echo "  D2=$D2"
echo "$D1" | grep -qE '^[0-9a-f]{64}$' || fail "D1 非 64 位小写 hex"

REPLAYED=$(curl -fsS -X POST "$BASE/api/config-bundles" -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"content":{"policy_version":"policy-e2e-am5-04","prompt_version":"holmes-prompt-v7"}}' \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['replayed'])")
[ "$REPLAYED" = "True" ] || [ "$REPLAYED" = "true" ] || fail "同内容重发未返回 replayed=true"

# 密钥材料拒收（INV-AM5-5 出界面）
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/config-bundles" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"content":{"policy_version":"p","api_key":"material"}}')
[ "$CODE" = "400" ] || fail "密钥材料键未被拒收（期望 400 实得 $CODE）"

# ---------------------------------------------------------------------------
# phase2 原子激活（指针落位；无半激活态）
# ---------------------------------------------------------------------------
log "phase2 激活 D1 并验证 active 指针"
curl -fsS -X POST "$BASE/api/config-bundles/$D1/activate" -H "$AUTH" \
    -H 'Content-Type: application/json' -d '{}' > /dev/null || fail "激活 D1 失败"
ACTIVE=$(curl -fsS "$BASE/api/config-bundles/active" -H "$AUTH" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['bundleDigest'])")
[ "$ACTIVE" = "$D1" ] || fail "active 指针未落 D1（实得 $ACTIVE）"

# 401 面：伪 bearer 拒绝
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/config-bundles/active" \
    -H "Authorization: Bearer wrong-token")
[ "$CODE" = "401" ] || fail "伪 bearer 未被拒（期望 401 实得 $CODE）"

# ---------------------------------------------------------------------------
# phase3 运行中回滚（[195]：RCA Run 在途窗口内执行 rollback）
# ---------------------------------------------------------------------------
log "phase3 运行中回滚（[195] 真栈窗口）"
# [195] ① 注入 F1 并等待 RCA Run 进入在途（run_created 事件或 rca_run 行出现）
# [195] ② 在途窗口内：curl -X POST .../rollback -d "{\"toDigest\":\"$D1\"}"（先激活 D2）
# [195] ③ 断言指针立即指回 D1（GET active）；④ 断言在途 Run 的 config_digest
#     仍为激活时值（M5-10 V25 落列后启用该断言，本骨架期以日志留痕替代）
log "  （骨架期占位：在途窗口断言待 195 部署段 + M5-10 V25 config_digest 列）"

# ---------------------------------------------------------------------------
# phase4 历史行零改写（[195]：需 DB 直查面）
# ---------------------------------------------------------------------------
log "phase4 历史行零改写核对（[195] DB 面）"
# [195] docker exec postgres psql -U control_app -c
#   "select jsonb_object_agg(bundle_digest, to_jsonb(b)) from config_bundle b"
#   回滚前后两次取样逐字段对拍（IT 面已锁同构断言，真栈复核）
log "  （骨架期占位：DB 对拍待 195 部署段激活）"

log "骨架校验完成（phase3/4 真栈断言待 195 部署段激活；API 面 phase1/2 已可执行）"
