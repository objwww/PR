#!/bin/sh
# ============================================================================
# e2e-am6-00-baseline-assembly.sh —— E2E-AM6-00：AM6 基线与装配面（M6-01 案④；
#                                     沿 AM5 附录 §二 house style；[195] 部署段）
#
# 必断言（AM6 落码方案 §M6-01 验收 + 本案四案拆解）：
#   ① 姿态面双态（经 AM6_EXPECT_NATIVE_READY 期望注入，两轮执行各证一侧）：
#     - 匿名 GET /api/canary/status → 401（零落库；release bearer 同构）；
#     - not-ready：nativeReady=false + missing 含 metricsExpr/toolRegistryDigest
#       + 不出具 capabilityDigest（不完整能力没有指纹，fail-closed C-67）；
#     - ready：nativeReady=true + capabilityDigest 64hex（装配事实纯函数）。
#   ② percent=0 ≠ 未配置：发布 canary.percent=0 bundle → 注入合成告警 →
#     BUCKETED_HOLMES 且 canary_bucket 照记（0 不是 CANARY_DISABLED）+ engine=HOLMES；
#   ③ CANARY_DISABLED 对照：无 canary 段 bundle → 注入 → CANARY_DISABLED +
#     stickiness_key/canary_bucket 双 NULL（分桶根本未计算）+ engine=HOLMES；
#   ④ V25 活跃唯一索引回归（活库直查）：uq_rca_run_active_incident 谓词含
#     'REPORTING'（BA-43 重建保集）+ 粒度 (incident_id, engine)；
#   ⑤ 收尾态：percent=0 bundle 重激活为基线（全 HOLMES 非平凡最安全态）。
#
# 前置（195 操作员）：AM6_* env 已注入（见 e2e-am6-common.sh 头注）；control-app
#   健康 200。姿态期望由 AM6_EXPECT_NATIVE_READY（false 默认/true）控制——
#   两轮执行 = 双态证据（false 轮先跑，键位翻转属显式操作员步骤，先备份后变更）。
#
# 用法（195 部署段）：AM6_EXPECT_NATIVE_READY=false sh e2e-am6-00-baseline-assembly.sh
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am6-common.sh"

SUITE="$(am6_suite_run_id)"
RUNS="${AM6_RUNS_DIR:-./runs}/${SUITE}-am6-00"
mkdir -p "$RUNS"
EXPECT="${AM6_EXPECT_NATIVE_READY:-false}"
# stickiness/身份键统一小写（CanaryBucketer.normalizedKey = trim + Locale.ROOT 小写，
# DB 存的是全小写化键——键形与库面对齐，轮询不空转）
SFX="$(echo "${SUITE}" | tr 'A-Z' 'a-z')"

am6_log "E2E-AM6-00 开始 suite=$SUITE runs=$RUNS expect_native_ready=$EXPECT"
am6_resource_snapshot "$RUNS" "start"

# ---------------------------------------------------------------------------
# phase1 姿态面（401 零落库 + nativeReady 双态按期望断言）
# ---------------------------------------------------------------------------
am6_log "phase1 姿态面：canary/status 匿名 401 + 双态断言（期望=$EXPECT）"
_code="$(am6_http GET /api/canary/status "" "" "$RUNS/status-anon.json")"
[ "$_code" = "401" ] || am6_fail "phase1 匿名访问期望 401 实得 $_code"
_code="$(am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status.json")"
[ "$_code" = "200" ] || am6_fail "phase1 status 期望 200 实得 $_code"

grep -q '"nativeReady":true' "$RUNS/status.json" && _ready=true || _ready=false
if [ "$_ready" != "$EXPECT" ]; then
    am6_fail "phase1 nativeReady=$_ready 与期望 $EXPECT 不符（status=$(cat "$RUNS/status.json")）"
fi
if [ "$EXPECT" = "false" ]; then
    grep -q 'metricsExpr' "$RUNS/status.json" || am6_fail "phase1 缺件清单应含 metricsExpr"
    grep -q 'toolRegistryDigest' "$RUNS/status.json" || am6_fail "phase1 缺件清单应含 toolRegistryDigest"
    if grep -q '"capabilityDigest"' "$RUNS/status.json"; then
        am6_fail "phase1 not-ready 不应出具 capabilityDigest（fail-closed）"
    fi
else
    grep -Eq '"capabilityDigest":"[0-9a-f]{64}"' "$RUNS/status.json" \
        || am6_fail "phase1 ready 应出具 64hex capabilityDigest"
fi
am6_log "phase1 PASS（nativeReady=$_ready，响应已留证 status.json/status-anon.json）"

# ---------------------------------------------------------------------------
# phase2 percent=0 → BUCKETED_HOLMES 且桶位照记（0 不是 CANARY_DISABLED）
# ---------------------------------------------------------------------------
am6_log "phase2 percent=0 全 Holmes：发布→激活→注入→审计行对拍"
printf '{"policy_version":"am6-e2e-00-p0","canary":{"percent":0}}' > "$RUNS/bundle-p0.content"
D0="$(am6_publish_bundle "$RUNS/bundle-p0.content" p0)"
am6_log "  percent=0 bundle digest=$D0"
am6_activate "$D0" "$RUNS"
_ad="$(am6_active_digest "$RUNS")"
[ "$_ad" = "$D0" ] || am6_fail "phase2 激活后 active=$_ad 期望 $D0"

P0K="alertname=higherrorrate|service=am6e2e00-p0-${SFX}"
# 库面 stickiness_key = normalizedKey(groupId,id) = "K:K" 双段（group 与 id 同为 incidentKey）
P0KEY="${P0K}:${P0K}"
_code="$(am6_inject_alert HighErrorRate "am6e2e00-p0-${SFX}" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase2 注入期望 202 实得 $_code: $(cat "$RUNS/alert-am6e2e00-p0-${SFX}-firing.resp")"
am6_db_poll_ge "phase2 BUCKETED_HOLMES 审计行+run 对齐" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision d JOIN rca_run r ON r.id=d.run_id
     WHERE d.stickiness_key='${P0KEY}' AND d.decision='BUCKETED_HOLMES'
       AND d.percent=0 AND d.canary_bucket IS NOT NULL AND d.bundle_digest='${D0}'
       AND r.engine='HOLMES' AND r.stickiness_key=d.stickiness_key
       AND r.canary_bucket=d.canary_bucket" 1
am6_psql_ro AM6_PG_URL "SELECT d.stickiness_key, d.decision, d.percent, d.canary_bucket,
    r.engine, r.state FROM canary_route_decision d JOIN rca_run r ON r.id=d.run_id
    WHERE d.stickiness_key='${P0KEY}'" > "$RUNS/phase2-p0-row.txt"
am6_log "phase2 PASS（$RUNS/phase2-p0-row.txt）"

# ---------------------------------------------------------------------------
# phase3 CANARY_DISABLED 对照（无 canary 段：键/桶双 NULL）
# ---------------------------------------------------------------------------
am6_log "phase3 CANARY_DISABLED 对照：无 canary 段 bundle"
printf '{"policy_version":"am6-e2e-00-nocanary"}' > "$RUNS/bundle-nocanary.content"
D1="$(am6_publish_bundle "$RUNS/bundle-nocanary.content" nocanary)"
am6_activate "$D1" "$RUNS"
am6_log "  无 canary 段 bundle digest=$D1"

NCK="alertname=higherrorrate|service=am6e2e00-nocanary-${SFX}"
NCKEY="${NCK}:${NCK}"
_code="$(am6_inject_alert HighErrorRate "am6e2e00-nocanary-${SFX}" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase3 注入期望 202 实得 $_code: $(cat "$RUNS/alert-am6e2e00-nocanary-${SFX}-firing.resp")"
am6_db_poll_ge "phase3 CANARY_DISABLED 审计行" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision d JOIN rca_run r ON r.id=d.run_id
     WHERE d.bundle_digest='${D1}' AND d.decision='CANARY_DISABLED'
       AND d.stickiness_key IS NULL AND d.canary_bucket IS NULL
       AND r.engine='HOLMES'" 1
am6_psql_ro AM6_PG_URL "SELECT d.run_id, d.decision, d.stickiness_key, d.canary_bucket,
    r.engine FROM canary_route_decision d JOIN rca_run r ON r.id=d.run_id
    WHERE d.bundle_digest='${D1}' AND d.decision='CANARY_DISABLED'" > "$RUNS/phase3-nocanary-row.txt"
am6_log "phase3 PASS（$RUNS/phase3-nocanary-row.txt）"

# ---------------------------------------------------------------------------
# phase4 V25 活跃唯一索引回归（活库直查；BA-43 重建保集）
# ---------------------------------------------------------------------------
am6_log "phase4 uq_rca_run_active_incident 索引回归（活库 indexdef）"
am6_psql_ro AM6_PG_URL "SELECT indexdef FROM pg_indexes
    WHERE indexname='uq_rca_run_active_incident'" > "$RUNS/phase4-indexdef.txt"
grep -q "CREATE UNIQUE INDEX" "$RUNS/phase4-indexdef.txt" \
    || am6_fail "phase4 应为 unique index: $(cat "$RUNS/phase4-indexdef.txt")"
grep -q "(incident_id, engine)" "$RUNS/phase4-indexdef.txt" \
    || am6_fail "phase4 粒度应为 (incident_id, engine): $(cat "$RUNS/phase4-indexdef.txt")"
grep -q "'REPORTING'" "$RUNS/phase4-indexdef.txt" \
    || am6_fail "phase4 谓词应含 'REPORTING'（V12 扩集/BA-43 保集）: $(cat "$RUNS/phase4-indexdef.txt")"
am6_log "phase4 PASS（REPORTING 在活跃集，REPORTING 并发铸造防线在位）"

# ---------------------------------------------------------------------------
# phase5 收尾：percent=0 bundle 重激活为基线（全 Holmes 非平凡最安全态）
# ---------------------------------------------------------------------------
am6_log "phase5 收尾：重激活 percent=0 基线（digest=$D0）"
am6_activate "$D0" "$RUNS"
_ad="$(am6_active_digest "$RUNS")"
[ "$_ad" = "$D0" ] || am6_fail "phase5 基线重激活后 active=$_ad 期望 $D0"

am6_resource_snapshot "$RUNS" "end"
am6_scenario_result "$RUNS" "E2E-AM6-00" "AM6 基线与装配面（姿态双态/percent0/禁用对照/索引回归）" \
    "real-stack-195" "PASS"
am6_log "E2E-AM6-00 PASS（suite=$SUITE，证据=$RUNS）"
