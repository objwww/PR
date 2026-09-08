#!/bin/sh
# ============================================================================
# e2e-am6-02-engine-comparison.sh —— E2E-AM6-02：M6-02 观察面成账
#                                （V32 engine_comparison；[195] 部署段真栈）
#
# 必断言（AM6 落码方案 M6-02 验收 + 架构 v1.2 §736/§739 纪律）：
#   ① 前置：nativeReady=true + percent=0 姿态；注入新告警 → BUCKETED_HOLMES
#     主路径 run 至 SUCCEEDED 且报告在场（holmes 侧结论源）；
#   ② 影子触发（195 部署配方 §5 方式 A 一次性 runner）：镜像 holmes 身份
#     （同 incident/同 generation/同 investigation_hash）落影子 run，终态 REPORTING；
#   ③ 对照落账：engine_comparison 恰 1 行（native_run_id=影子 run，
#     shadow_exec_ref=am4-shadow-trigger，snapshot_digest=holmes investigation_hash）；
#     holmes 侧 validation_status 在场且非 report_missing（结论可解析）；
#     native 侧 root_cause 三元组在场；
#   ④ 分歧标记纪律：flags 只携 {dim,holmes,native} 原始值——零 winner/correct/
#     verdict 判语字段（§736 无 GT 只记 disagreement 不判对错）；
#   ⑤ 零报告零发布：影子 run 的 rca_report / report_publication / notify_outbox
#     全零（对照行不是报告/发布——零报告纪律不变）；
#   ⑥ 二次触发停发：同 incident 已有影子 run 占活跃位（uq_rca_run_active_incident
#     ——REPORTING 属活跃态；若 incident 已有 NATIVE 路由 run 则 C-61 先拒），
#     exit 非 0 + engine_comparison 仍恰 1 行；
#   ⑦ 四维台账 SQL 面可执行留证（预算/延迟/错误率/人工分歧四段；
#     同窗对照禁 before/after）；
#   ⑧ percent=10 放量姿态（零代码放量：bundle 发布/激活 + status percent=10）→
#     立即回退 percent=0（status 回 0 + active=回滚靶）。
#
# 用法（195 部署段）：经 m6-run-scenario.sh 包裹（bearer/PG env 注入 + 脱敏）
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am6-common.sh"

SUITE="$(am6_suite_run_id)"
RUNS="${AM6_RUNS_DIR:-./runs}/${SUITE}-am6-02"
mkdir -p "$RUNS"
SFX="$(echo "${SUITE}" | tr 'A-Z' 'a-z')"

am6_log "E2E-AM6-02 开始 suite=$SUITE runs=$RUNS"
am6_resource_snapshot "$RUNS" "start"

# ---------------------------------------------------------------------------
# phase0 前置姿态 + holmes 主路径 run（对照的 holmes 侧结论源）
# ---------------------------------------------------------------------------
am6_log "phase0 前置：nativeReady=true + percent=0 姿态 + holmes 主路径 run"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status.json" >/dev/null
grep -q '"nativeReady":true' "$RUNS/status.json" \
    || am6_fail "phase0 前置不满足 nativeReady 非 true: $(cat "$RUNS/status.json")"
grep -q '"percent":0' "$RUNS/status.json" \
    || am6_fail "phase0 前置不满足：期望 percent=0 姿态: $(cat "$RUNS/status.json")"
DACT="$(am6_active_digest "$RUNS")"
[ -n "$DACT" ] || am6_fail "phase0 无 active bundle"

RAW="alertname=higherrorrate|service=am6e2e02-holmes-${SFX}"
HKEY="${RAW}:${RAW}"
_code="$(am6_inject_alert HighErrorRate "am6e2e02-holmes-${SFX}" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase0 注入期望 202 实得 $_code: $(cat "$RUNS/alert-am6e2e02-holmes-${SFX}-firing.resp")"
am6_db_poll_ge "phase0 BUCKETED_HOLMES 审计行" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision d JOIN rca_run r ON r.id=d.run_id
     WHERE d.stickiness_key='${HKEY}' AND d.bundle_digest='${DACT}'
       AND d.decision='BUCKETED_HOLMES' AND r.engine='HOLMES'" 1
HRUN="$(am6_psql_ro AM6_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE stickiness_key='${HKEY}' AND decision='BUCKETED_HOLMES'
    AND bundle_digest='${DACT}' ORDER BY id DESC LIMIT 1" '-At')"
am6_log "  holmes 主路径 run=$HRUN（真栈 worker + 真 LLM，超时 300s）"
am6_db_poll_ge "phase0 holmes run SUCCEEDED" 300 AM6_PG_URL \
    "SELECT count(*) FROM rca_run WHERE id='${HRUN}' AND state='SUCCEEDED'
     AND engine='HOLMES'" 1
am6_db_poll_ge "phase0 holmes 报告在场（结论源）" 60 AM6_PG_URL \
    "SELECT count(*) FROM rca_report WHERE run_id='${HRUN}'" 1
am6_log "phase0 PASS"

# ---------------------------------------------------------------------------
# phase1 影子触发（一次性 runner）→ 影子 run REPORTING
# ---------------------------------------------------------------------------
am6_log "phase1 影子触发（docker,am4-shadow-trigger + web=none + --no-deps）"
cd /opt/build/pr/deploy
docker compose run --rm --no-deps control-app \
    --spring.profiles.active=docker,am4-shadow-trigger \
    --spring.main.web-application-type=none \
    --am4.shadow-trigger.holmes-run-id="${HRUN}" 2>&1 \
    | am6_redact > "$RUNS/shadow-trigger.log"
grep -q 'AM4_SHADOW_RUN_ID=' "$RUNS/shadow-trigger.log" \
    || am6_fail "phase1 runner 无 run id 标记: $(tail -5 "$RUNS/shadow-trigger.log")"
SRUN="$(sed -n 's/.*AM4_SHADOW_RUN_ID=\([0-9a-f][0-9a-f-]*\).*/\1/p' \
    "$RUNS/shadow-trigger.log" | tail -1)"
grep 'AM4_SHADOW_TASK=' "$RUNS/shadow-trigger.log" > "$RUNS/shadow-tasks.txt" || true
am6_log "  影子 run=$SRUN；任务结局: $(tr '\n' ' ' < "$RUNS/shadow-tasks.txt")"
am6_db_poll_ge "phase1 影子 run 终态 REPORTING" 60 AM6_PG_URL \
    "SELECT count(*) FROM rca_run WHERE id='${SRUN}' AND state='REPORTING'" 1
am6_psql_ro AM6_PG_URL "SELECT id||'|'||engine||'|'||state||'|'||generation
    ||'|'||investigation_hash FROM rca_run WHERE id IN ('${HRUN}','${SRUN}')
    ORDER BY id" '-At' > "$RUNS/shadow-runs.txt"
am6_log "phase1 PASS（run 面: $(tr '\n' ' ' < "$RUNS/shadow-runs.txt")）"

# ---------------------------------------------------------------------------
# phase2 对照落账断言（V32 engine_comparison 形态/纪律）
# ---------------------------------------------------------------------------
am6_log "phase2 对照落账断言"
ECW="SELECT count(*) FROM engine_comparison WHERE native_run_id='${SRUN}'"
am6_db_poll_ge "phase2 对照行恰 1（uq_ec_pair 锚 + 快照=holmes investigation_hash）" 30 \
    AM6_PG_URL "SELECT count(*) FROM engine_comparison WHERE native_run_id='${SRUN}'
     AND shadow_exec_ref='am4-shadow-trigger'
     AND snapshot_digest=(SELECT investigation_hash FROM rca_run WHERE id='${HRUN}')" 1
_cnt="$(am6_psql_ro AM6_PG_URL "$ECW" '-At')"
[ "$_cnt" = "1" ] || am6_fail "phase2 对照行数=$_cnt 期望恰 1"
_h="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM engine_comparison
    WHERE native_run_id='${SRUN}' AND holmes_outcome ? 'validation_status'
    AND coalesce((holmes_outcome->>'report_missing')::boolean, false) = false" '-At')"
[ "$_h" = "1" ] || am6_fail "phase2 holmes 侧结论缺数（validation_status/report_missing 面）"
_n="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM engine_comparison
    WHERE native_run_id='${SRUN}' AND native_outcome ? 'root_cause'" '-At')"
[ "$_n" = "1" ] || am6_fail "phase2 native 侧 root_cause 三元组缺席"
_bad="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM engine_comparison
    WHERE native_run_id='${SRUN}' AND (disagree_flags::text ILIKE '%winner%'
      OR disagree_flags::text ILIKE '%correct%' OR disagree_flags::text ILIKE '%verdict%')" '-At')"
[ "$_bad" = "0" ] || am6_fail "phase2 flags 含判语字段（违 §736 无 GT 不判对错）"
_zero="$(am6_psql_ro AM6_PG_URL "SELECT
      (SELECT count(*) FROM rca_report WHERE run_id='${SRUN}') || '|' ||
      (SELECT count(*) FROM report_publication p JOIN rca_report rr
         ON p.report_id=rr.id WHERE rr.run_id='${SRUN}') || '|' ||
      (SELECT count(*) FROM notify_outbox WHERE report_id IN
         (SELECT id FROM rca_report WHERE run_id='${SRUN}'))" '-At')"
[ "$_zero" = "0|0|0" ] || am6_fail "phase2 零报告/发布/外发被破: $_zero"
am6_psql_ro AM6_PG_URL "SELECT native_run_id, shadow_exec_ref, snapshot_digest,
    holmes_outcome, native_outcome, disagree_flags, cost_compare, created_at
    FROM engine_comparison WHERE native_run_id='${SRUN}'" \
    > "$RUNS/comparison-row.txt"
am6_log "phase2 PASS（对照行落账，零报告零发布，flags 零判语）"

# ---------------------------------------------------------------------------
# phase2b 二次触发停发：活跃位互斥（uq_rca_run_active_incident 或 C-61）
#   （195 实证 2026-09-08：影子 run engine 列落 DB 默认 HOLMES、不经
#   CanaryRouter，故 C-61（NATIVE 路由 run 判定面）不触发；首影子 run
#   REPORTING 属活跃态，二次 insert 撞 uq_rca_run_active_incident——结构性
#   拒绝面，与 C-61 同为合规停发路径，两守卫任一即过）
# ---------------------------------------------------------------------------
am6_log "phase2b 二次触发停发（活跃位互斥）"
if docker compose run --rm --no-deps control-app \
        --spring.profiles.active=docker,am4-shadow-trigger \
        --spring.main.web-application-type=none \
        --am4.shadow-trigger.holmes-run-id="${HRUN}" \
        > "$RUNS/shadow-trigger-c61.log" 2>&1; then
    am6_fail "phase2b 二次触发应失败（活跃位互斥/C-61）"
fi
grep -qE 'C-61|uq_rca_run_active_incident' "$RUNS/shadow-trigger-c61.log" \
    || am6_fail "phase2b 拒绝理由非活跃位互斥: $(tail -3 "$RUNS/shadow-trigger-c61.log")"
_cnt="$(am6_psql_ro AM6_PG_URL "$ECW" '-At')"
[ "$_cnt" = "1" ] || am6_fail "phase2b 被拒触发不得再落行（实 $_cnt）"
am6_log "phase2b PASS（二次触发被拒，engine_comparison 仍恰 1 行）"

# ---------------------------------------------------------------------------
# phase3 四维台账 SQL 留证
# ---------------------------------------------------------------------------
am6_log "phase3 四维台账 SQL 执行留证"
docker exec -i deploy-postgres-1 sh -c \
    'psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1' \
    < /opt/build/pr/deploy/policy/m6-engine-observation.sql \
    > "$RUNS/observation-ledger.txt" 2>&1
grep -q 'engine_comparison' "$RUNS/observation-ledger.txt" \
    || am6_fail "phase3 台账输出异常: $(tail -3 "$RUNS/observation-ledger.txt")"
am6_log "phase3 PASS（$RUNS/observation-ledger.txt）"

# ---------------------------------------------------------------------------
# phase4 percent=10 放量姿态（零代码放量）→ 立即回退 percent=0
# ---------------------------------------------------------------------------
am6_log "phase4 percent=10 放量姿态 + 立即回退"
printf '{"policy_version":"am6-e2e-02-canary10-%s","canary":{"percent":10,"max_native_runs":50},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}' "$SUITE" \
    > "$RUNS/bundle-canary10.content"
D10="$(am6_publish_bundle "$RUNS/bundle-canary10.content" canary10)"
am6_activate "$D10" "$RUNS"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-p10.json" >/dev/null
grep -q '"percent":10' "$RUNS/status-p10.json" \
    || am6_fail "phase4 status percent 非 10: $(cat "$RUNS/status-p10.json")"
am6_log "  percent=10 生效 active=$D10"
printf '{"policy_version":"am6-e2e-02-rollback-%s","canary":{"percent":0}}' "$SUITE" \
    > "$RUNS/bundle-rollback.content"
DRB="$(am6_publish_bundle "$RUNS/bundle-rollback.content" rollback)"
am6_rollback "$DRB" "$RUNS"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-rollback.json" >/dev/null
grep -q '"percent":0' "$RUNS/status-rollback.json" \
    || am6_fail "phase4 回退后 percent 非 0: $(cat "$RUNS/status-rollback.json")"
_ad="$(am6_active_digest "$RUNS")"
[ "$_ad" = "$DRB" ] || am6_fail "phase4 回退后 active=$_ad 期望 $DRB"
am6_log "phase4 PASS（percent=10 → 回退 percent=0，active=$DRB）"

am6_resource_snapshot "$RUNS" "end"
am6_scenario_result "$RUNS" "E2E-AM6-02" \
    "M6-02 观察面成账（V32 对照落账/零报告纪律/二次触发停发/四维台账/10% 放量回退）" \
    "real-stack-195" "PASS"
am6_log "E2E-AM6-02 PASS（suite=$SUITE，证据=$RUNS）"
