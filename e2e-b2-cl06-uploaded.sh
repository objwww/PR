#!/bin/sh
# ============================================================================
# e2e-b2-cl06.sh —— B2 CL-06 跨轮记忆真窗断言（e2e-a0-b1.sh 委派变体）
#
# 姿态（override 合窗）：APP_ALERT_R7_PRIMARY_MAX_DELEGATION_BATCHES=2
#   + APP_ALERT_R7_PRIMARY_PROMPT=jar 缺省委派面 + APP_ALERT_R7_INPUTCAPTURE=full。
# 场景：真实 checkout 现场 → 主 Agent 缺省 prompt（按需委派）→ 委派批获批
#   → round_id 随批 +1 → 跨轮工作记忆链。
# 断言面：
#   ①②③ 同 A0（路由原子对/主链/绑定/检查点），但 phase4 翻面：
#      DELEGATE-% ≥1（委派真实发生）+ checkpoint.round_id≥1 + batches_used≥1
#   ⑤⑥⑦⑧ 同 A0（allowlist=jar 缺省八工具；回执链；报告链；日志脱敏）
#   ⑨ CL-06 跨轮记忆：rca_working_memory schema_version 全 2；父链修订连号；
#      checkpoint.memory_id↔memory.checkpoint_revision+digest 锚点回环；
#      ruled_out 槽纯度（控制拒绝码零混入）。
#   ⑩ 跨轮 prompt 可达性（FULL 捕获）：b2-cl06-verify.py 承载
#      （round>0 首 prompt 的 working_memory 槽 ⊇ round-0 末 prompt 槽并集）。
# ============================================================================
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)
. "${SCRIPT_DIR}/e2e-r7-common.sh"

SUITE="b2cl06-$(r7_suite_run_id)"
RUNS="${R7_RUNS_DIR:-./runs}/${SUITE}"
mkdir -p "$RUNS"
SVC="${R7_A0_SERVICE:-checkout}"
AN="E2EB2Cl06CrossRound"
ALLOWLIST="${R7_PRIMARY_ALLOWLIST:-prometheus.query,logs.query,prometheus.instant,prometheus.metric_value,prometheus.catalog,prometheus.label_values,prometheus.rules,logs.aggregate}"
r7_log "suite=$SUITE runs=$RUNS scene=service:$SVC alertname:$AN (委派面)"

r7_resource_snapshot "$RUNS" "pre"

# phase1 姿态 + bundle
r7_log "phase1 姿态：health 200 + bundle 发布激活（白名单 $SVC）"
r7_health "$RUNS"
cat > "$RUNS/bundle-b2cl06.content" <<EOF
{"policy_version":"r7-b2-cl06-${SUITE}","canary":{"percent":0,"whitelist":["alertname=${AN}|service=${SVC}"],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
DA0="$(r7_publish_bundle "$RUNS/bundle-b2cl06.content" b2cl06)"
r7_qualify "$DA0" "$RUNS"
r7_activate "$DA0" "$RUNS"
r7_log "phase1 PASS（health 200；digest=$DA0）"

# phase2 注入 + 原子对
T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
r7_log "phase2 注入合成告警（${AN}@$SVC）"
_code="$(r7_inject_alert "$AN" "$SVC" firing "$RUNS" "E2E-B2 CL06 cross-round ${SUITE} checkout 现场")"
[ "$_code" = "202" ] || r7_fail "phase2 注入期望 202 实得 $_code: $(cat "$RUNS/alert-${SVC}-firing.resp")"
WL_L="alertname=$(echo "$AN" | tr '[:upper:]' '[:lower:]')|service=${SVC}"
WLKEY="${WL_L}:${WL_L}"
r7_db_poll_ge "phase2 WHITELISTED 审计行" 120 R7_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${WLKEY}'
     AND decision='WHITELISTED' AND bundle_digest='${DA0}' AND run_id IS NOT NULL" 1
RUNID="$(r7_psql_ro R7_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE stickiness_key='${WLKEY}' AND decision='WHITELISTED' AND bundle_digest='${DA0}'
    ORDER BY id DESC LIMIT 1" '-At')"
echo "run=${RUNID}" > "$RUNS/run-id.txt"
r7_log "phase2 PASS（原子对成立；run=$RUNID）"

# phase3 真模型收敛
r7_log "phase3 等 run 终态（真模型 900s 上限；委派面收敛窗更宽）"
STATE="$(r7_wait_run_terminal R7_PG_URL "$RUNID" 900)"
[ "$STATE" = "SUCCEEDED" ] || r7_fail "phase3 run 终态=$STATE（CL-06 要求 SUCCEEDED；取证见 $RUNS）"
r7_log "phase3 PASS（run SUCCEEDED）"

# phase4 主链断言（委派变体：委派真实发生 + 轮推进）
r7_log "phase4 主链：PRIMARY DONE / DELEGATE≥1 / 绑定 / 检查点（round≥1+batches≥1）"
_nprim="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
    WHERE run_id='${RUNID}' AND task_key='PRIMARY_INVESTIGATE' AND state='DONE'" '-At')"
[ "$_nprim" = "1" ] || r7_fail "phase4 PRIMARY_INVESTIGATE DONE 应恰 1 实得 $_nprim"
_ndel="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
    WHERE run_id='${RUNID}' AND task_key LIKE 'DELEGATE-%'" '-At')"
[ "${_ndel:-0}" -ge 1 ] || r7_fail "phase4 零委派（DELEGATE-%=0）——委派未发生，prompt/姿态再调"
r7_psql_ro R7_PG_URL "SELECT task_id||'|'||role_id||'|'||role_version||'|'||role_digest
    FROM rca_task_execution_binding WHERE run_id='${RUNID}'" '-At' \
    > "$RUNS/phase4-binding.txt"
grep -qE '^[0-9a-f-]{36}\|primary\|[0-9A-Za-z._-]+\|[0-9a-f]{64}$' "$RUNS/phase4-binding.txt" \
    || r7_fail "phase4 primary 绑定三元组不全: $(cat "$RUNS/phase4-binding.txt")"
r7_psql_ro R7_PG_URL "SELECT round_id||'|'||steps_used||'|'||decision_seq||'|'||batches_used||'|'||
    (final_claims IS NOT NULL) FROM rca_primary_checkpoint WHERE run_id='${RUNID}'" '-At' \
    > "$RUNS/phase4-checkpoint.txt"
CP="$(cat "$RUNS/phase4-checkpoint.txt")"
echo "$CP" | grep -qE '^[1-9][0-9]*\|[1-8]\|[0-9]+\|[1-9][0-9]*\|true$' \
    || r7_fail "phase4 检查点面异常（需 round≥1/steps∈[1,8]/batches≥1/final_claims 非空）: $CP"
r7_log "phase4 PASS（主 DONE+委派 $_ndel 子任务；checkpoint round/batches 推进：$CP）"

# phase5 受限直查 + 证据锚定
r7_log "phase5 受限直查：allowlist=jar 缺省八工具；evidence_refs 全可解析"
r7_psql_ro R7_PG_URL "SELECT DISTINCT tool_name FROM rca_tool_invocation
    WHERE run_id='${RUNID}'" '-At' > "$RUNS/phase5-tools.txt"
[ -s "$RUNS/phase5-tools.txt" ] || r7_fail "phase5 零工具调用"
_tools_bad=0
while IFS= read -r _t; do
    echo "$ALLOWLIST" | grep -q "$_t" || _tools_bad=$((_tools_bad + 1))
done < "$RUNS/phase5-tools.txt"
[ "$_tools_bad" = "0" ] || r7_fail "phase5 存在 allowlist 外工具: $(cat "$RUNS/phase5-tools.txt")"
r7_db_poll_ge "phase5 至少 1 笔工具 SUCCESS" 30 R7_PG_URL \
    "SELECT count(*) FROM rca_tool_invocation WHERE run_id='${RUNID}' AND state='SUCCESS'" 1
_nev="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_evidence WHERE run_id='${RUNID}'" '-At')"
[ "${_nev:-0}" -ge 1 ] || r7_fail "phase5 证据行恒零"
_nunresolved="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_claim c,
    jsonb_array_elements_text(c.evidence_refs) ref
    WHERE c.run_id='${RUNID}' AND c.lifecycle='ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM rca_evidence e WHERE e.id::text=ref)" '-At')"
[ "$_nunresolved" = "0" ] || r7_fail "phase5 evidence_refs 有 $_nunresolved 条解析不到证据行"
r7_log "phase5 PASS（直查全落 allowlist；证据 ${_nev} 行）"

# phase6 供应商回执链（同 A0：全 SUCCESS+R2 捕获恰一行对账+R1 快照回填+平台零交叉）
r7_log "phase6 回执链：全 SUCCESS 结清 + 捕获对账（R2）+ 快照回填（R1）+ 平台零交叉"
r7_psql_ro R7_PG_URL "SELECT state||'|'||action_seq||'|'||coalesce(route_id,'')||'|'||coalesce(error_code,'')
    FROM rca_model_call WHERE run_id='${RUNID}' ORDER BY action_seq" '-At' \
    > "$RUNS/phase6-model-calls.txt"
[ -s "$RUNS/phase6-model-calls.txt" ] || r7_fail "phase6 rca_model_call 恒零"
_bad="$(grep -vcE '^SUCCESS\|' "$RUNS/phase6-model-calls.txt")" || _bad=0
[ "${_bad:-0}" = "0" ] || r7_fail "phase6 存在非 SUCCESS 模型调用行: $(grep -vE '^SUCCESS\|' "$RUNS/phase6-model-calls.txt" | tr '\n' ' ')"
_nosettled="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call
    WHERE run_id='${RUNID}' AND (usage IS NULL OR usage_missing=true
      OR pricing_version IS NULL
      OR (cost_micros IS NULL AND pricing_version <> 'unpriced')
      OR invocation_id IS NULL OR settled_at IS NULL)" '-At')"
[ "$_nosettled" = "0" ] || r7_fail "phase6 ${_nosettled} 行未结清"
_nocap="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call m
    WHERE m.run_id='${RUNID}' AND m.state='SUCCESS'
      AND (SELECT count(*) FROM rca_model_input i WHERE i.model_call_id=m.id) <> 1" '-At')"
[ "${_nocap:-0}" = "0" ] || r7_fail "phase6 R2: ${_nocap} 笔捕获行数≠1"
_digmis="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call m
    JOIN rca_model_input i ON i.model_call_id=m.id
    WHERE m.run_id='${RUNID}' AND m.prompt_digest <> i.prompt_digest" '-At')"
[ "${_digmis:-0}" = "0" ] || r7_fail "phase6 R2: ${_digmis} 行 digest 不对账"
_nosnap="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_primary_checkpoint
    WHERE run_id='${RUNID}' AND steps_used > 0 AND input_snapshot_digest IS NULL" '-At')"
[ "${_nosnap:-0}" = "0" ] || r7_fail "phase6 R1: 快照未回填"
_platpoll="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM model_call_ledger p
    WHERE p.invocation_id IN (SELECT invocation_id FROM rca_model_call
      WHERE run_id='${RUNID}' AND invocation_id IS NOT NULL)" '-At')"
[ "$_platpoll" = "0" ] || r7_fail "phase6 平台账本跨域污染 ${_platpoll} 笔"
r7_log "phase6 PASS（$(wc -l < "$RUNS/phase6-model-calls.txt" | tr -d ' ') 笔调用全结清）"

# phase7 报告链（新 incident 首跑=发布赢家面）
r7_log "phase7 报告链：report → publication → outbox"
r7_db_poll_ge "phase7 report 落行" 60 R7_PG_URL \
    "SELECT count(*) FROM rca_report WHERE run_id='${RUNID}'" 1
r7_db_poll_ge "phase7 report_publication 落行" 60 R7_PG_URL \
    "SELECT count(*) FROM report_publication p JOIN rca_report rr ON p.report_id=rr.id
     WHERE rr.run_id='${RUNID}'" 1
r7_db_poll_ge "phase7 notify_outbox 落行" 60 R7_PG_URL \
    "SELECT count(*) FROM notify_outbox WHERE report_id IN
     (SELECT id FROM rca_report WHERE run_id='${RUNID}')" 1
r7_log "phase7 PASS（报告/发布/外发全落行）"

# phase8 日志取证 + 密钥扫描
r7_log "phase8 日志取证（脱敏）+ 密钥词形零回显"
r7_dump_container_logs "$T0" "$RUNS/control-app.log" > "$RUNS/phase8-ledger-fail-count.txt" || true
grep -icE 'sk-[A-Za-z0-9]{8,}' "$RUNS/control-app.log" > "$RUNS/phase8-sk-hits.txt" || true
[ "$(cat "$RUNS/phase8-sk-hits.txt")" = "0" ] \
    || r7_fail "phase8 日志存在密钥词形"
r7_log "phase8 PASS"

# phase9 CL-06 跨轮记忆（持久面）
r7_log "phase9 CL-06 记忆链：schema2/父链连号/锚点回环/槽纯度"
_memcnt="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_working_memory
    WHERE run_id='${RUNID}'" '-At')"
[ "${_memcnt:-0}" -ge 2 ] || r7_fail "phase9 记忆行 ${_memcnt}（<2，无累计链可言）"
_schema2="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_working_memory
    WHERE run_id='${RUNID}' AND schema_version <> 2" '-At')"
[ "$_schema2" = "0" ] || r7_fail "phase9 存在 schema_version<>2 行 $_schema2 个"
_chainbrk="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_working_memory c
    LEFT JOIN rca_working_memory p ON c.parent_memory_id=p.id
    WHERE c.run_id='${RUNID}' AND c.checkpoint_revision > 0
      AND (p.id IS NULL OR p.checkpoint_revision <> c.checkpoint_revision - 1)" '-At')"
[ "$_chainbrk" = "0" ] || r7_fail "phase9 父链断裂/跳号 $_chainbrk 处"
_anchor="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_primary_checkpoint cp
    JOIN rca_working_memory m ON cp.memory_id=m.id
    WHERE cp.run_id='${RUNID}' AND (m.checkpoint_revision <> cp.revision
      OR m.memory_digest <> cp.memory_digest)" '-At')"
[ "$_anchor" = "0" ] || r7_fail "phase9 检查点记忆锚点漂移 $_anchor 处"
_purity="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_working_memory
    WHERE run_id='${RUNID}' AND (memory_json::text LIKE '%TOOL_NOT_ALLOWED%'
      OR memory_json::text LIKE '%INVALID_ARGS%'
      OR memory_json::text LIKE '%DECISION_UNPARSEABLE%')" '-At')"
[ "$_purity" = "0" ] || r7_fail "phase9 ruled_out 槽混入控制拒绝码 $_purity 处（CL-06 语义破）"
_r2full="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_input i
    JOIN rca_model_call c ON c.id=i.model_call_id
    WHERE c.run_id='${RUNID}' AND i.capture_level='FULL'
      AND (SELECT count(*) FROM rca_model_input i2 WHERE i2.model_call_id=c.id) = 1" '-At')"
[ "${_r2full:-0}" -ge 1 ] || r7_fail "phase9 FULL 捕获面为零（override 未生效？）"
r7_log "phase9 PASS（记忆行 $_memcnt；父链连号；锚点回环；槽纯度绿；FULL 捕获在）"

r7_resource_snapshot "$RUNS" "post"
r7_scenario_result "$RUNS" "B2-CL06" \
    "B2 CL-06 跨轮记忆真窗（委派面+FULL 捕获；run=$RUNID）" \
    "real-provider@195" "PASS"
r7_log "SUITE PASS：CL-06 驱动断言全绿（run=$RUNID digest=$DA0 证据=$RUNS；跨轮 prompt 可达性见 b2-cl06-verify.py）"
