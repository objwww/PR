#!/bin/sh
# ============================================================================
# e2e-am6-05-holmes-shadow.sh —— E2E-AM6-05：M6-05 Holmes 只读对照期
#                          生产面全链（V34 影子工作面；[195] 部署段真栈；
#                          经 m6-run-scenario.sh 包裹）
#
# 必断言（AM6 落码方案 M6-05 演练段；SKIP LOCKED 并发/租约/CAS/有界重试/
#          授权面归 PostgresHolmesShadowIT 真 PG 实证，本场景证生产面全链）：
#   ① DRILL 姿态：先落 percent=0 安全锚（自包含恢复位）→ percent=100 且带
#     native.proposal（NATIVE 必成功姿态）→ 注入新告警 → BUCKETED_NATIVE →
#     NATIVE run 真 agent 执行至 SUCCEEDED，生产收尾链完整（报告/赢家/发布/
#     通知照常——对照期主路径零惊扰）；
#   ② 确定性抽样入队：holmes_shadow_work 恰 1 行 COMPARISON
#     （shadow_key=holmes-shadow:<native_run>，snapshot_digest=native
#     investigation_hash），scheduler 真 SKIP LOCKED 认领执行至 SUCCEEDED
#     （attempts=1 恰一次）；
#   ③ 影子锚真 FK 链：影子 run（engine=HOLMES DB 默认/trigger=RERUN/终态
#     SUCCEEDED 自收 finished_at，同 incident 同 generation）/ task DONE /
#     attempt SUCCEEDED；对照落账 engine_comparison 恰 1 行
#     （shadow_exec_ref=holmes-shadow-worker，holmes 侧 validation_status
#     非 report_missing——真 LLM 执行）；
#   ④ 零发布面纪律（不铸生产 run 不调 finishTask，C-65）：影子 run 的
#     rca_report / report_publication / notify_outbox / 赢家行全零；
#     incident.current_rca_run_id 不被影子 run 占据（195 实证：NATIVE 成功
#     收尾后指针保持 null——finishTask 不移指针，移动仅 FallbackService 面）；
#   ⑤ 底噪校准：holmes-calib:<native_run> CALIBRATION 行 SUCCEEDED →
#     校准对照行恰 1（同 native_run_id 锚 + noise_baseline jsonb 在场：
#     native_run_id/baseline_run_id/calibration_run_id/snapshot_digest/
#     disagree/dims 六键；双侧 engine 标签 HOLMES vs HOLMES_CALIB；
#     校准 run 同快照 investigation_hash）；
#   ⑥ 工作面健康：EXHAUSED=0、24h 窗入队 ≤ 日预算 20（预算预留面）；
#   ⑦ 终态回 percent=0（回滚靶=预演 active，验证后不留放量态）。
#
# 用法（195 部署段）：经 m6-run-scenario.sh 包裹（bearer/PG env 注入 + 脱敏）
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am6-common.sh"

SUITE="$(am6_suite_run_id)"
RUNS="${AM6_RUNS_DIR:-./runs}/${SUITE}-am6-05"
mkdir -p "$RUNS"
SFX="$(echo "${SUITE}" | tr 'A-Z' 'a-z')"
SVC="am6e2e05-shadow-${SFX}"
RAW="alertname=higherrorrate|service=${SVC}"
HKEY="${RAW}:${RAW}"

am6_log "E2E-AM6-05 开始 suite=$SUITE runs=$RUNS"
am6_resource_snapshot "$RUNS" "start"

# ---------------------------------------------------------------------------
# phase0 DRILL 姿态：percent=100 且带 native.proposal（NATIVE 必成功姿态）
# ---------------------------------------------------------------------------
am6_log "phase0 DRILL 姿态：percent=100 + native.proposal（抽样闸已开）"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-pre.json" >/dev/null
grep -q '"nativeReady":true' "$RUNS/status-pre.json" \
    || am6_fail "phase0 前置不满足 nativeReady 非 true: $(cat "$RUNS/status-pre.json")"
printf '{"policy_version":"am6-e2e-05-anchor-%s","canary":{"percent":0}}' "$SUITE" \
    > "$RUNS/bundle-anchor.content"
PRE="$(am6_publish_bundle "$RUNS/bundle-anchor.content" anchor)"
am6_activate "$PRE" "$RUNS"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-anchor.json" >/dev/null
grep -q '"percent":0' "$RUNS/status-anchor.json" \
    || am6_fail "phase0 锚非 percent=0: $(cat "$RUNS/status-anchor.json")"
am6_log "  预演锚 active=$PRE（percent=0，回滚靶）"
cat > "$RUNS/bundle-p100.content" <<EOF
{"policy_version":"am6-e2e-05-p100-${SUITE}","canary":{"percent":100,"max_native_runs":50},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
D100="$(am6_publish_bundle "$RUNS/bundle-p100.content" p100)"
am6_activate "$D100" "$RUNS"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-p100.json" >/dev/null
grep -q '"percent":100' "$RUNS/status-p100.json" \
    || am6_fail "phase0 status percent 非 100: $(cat "$RUNS/status-p100.json")"
am6_log "  percent=100 生效 active=$D100（带 proposal）"

# ---------------------------------------------------------------------------
# phase1 NATIVE 真 agent 执行至 SUCCEEDED（生产收尾链完整——对照期零惊扰）
# ---------------------------------------------------------------------------
am6_log "phase1 注入告警 → BUCKETED_NATIVE → 真 agent NATIVE SUCCEEDED"
_code="$(am6_inject_alert HighErrorRate "$SVC" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase1 注入期望 202 实得 $_code: $(cat "$RUNS/alert-${SVC}-firing.resp")"am6_db_poll_ge "phase1 BUCKETED_NATIVE 审计行" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${HKEY}'
     AND decision='BUCKETED_NATIVE' AND bundle_digest='${D100}'" 1
NRUN=""
_n=0
while [ -z "$NRUN" ] && [ "$_n" -lt 40 ]; do
    NRUN="$(am6_psql_ro AM6_PG_URL "SELECT run_id FROM canary_route_decision
        WHERE stickiness_key='${HKEY}' AND decision='BUCKETED_NATIVE'
        AND bundle_digest='${D100}' ORDER BY id DESC LIMIT 1" '-At' 2>/dev/null || true)"
    [ -z "$NRUN" ] && sleep 3 && _n=$((_n + 3))
done
[ -n "$NRUN" ] || am6_fail "phase1 NATIVE run id 获取失败（120s 重试空）"
am6_log "  route 行取回 run=$NRUN（重试 ${_n}s；digest=$D100 key=$HKEY）"
INC="$(am6_psql_ro AM6_PG_URL "SELECT incident_id FROM rca_run WHERE id='${NRUN}'" '-At')"
GEN="$(am6_psql_ro AM6_PG_URL "SELECT generation FROM rca_run WHERE id='${NRUN}'" '-At')"
am6_log "  NATIVE run=$NRUN incident=$INC generation=$GEN（真栈 agents，超时 420s）"
am6_db_poll_ge "phase1 NATIVE run SUCCEEDED" 420 AM6_PG_URL \
    "SELECT count(*) FROM rca_run WHERE id='${NRUN}' AND state='SUCCEEDED'
     AND engine='NATIVE'" 1
am6_db_poll_ge "phase1 NATIVE 生产报告在场" 60 AM6_PG_URL \
    "SELECT count(*) FROM rca_report WHERE run_id='${NRUN}'" 1
am6_log "phase1 PASS（NATIVE 主路径生产收尾链完整，对照期零惊扰）"

# ---------------------------------------------------------------------------
# phase2 确定性抽样入队 + scheduler 影子执行 + 对照落账
# ---------------------------------------------------------------------------
am6_log "phase2 抽样入队 → 影子执行（真 LLM）→ V32 对照恰 1 行"
WORKKEY="holmes-shadow:${NRUN}"
am6_db_poll_ge "phase2 COMPARISON 工作行入队" 60 AM6_PG_URL \
    "SELECT count(*) FROM holmes_shadow_work WHERE shadow_key='${WORKKEY}'
     AND kind='COMPARISON' AND native_run_id='${NRUN}' AND incident_id='${INC}'
     AND snapshot_digest=(SELECT investigation_hash FROM rca_run WHERE id='${NRUN}')" 1
am6_db_poll_ge "phase2 影子执行 SUCCEEDED（真 LLM，SKIP LOCKED 恰一次）" 300 AM6_PG_URL \
    "SELECT count(*) FROM holmes_shadow_work WHERE shadow_key='${WORKKEY}'
     AND state='SUCCEEDED' AND attempts=1 AND tokens_spent IS NOT NULL" 1
_SRUN="$(am6_psql_ro AM6_PG_URL "SELECT id FROM rca_run
    WHERE incident_id='${INC}' AND id <> '${NRUN}' AND engine='HOLMES'
    AND trigger_kind='RERUN' AND state='SUCCEEDED' AND finished_at IS NOT NULL" '-At')"
[ "$(printf '%s\n' "$_SRUN" | grep -c .)" = "1" ] \
    || am6_fail "phase2 影子 run 应恰 1 条实得: $_SRUN"
am6_log "  影子 run=$_SRUN（engine=HOLMES DB 默认/RERUN/终态自收）"
am6_db_poll_ge "phase2 影子 task DONE + attempt SUCCEEDED" 30 AM6_PG_URL \
    "SELECT count(*) FROM rca_task t JOIN rca_attempt a ON a.task_id=t.id
     WHERE t.run_id='${_SRUN}' AND t.state='DONE'
       AND a.status='SUCCEEDED'" 1
am6_db_poll_ge "phase2 对照行恰 1（holmes-shadow-worker 锚 + 真执行证据）" 30 AM6_PG_URL \
    "SELECT count(*) FROM engine_comparison WHERE native_run_id='${NRUN}'
     AND shadow_exec_ref='holmes-shadow-worker'
     AND snapshot_digest=(SELECT investigation_hash FROM rca_run WHERE id='${NRUN}')
     AND holmes_outcome ? 'validation_status'
     AND coalesce((holmes_outcome->>'report_missing')::boolean, false) = false
     AND native_outcome ? 'root_cause'" 1
am6_psql_ro AM6_PG_URL "SELECT id, state, kind, attempts, lease_epoch, tokens_spent
    FROM holmes_shadow_work WHERE shadow_key='${WORKKEY}'" > "$RUNS/work-row.txt"
am6_psql_ro AM6_PG_URL "SELECT native_run_id, shadow_exec_ref, snapshot_digest,
    holmes_outcome, native_outcome, disagree_flags, cost_compare
    FROM engine_comparison WHERE native_run_id='${NRUN}'
    AND shadow_exec_ref='holmes-shadow-worker'" > "$RUNS/comparison-row.txt"
am6_log "phase2 PASS（入队恰一/执行恰一/对照落账，留痕 work-row.txt+comparison-row.txt）"

# ---------------------------------------------------------------------------
# phase3 零发布面纪律（C-65：影子 run 不经 finishTask）
# ---------------------------------------------------------------------------
am6_log "phase3 零发布面：报告/发布/外发/赢家全零 + incident 指针不上移"
_zero="$(am6_psql_ro AM6_PG_URL "SELECT
      (SELECT count(*) FROM rca_report WHERE run_id='${_SRUN}') || '|' ||
      (SELECT count(*) FROM report_publication p JOIN rca_report rr
         ON p.report_id=rr.id WHERE rr.run_id='${_SRUN}') || '|' ||
      (SELECT count(*) FROM notify_outbox WHERE report_id IN
         (SELECT id FROM rca_report WHERE run_id='${_SRUN}')) || '|' ||
      (SELECT count(*) FROM report_generation_winner WHERE winner_run_id='${_SRUN}')" '-At')"
[ "$_zero" = "0|0|0|0" ] || am6_fail "phase3 影子 run 发布面被破: $_zero"
_ptr="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM incident
    WHERE id='${INC}' AND current_rca_run_id='${_SRUN}'" '-At')"
[ "$_ptr" = "0" ] || am6_fail "phase3 incident 指针被影子 run 占据（实 $_ptr）"
_ptrval="$(am6_psql_ro AM6_PG_URL "SELECT coalesce(current_rca_run_id::text,'null')
    FROM incident WHERE id='${INC}'" '-At')"
am6_log "phase3 PASS（零报告/零发布/零外发/零赢家；指针未动（195 实证 finishTask 不移 incident 指针，指针移动仅 FallbackService 面）: $ptrval）"

# ---------------------------------------------------------------------------
# phase4 底噪校准（同快照 Holmes control-vs-control）
# ---------------------------------------------------------------------------
am6_log "phase4 底噪校准：CALIBRATION 执行 → noise_baseline 回填"
CALIBKEY="holmes-calib:${NRUN}"
am6_db_poll_ge "phase4 CALIBRATION 工作行 SUCCEEDED" 300 AM6_PG_URL \
    "SELECT count(*) FROM holmes_shadow_work WHERE shadow_key='${CALIBKEY}'
     AND kind='CALIBRATION' AND state='SUCCEEDED'" 1
am6_db_poll_ge "phase4 校准对照行恰 1（noise_baseline 六键 + 双侧标签）" 30 AM6_PG_URL \
    "SELECT count(*) FROM engine_comparison WHERE native_run_id='${NRUN}'
     AND shadow_exec_ref='holmes-shadow-worker' AND noise_baseline IS NOT NULL
     AND noise_baseline ? 'native_run_id' AND noise_baseline ? 'baseline_run_id'
     AND noise_baseline ? 'calibration_run_id' AND noise_baseline ? 'snapshot_digest'
     AND noise_baseline ? 'disagree' AND noise_baseline ? 'dims'
     AND holmes_outcome->>'engine' = 'HOLMES'
     AND native_outcome->>'engine' = 'HOLMES_CALIB'" 1
_CALRUN="$(am6_psql_ro AM6_PG_URL "SELECT noise_baseline->>'calibration_run_id'
    FROM engine_comparison WHERE native_run_id='${NRUN}'
    AND shadow_exec_ref='holmes-shadow-worker' AND noise_baseline IS NOT NULL" '-At')"
_snap="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM rca_run
    WHERE id='${_CALRUN}' AND investigation_hash=
      (SELECT investigation_hash FROM rca_run WHERE id='${NRUN}')" '-At')"
[ "$_snap" = "1" ] || am6_fail "phase4 校准 run 非同快照（investigation_hash 不一致）"
am6_psql_ro AM6_PG_URL "SELECT noise_baseline FROM engine_comparison
    WHERE native_run_id='${NRUN}' AND shadow_exec_ref='holmes-shadow-worker'
    AND noise_baseline IS NOT NULL" > "$RUNS/noise-baseline.txt"
am6_log "phase4 PASS（校准行落账 noise_baseline，同快照 run=$_CALRUN，留痕 noise-baseline.txt）"

# ---------------------------------------------------------------------------
# phase5 工作面健康 + 一键切回
# ---------------------------------------------------------------------------
am6_log "phase5 工作面健康（EXHAUSED=0/预算内）+ 一键切回 percent=0"
_health="$(am6_psql_ro AM6_PG_URL "SELECT
      (SELECT count(*) FROM holmes_shadow_work WHERE state='EXHAUSTED') || '|' ||
      (SELECT count(*) FROM holmes_shadow_work
        WHERE created_at > now() - interval '24 hours')" '-At')"
_exh="$(printf '%s' "$_health" | cut -d'|' -f1)"
_w24="$(printf '%s' "$_health" | cut -d'|' -f2)"
[ "$_exh" = "0" ] || am6_fail "phase5 工作面出现 EXHAUSTED 行: $_exh"
[ "$_w24" -le 20 ] || am6_fail "phase5 24h 入队超日预算 20: $_w24"
echo "exhausted=$_exh work_rows_24h=$_w24 daily_budget=20" > "$RUNS/face-health.txt"
am6_rollback "$PRE" "$RUNS"
am6_poll_until "phase5 status 回 percent=0" 60 \
    "am6_http GET /api/canary/status AM6_RELEASE_BEARER '' $RUNS/status-rollback.json >/dev/null && grep -q '\\\"percent\\\":0' $RUNS/status-rollback.json"
_ad="$(am6_active_digest "$RUNS")"
[ "$_ad" = "$PRE" ] || am6_fail "phase5 active 未回预演靶: $_ad 期望 $PRE"
am6_log "phase5 PASS（工作面健康 24h=$_w24/20；active=$PRE）"

am6_resource_snapshot "$RUNS" "end"
am6_scenario_result "$RUNS" "E2E-AM6-05" \
    "M6-05 Holmes 只读对照期全链（抽样入队/影子执行恰一/V32 对照+底噪校准/零发布面/工作面健康/切回）" \
    "real-stack-195" "PASS"
am6_log "E2E-AM6-05 PASS（suite=$SUITE，证据=$RUNS）"
