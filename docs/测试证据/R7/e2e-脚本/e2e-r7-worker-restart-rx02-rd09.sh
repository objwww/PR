#!/bin/sh
# ============================================================================
# e2e-r7-worker-restart-rx02-rd09.sh —— RX02/RD09 真 worker 重启恢复断言
# （R7 执行日志 §9 NOT_RUN 主责例：RX02 重启恢复绑定/RD09 WAITING_CHILDREN 重启）
#
# 场景：委派诱导案跑到 WAITING_CHILDREN（子任务在场、父任务持久等待）→ 对
#       control-app SIGKILL 硬杀 + 原容器拉起 → 断言恢复纪律：
#   RX02 面（绑定恢复，不猜 latest）：
#     ① 绑定快照 kill 前后逐行恒等（role/version/digest/input_refs/schema 冻结件
#        不漂移不重写——恢复只读 rca_task_execution_binding）
#     ② rca_model_call.role_digest 全落绑定 digest 集合内（执行期身份=绑定身份，
#        无 latest 串用）
#   RD09 面（检查点恢复，无上下文丢失）：
#     ③ run 达 SUCCEEDED；任务零 DEAD（运行器解析成功=CX 行为面；CAPABILITY_
#        UNAVAILABLE 会以 DEAD 留证）
#     ④ 检查点计数单调：post steps_used ≥ pre 且 decision_seq ≥ pre（续走不回退）
#     ⑤ rca_model_call (task,attempt,action_seq,physical_seq) 零重复组（重驱动
#        不重复执行、不重复计费）
#     ⑥ 同 incident 恰 1 run（重启不复活重复 run）
#
# 前置：主模式部署形态 + R7_ALLOW_DESTRUCTIVE=1；R7_RESTART_WAIT_TIMEOUT 检查点
#   等待上限（默认 300s——真模型不出委派则如实 FAIL，案面重调）；硬杀面同
#   atomicity 脚本（docker kill/start）。
# ============================================================================

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/e2e-r7-common.sh"

WAIT_TO="${R7_RESTART_WAIT_TIMEOUT:-300}"
SESSION="$(r7_suite_run_id)"
RUNS="${R7_RUNS_DIR:-./runs}/restart-${SESSION}"
mkdir -p "$RUNS"
R7_KILL_LOG="$RUNS/kill-restart.log"
SFX="$(date -u +%H%M%S)"
SVC="r7restart-${SFX}"
AN="RestartProbe"
r7_require_destructive
r7_log "runs=$RUNS service=$SVC"

r7_resource_snapshot "$RUNS" "pre"

# ---------------------------------------------------------------------------
# phase1 姿态 + bundle + 注入
# ---------------------------------------------------------------------------
r7_health "$RUNS"
# 白名单匹配面对 incident_key 原样大小写敏感（195 真窗差分实证：原样匹配
# WHITELISTED；小写化条目→BUCKETED_HOLMES 不铸 run——b-gate/atomicity 同雷第四处）
_wl_raw="alertname=${AN}|service=${SVC}"
_key_l="alertname=$(echo "$AN" | tr '[:upper:]' '[:lower:]')|service=${SVC}"
cat > "$RUNS/bundle-restart.content" <<EOF
{"policy_version":"r7-e2e-restart-${SESSION}","canary":{"percent":0,"whitelist":["${_wl_raw}"],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
DR="$(r7_publish_bundle "$RUNS/bundle-restart.content" restart)"
r7_activate "$DR" "$RUNS"
T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
r7_log "phase1 注入 ${AN}@$SVC（复杂多域 summary 诱导委派；attempt-1 简版 summary
    实证简案面不触发委派——案面重调即本脚本头登记的路径）"
# 委派诱导 fixture：真实复杂事故形态（跨三域+疑似变更源+明示范围超出单次调查覆盖），
# 只描述案面复杂度，不注入任何结论/答案（phase3 断言口径不变）
_code="$(r7_inject_alert "$AN" "$SVC" firing "$RUNS" \
    "SEV2 复合事故：checkout p99 延迟 1.8s（阈值 0.5s）+ 支付回调错误率 4.2% + 网关 5xx 抖动；deploy-2431 后疑配置漂移；涉及 payment/inventory/gateway 三域，需变更面 diff 取证与日志面深挖等并行专项子调查，单次调查覆盖不足")"
[ "$_code" = "202" ] || r7_fail "phase1 注入期望 202 实得 $_code"
_key="${_key_l}:${_key_l}"
r7_db_poll_ge "phase1 WHITELISTED 原子对" 120 R7_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${_key}'
     AND decision='WHITELISTED' AND bundle_digest='${DR}' AND run_id IS NOT NULL" 1
RUNID="$(r7_psql_ro R7_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE stickiness_key='${_key}' AND decision='WHITELISTED'
    AND bundle_digest='${DR}' ORDER BY id DESC LIMIT 1" '-At')"
r7_log "phase1 PASS（run=$RUNID）"

# ---------------------------------------------------------------------------
# phase2 等 WAITING_CHILDREN + 委派批在场 → 杀前快照 → 硬杀重启
# ---------------------------------------------------------------------------
r7_log "phase2 等 WAITING_CHILDREN 检查点 + 子任务在场（${WAIT_TO}s 上限）"
r7_db_poll_ge "phase2 WAITING_CHILDREN 检查点" "$WAIT_TO" R7_PG_URL \
    "SELECT count(*) FROM rca_primary_checkpoint
     WHERE run_id='${RUNID}' AND phase='WAITING_CHILDREN'" 1
r7_db_poll_ge "phase2 DELEGATE 子任务在场" 30 R7_PG_URL \
    "SELECT count(*) FROM rca_task WHERE run_id='${RUNID}'
     AND task_key LIKE 'DELEGATE-%'" 1
sleep 2   # 让委派批事务与绑定写完全落库后再拍快照/杀（窗口富余）

_started_pre="$(r7_container_started_at)"
r7_binding_snapshot R7_PG_URL "$RUNID" "$RUNS/bindings-pre.txt"
r7_psql_ro R7_PG_URL "SELECT task_id||'|'||round_id||'|'||phase||'|'||decision_seq||'|'||
    steps_used||'|'||batches_used FROM rca_primary_checkpoint
    WHERE run_id='${RUNID}' ORDER BY task_id" '-At' > "$RUNS/checkpoint-pre.txt"
r7_psql_ro R7_PG_URL "SELECT gap_id||'|'||status||'|'||coalesce(child_task_id::text,'')
    FROM rca_delegation_decision WHERE run_id='${RUNID}' ORDER BY gap_id" '-At' \
    > "$RUNS/decisions-pre.txt"
[ -s "$RUNS/bindings-pre.txt" ] || r7_fail "phase2 杀前绑定快照为空（姿态/时序异常）"
r7_log "phase2 快照齐（StartedAt=$_started_pre；杀）"

r7_container_kill
_killed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
r7_container_start
r7_poll_until "phase2 重启后 health 回 200" 180 "( r7_health $RUNS )"
r7_log "phase2 容器已拉起（killed_at=$_killed_at），等恢复后终态"

# 终态等待 1800s：恢复=租约超时(~10min)+glm-5 全重驱(~5min)≈16min（atomicity attempt-2 实证）
STATE="$(r7_wait_run_terminal R7_PG_URL "$RUNID" 1800)"

# ---------------------------------------------------------------------------
# phase3 恢复纪律断言
# ---------------------------------------------------------------------------
r7_log "phase3 断言（终态=$STATE）"
[ "$STATE" = "SUCCEEDED" ] || r7_fail "phase3 run 终态=$STATE（RD09 要求恢复收敛 SUCCEEDED；\
若 DEAD 带 CAPABILITY_UNAVAILABLE → 绑定解析/姿态面取证）"

# ① 绑定恒等（RX02）
r7_binding_snapshot R7_PG_URL "$RUNID" "$RUNS/bindings-post.txt"
if ! diff "$RUNS/bindings-pre.txt" "$RUNS/bindings-post.txt" > "$RUNS/bindings.diff" 2>&1; then
    r7_fail "phase3 绑定快照漂移（恢复只读纪律破）: $(cat "$RUNS/bindings.diff")"
fi

# ② 执行期身份=绑定身份（不猜 latest）
_notbound="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call m
    WHERE m.run_id='${RUNID}' AND NOT EXISTS
      (SELECT 1 FROM rca_task_execution_binding b
       WHERE b.task_id=m.task_id AND b.role_id=m.role_id
         AND b.role_version=m.role_version AND b.role_digest=m.role_digest)" '-At')"
[ "$_notbound" = "0" ] || r7_fail "phase3 ${_notbound} 笔模型调用身份越出绑定三元组（latest 串用面）"

# ③ 任务零 DEAD
_ndead="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
    WHERE run_id='${RUNID}' AND state='DEAD'" '-At')"
[ "$_ndead" = "0" ] || r7_fail "phase3 ${_ndead} 个任务 DEAD（恢复面运行器解析失败留证）"

# ④ 检查点单调
r7_psql_ro R7_PG_URL "SELECT task_id||'|'||round_id||'|'||phase||'|'||decision_seq||'|'||
    steps_used||'|'||batches_used FROM rca_primary_checkpoint
    WHERE run_id='${RUNID}' ORDER BY task_id" '-At' > "$RUNS/checkpoint-post.txt"
_mono="$(awk -F'|' '
    NR==FNR { pre[$1]=FNR; ps[FNR]=$4; pt[FNR]=$5; next }
    { if ($1 in pre && ($4 < ps[pre[$1]] || $5 < pt[pre[$1]])) bad++ }
    END { print bad+0 }' "$RUNS/checkpoint-pre.txt" "$RUNS/checkpoint-post.txt")"
[ "$_mono" = "0" ] || r7_fail "phase3 检查点计数回退（${_mono} 行 steps/decision 递减）"

# ⑤ 零重复执行/计费
_dup="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM (
    SELECT task_id, attempt_id, action_seq, physical_seq FROM rca_model_call
    WHERE run_id='${RUNID}' GROUP BY 1,2,3,4 HAVING count(*)>1) x" '-At')"
[ "$_dup" = "0" ] || r7_fail "phase3 ${_dup} 组重复物理调用（重驱动重复执行/计费面）"

# ⑥ 同 incident 恰 1 run
_nrun="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_run r
    JOIN incident i ON i.id=r.incident_id
    WHERE i.incident_key='${AN}|${SVC}'" '-At')"
[ "$_nrun" = "1" ] || r7_fail "phase3 同 incident 出现 ${_nrun} 个 run（重启复活面）"

_started_post="$(r7_container_started_at)"
[ "$_started_pre" != "$_started_post" ] \
    || r7_fail "phase3 容器 StartedAt 未变化（重启证据面不成立）"

r7_dump_container_logs "$T0" "$RUNS/control-app.log" > "$RUNS/phase3-ledger-fail-count.txt" || true
r7_resource_snapshot "$RUNS" "post"
r7_scenario_result "$RUNS" "R7-RX02-RD09" \
    "RX02/RD09 真 worker 重启：WAITING_CHILDREN 硬杀→绑定恒等/检查点续走/零重复执行" \
    "real-kill@195" "PASS"
r7_log "SUITE PASS（重启前 $_started_pre → 重启后 $_started_post；快照与 diff 见 $RUNS）"
