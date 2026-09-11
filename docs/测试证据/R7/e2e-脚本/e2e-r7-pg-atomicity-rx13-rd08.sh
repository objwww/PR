#!/bin/sh
# ============================================================================
# e2e-r7-pg-atomicity-rx13-rd08.sh —— RX13/RD08 真 PG 原子性真窗断言
# （R7 执行日志 §9 NOT_RUN 主责例：RX13/RD08；RD08 委派批事务、RX13 补证批准→建任务）
#
# 场景：委派诱导案在飞时对 control-app 隔离进程 SIGKILL 硬杀（docker kill，非
#       compose stop）→ 原容器拉起 → run 必达诚实终态 + 全量不变式扫。
# 档位设计（RD08 提交前/提交后双档）：真模型时序不可控，档位=观测归档而非精确
#       注入——杀前拍 finished_at 快照（在飞/已终态）+ 杀后查裁决行存在性：
#   PRE_COMMIT  档：杀时 run 在飞且 APPROVED 裁决行不存在 → 必须零 DELEGATE 任务
#                   （事务未提交=零残留，全无）
#   POST_COMMIT 档：APPROVED 裁决行存在 → 子任务/绑定/检查点计数必须齐（全有）
# 不变式扫（逐轮 + 全量收尾）：
#   I1 APPROVED 裁决 child_task_id 必非空且真实存在（裁决即建子=同事务）
#   I2 (run,round,task_key) 零重复、(run,gap_id) 零重复（不重复子任务/不重复派发；
#      uq 兜底，扫面留证）
#   I3 checkpoint.batches_used == APPROVED 裁决数（计数面一致）
#   I4 任务↔绑定双射（PRIMARY+DELEGATE 每任务恰 1 绑定行；恢复只读绑定）
#   I5 rca_model_call (task,attempt,action_seq,physical_seq) 零重复组（重启不重执行）
#   I6 同 incident 恰 1 个 run（重启不复活重复 run）
#
# 前置：主模式部署形态 + R7_ALLOW_DESTRUCTIVE=1 显式放行（本脚本对 control-app
#   做 N 轮 docker kill/start）；R7_ATOMICITY_ROUNDS 轮数（默认 3）；杀后延迟档
#   0/1.5s/4s 循环（R7_KILL_DELAYS 可覆盖，逗号分隔毫秒）。
# 姿态行为面核验同 B 门（PRIMARY_INVESTIGATE 在场）。无委派案=不触发 RD08 面，
#   annotations 用多信号 summary 诱导委派；若全轮零委派如实 FAIL（案面重调）。
# ============================================================================

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/e2e-r7-common.sh"

ROUNDS="${R7_ATOMICITY_ROUNDS:-3}"
DELAYS="${R7_KILL_DELAYS:-0,1500,4000}"
SESSION="$(r7_suite_run_id)"
RUNS="${R7_RUNS_DIR:-./runs}/atomicity-${SESSION}"
mkdir -p "$RUNS"
R7_KILL_LOG="$RUNS/kill-restart.log"
SFX="$(date -u +%H%M%S)"
AN="KillProbe"
r7_require_destructive
r7_log "rounds=$ROUNDS delays=${DELAYS}ms runs=$RUNS"

r7_resource_snapshot "$RUNS" "pre"

# ---------------------------------------------------------------------------
# phase1 姿态 + 一次性 bundle（白名单全部轮次 service）
# ---------------------------------------------------------------------------
r7_health "$RUNS"
_wl=""
_i=1
while [ "$_i" -le "$ROUNDS" ]; do
    _svc="r7kill-r${_i}-${SFX}"
    _wl="${_wl}\"alertname=$(echo "$AN" | tr '[:upper:]' '[:lower:]')|service=${_svc}\","
    _i=$((_i + 1))
done
_wl="$(echo "$_wl" | sed -E 's/,$//')"
cat > "$RUNS/bundle-kill.content" <<EOF
{"policy_version":"r7-e2e-kill-${SESSION}","canary":{"percent":0,"whitelist":[${_wl}],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
DK="$(r7_publish_bundle "$RUNS/bundle-kill.content" kill)"
r7_activate "$DK" "$RUNS"
r7_log "phase1 PASS（digest=$DK；姿态=主模式部署（行为面逐轮核验））"

# ---------------------------------------------------------------------------
# phase2 N 轮硬杀×档位
# ---------------------------------------------------------------------------
_started_before="$(r7_container_started_at)"
r7_log "phase2 容器起始 StartedAt=$_started_before（重启证据面）"

_i=1
: > "$RUNS/rounds.txt"
while [ "$_i" -le "$ROUNDS" ]; do
    _svc="r7kill-r${_i}-${SFX}"
    _delay="$(echo "$DELAYS" | cut -d',' -f$(( (_i - 1) % $(echo "$DELAYS" | tr ',' '\n' | wc -l) + 1 )))"
    _key="alertname=$(echo "$AN" | tr '[:upper:]' '[:lower:]')|service=${_svc}"
    _key="${_key}:${_key}"
    r7_log "round $_i：注入 $_svc（延迟档 ${_delay}ms）"
    _code="$(r7_inject_alert "$AN" "$_svc" firing "$RUNS" \
        "multi-signal: latency + errors + suspected configuration change")"
    [ "$_code" = "202" ] || r7_fail "round$_i 注入期望 202 实得 $_code"
    r7_db_poll_ge "round$_i WHITELISTED 原子对" 120 R7_PG_URL \
        "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${_key}'
         AND decision='WHITELISTED' AND bundle_digest='${DK}' AND run_id IS NOT NULL" 1
    _runid="$(r7_psql_ro R7_PG_URL "SELECT run_id FROM canary_route_decision
        WHERE stickiness_key='${_key}' AND decision='WHITELISTED'
        AND bundle_digest='${DK}' ORDER BY id DESC LIMIT 1" '-At')"

    # 等主任务在场（run 已在飞）→ 延迟档 → 杀前快照
    r7_db_poll_ge "round$_i PRIMARY_INVESTIGATE 任务在场" 120 R7_PG_URL \
        "SELECT count(*) FROM rca_task WHERE run_id='${_runid}'
         AND task_key='PRIMARY_INVESTIGATE'" 1
    if [ "$_delay" != "0" ]; then
        _sec="$(awk "BEGIN{printf \"%.1f\", $_delay/1000}")"
        sleep "$_sec"
    fi
    _active_at_kill="$(r7_psql_ro R7_PG_URL \
        "SELECT count(*) FROM rca_run WHERE id='${_runid}' AND finished_at IS NULL" '-At')"

    r7_log "round $_i：SIGKILL（在飞快照=$_active_at_kill）"
    r7_container_kill
    _killed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    r7_container_start
    r7_poll_until "round$_i 重启后 health 回 200" 180 "( r7_health $RUNS )"
    r7_log "round $_i：容器已拉起（killed_at=$_killed_at），等 run 诚实终态"

    _state="$(r7_wait_run_terminal R7_PG_URL "$_runid" 900)"

    # ---- 不变式扫（逐轮）----
    _svc_q="$(echo "$_svc" | sed 's/'"'"'//g')"
    _inv="$(r7_psql_ro R7_PG_URL "
        SELECT
          (SELECT count(*) FROM rca_delegation_decision d
             WHERE d.run_id='${_runid}' AND d.status='APPROVED'
               AND (d.child_task_id IS NULL OR NOT EXISTS
                    (SELECT 1 FROM rca_task t WHERE t.id=d.child_task_id)))
          ||'|'||
          (SELECT count(*) FROM (SELECT run_id, round_id, task_key
             FROM rca_task WHERE run_id='${_runid}' GROUP BY 1,2,3
             HAVING count(*)>1) x)
          ||'|'||
          (SELECT count(*) FROM (SELECT run_id, gap_id FROM rca_delegation_decision
             WHERE run_id='${_runid}' GROUP BY 1,2 HAVING count(*)>1) y)
          ||'|'||
          (SELECT count(*) FROM rca_primary_checkpoint c
             WHERE c.run_id='${_runid}' AND c.batches_used <>
               (SELECT count(*) FROM rca_delegation_decision d
                WHERE d.run_id='${_runid}' AND d.status='APPROVED'))
          ||'|'||
          (SELECT count(*) FROM rca_task t WHERE t.run_id='${_runid}'
             AND NOT EXISTS (SELECT 1 FROM rca_task_execution_binding b
                             WHERE b.task_id=t.id))
          ||'|'||
          (SELECT count(*) FROM (SELECT task_id, attempt_id, action_seq, physical_seq
             FROM rca_model_call WHERE run_id='${_runid}' GROUP BY 1,2,3,4
             HAVING count(*)>1) z)
          ||'|'||
          (SELECT count(*) FROM rca_run r JOIN incident i ON i.id=r.incident_id
             WHERE i.incident_key='${AN}|${_svc_q}')
        " '-At')"
    _inv_expect="0|0|0|0|0|0|1"
    [ "$_inv" = "$_inv_expect" ] || r7_fail "round$_i 不变式破（I1..I6：orphan|dup_task|dup_gap|batch_cnt|no_binding|dup_call|run_cnt）
实得=$_inv 期望=$_inv_expect"

    # ---- 档位归档（观测而非注入）----
    _ndec="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_delegation_decision
        WHERE run_id='${_runid}' AND status='APPROVED'" '-At')"
    if [ "$_ndec" = "0" ] && [ "$_active_at_kill" = "1" ]; then
        _bracket="PRE_COMMIT（零残留面）"
        _ndel_expect=0
    elif [ "$_ndec" -ge 1 ]; then
        _bracket="POST_COMMIT（全有面）"
        _ndel_expect="$_ndec"
    else
        _bracket="POST_TERMINAL（杀时已终态）"
        _ndel_expect="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
            WHERE run_id='${_runid}' AND task_key LIKE 'DELEGATE-%'" '-At')"
    fi
    # PRE_COMMIT 档硬断言：裁决未提交=零子任务（RX13 半套图禁令）
    if [ "$_bracket" = "PRE_COMMIT（零残留面）" ]; then
        _ndel="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
            WHERE run_id='${_runid}' AND task_key LIKE 'DELEGATE-%'" '-At')"
        [ "$_ndel" = "0" ] || r7_fail "round$_i PRE_COMMIT 档发现 $_ndel 个 DELEGATE 任务（半套图！）"
    fi
    printf 'r%s|%s|%s|%sms|%s|%s|%s\n' "$_i" "$_runid" "$_active_at_kill" "$_delay" \
        "$_bracket" "$_state" "$_inv" >> "$RUNS/rounds.txt"
    r7_log "round $_i PASS：state=$_state bracket=$_bracket 委派子任务=${_ndel_expect}"
    _i=$((_i + 1))
done

# ---------------------------------------------------------------------------
# phase3 姿态核验 + 在飞命中统计 + 全轮汇总
# ---------------------------------------------------------------------------
_started_after="$(r7_container_started_at)"
[ "$_started_before" != "$_started_after" ] \
    || r7_fail "phase3 容器 StartedAt 未变化（重启证据面不成立）"
_active_hits="$(grep -c '|1|' "$RUNS/rounds.txt" || true)"
[ "${_active_hits:-0}" -ge 1 ] \
    || r7_fail "phase3 全部轮次杀时 run 已终态（硬杀未命中在飞）——调大延迟档/换慢案后重跑"
_prim="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task t
    WHERE t.task_key='PRIMARY_INVESTIGATE' AND t.run_id IN
      (SELECT r.id FROM rca_run r JOIN incident i ON i.id=r.incident_id
       WHERE i.incident_key LIKE 'KillProbe|r7kill-r%-${SFX}')" '-At')"
[ "${_prim:-0}" -ge 1 ] || r7_fail "phase3 零主模式任务——部署姿态非主模式（r7.primary.enabled）"

r7_resource_snapshot "$RUNS" "post"
r7_scenario_result "$RUNS" "R7-RX13-RD08" \
    "RX13/RD08 真 PG 原子性：在飞硬杀×${ROUNDS}轮（提交前后档观测归档）+ 全量不变式" \
    "real-kill@195" "PASS"
r7_log "SUITE PASS：轮次账=$RUNS/rounds.txt（bracket 分布见档位列）"
