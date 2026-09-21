#!/bin/sh
# ============================================================================
# e2e-r7-a0-provider-receipt-chain.sh —— R7-A0 门真窗断言（执行卡 §执行顺序 #9）
#
# 场景：真实 checkout 现场合成告警 → 主 Agent 受限指标直查 → 真实模型执行 →
#       带引用 Claim 报告收敛；零子 Agent；供应商回执关联链完整可对账。
# 断言面（只读 SQL 全经 r7_psql_ro，证据脱敏落盘）：
#   ① 路由：WHITELISTED 决策行 ↔ run 原子对（V31 语义），engine=NATIVE
#   ② 主链零子 Agent：恰 1 个 PRIMARY_INVESTIGATE 任务 DONE；DELEGATE-% 恒零
#   ③ 冻结绑定：rca_task_execution_binding 1 行 role_id=primary，digest 三元组齐全
#   ④ 检查点：steps_used ∈ [1,max_steps]；final_claims 非空（FINAL 先落后进报告相位）
#   ⑤ 受限直查：rca_tool_invocation 全落 allowlist（默认 prometheus.query,logs.query），
#      ≥1 SUCCESS；证据 ≥1 行；ACTIVE TRUE Claim 的 evidence_refs 全部可解析到
#      rca_evidence 行（不预灌答案：结论必须锚在真实取回的证据上）
#   ⑥ 供应商回执链：rca_model_call 全 SUCCESS 零 UNKNOWN/FAILED；usage 落账
#      （usage_missing=false，cost/pricing 齐全）；invocation_id 审计锚非空；
#      平台 model_call_ledger 对 RCA 调用恒零行（BA-109 裁定：RCA 侧独立账本山，
#      平台写面装配旁路，跨域污染防栅）
#   ⑦ 报告生产链：rca_report → report_publication → notify_outbox 全落行
#   ⑧ 日志取证：容器日志脱敏落盘；供应商密钥词形（sk-…）零回显
#
# ⚠ 风险面已实证并裁定（2026-09-11 真窗，BA-109）：
#   RCA→平台账本写面 23503 FK 违反首跑实证（全量 UNKNOWN + 「账本 STARTED 写失败」）。
#   裁定=候选形态①RCA 侧独立账本（rca_model_call 唯一账本山，平台写面
#   NoOpModelCallLedgerRepository 旁路）；phase6 对账断言随之翻面为「平台恒零行」。
#   同窗另实证 BA-108（openRun 缺 TOKEN 维 → PG fail-closed 全量预算耗尽），均已修复。
#
# 操作员前置（部署段，见 RUNBOOK）：主模式部署形态（app.alert.r7.primary.enabled=true
#   + release-digest + AGENT_MODEL/AGENT_MODEL_API_KEY 注入）；prometheus 可达；
#   R7_WEBHOOK_BEARER / R7_RELEASE_BEARER / R7_PG_URL / R7_PSQL_CMD 注入。
# 本脚本对 195 无破坏性动作（不 kill/重启容器）。
# ============================================================================

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/e2e-r7-common.sh"

SUITE="a0-$(r7_suite_run_id)"
RUNS="${R7_RUNS_DIR:-./runs}/${SUITE}"
mkdir -p "$RUNS"
# 真实 checkout 现场：service 必须是 195 上有真实指标的服务（合成服务名查空证据
# 违反 A0 语义）；场景身份由专用 alertname 承担（同键复燃=新 episode，AM6 判据）
SVC="${R7_A0_SERVICE:-checkout}"
AN="E2EA0CheckoutProbe"
ALLOWLIST="${R7_PRIMARY_ALLOWLIST:-prometheus.query,logs.query}"
r7_log "suite=$SUITE runs=$RUNS scene=service:$SVC alertname:$AN"

r7_resource_snapshot "$RUNS" "pre"

# ---------------------------------------------------------------------------
# phase1 姿态 + 路由 bundle（percent=0 + 白名单本场景 service，外科式收窄爆炸半径）
# ---------------------------------------------------------------------------
r7_log "phase1 姿态：health 200 + bundle 发布激活（白名单 $SVC）"
r7_health "$RUNS"
cat > "$RUNS/bundle-a0.content" <<EOF
{"policy_version":"r7-e2e-a0-${SUITE}","canary":{"percent":0,"whitelist":["alertname=${AN}|service=${SVC}"],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
DA0="$(r7_publish_bundle "$RUNS/bundle-a0.content" a0)"
# EN-02 资格门（227896c 起 activate 必须携带未撤销 PASS 证明；grant HTTP 面归 EN-09/10
# 未建）——探针 bundle 先过 SQL 桥授予再激活，provenance 见 r7_qualify
r7_qualify "$DA0" "$RUNS"
r7_activate "$DA0" "$RUNS"
r7_log "phase1 PASS（health 200；digest=$DA0）"

# ---------------------------------------------------------------------------
# phase2 注入 + WHITELISTED 原子对
# ---------------------------------------------------------------------------
T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
r7_log "phase2 注入 checkout 合成告警（${AN}@$SVC）"
# BA-111 同窗实证：run SUCCEEDED 后 last_investigation_hash=输入哈希，同输入再注入=
# 正确去重（产品语义，非缺陷）——每次套件必须以唯一 summary 区分调查输入
_code="$(r7_inject_alert "$AN" "$SVC" firing "$RUNS" "E2E-R7 A0 ${SUITE} checkout 现场")"
[ "$_code" = "202" ] || r7_fail "phase2 注入期望 202 实得 $_code: $(cat "$RUNS/alert-${SVC}-firing.resp")"
WL_L="alertname=$(echo "$AN" | tr '[:upper:]' '[:lower:]')|service=${SVC}"
WLKEY="${WL_L}:${WL_L}"
r7_db_poll_ge "phase2 WHITELISTED 审计行（run_id 非空=原子对）" 120 R7_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${WLKEY}'
     AND decision='WHITELISTED' AND bundle_digest='${DA0}' AND run_id IS NOT NULL" 1
RUNID="$(r7_psql_ro R7_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE stickiness_key='${WLKEY}' AND decision='WHITELISTED' AND bundle_digest='${DA0}'
    ORDER BY id DESC LIMIT 1" '-At')"
r7_log "phase2 PASS（原子对成立；run=$RUNID）"

# ---------------------------------------------------------------------------
# phase3 真模型收敛（900s 上限；SUCCEEDED 是 A0 唯一收敛面）
# ---------------------------------------------------------------------------
r7_log "phase3 等 run 终态（真模型 900s 上限）"
STATE="$(r7_wait_run_terminal R7_PG_URL "$RUNID" 900)"
[ "$STATE" = "SUCCEEDED" ] || r7_fail "phase3 run 终态=$STATE（A0 要求 SUCCEEDED；取证见 $RUNS）"
r7_log "phase3 PASS（run SUCCEEDED）"

# ---------------------------------------------------------------------------
# phase4 主链断言：零子 Agent + 冻结绑定 + 检查点
# ---------------------------------------------------------------------------
r7_log "phase4 主链：恰 1 PRIMARY_INVESTIGATE DONE / 零 DELEGATE / 绑定 / 检查点"
_nprim="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
    WHERE run_id='${RUNID}' AND task_key='PRIMARY_INVESTIGATE' AND state='DONE'" '-At')"
[ "$_nprim" = "1" ] || r7_fail "phase4 PRIMARY_INVESTIGATE DONE 应恰 1 实得 $_nprim\
（0 → 部署姿态疑非主模式：核对 app.alert.r7.primary.enabled）"
_ndel="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
    WHERE run_id='${RUNID}' AND task_key LIKE 'DELEGATE-%'" '-At')"
[ "$_ndel" = "0" ] || r7_fail "phase4 零子 Agent 破：DELEGATE-% 任务 $_ndel 个（A0 要求直查收敛）"
r7_psql_ro R7_PG_URL "SELECT task_id||'|'||role_id||'|'||role_version||'|'||role_digest
    FROM rca_task_execution_binding WHERE run_id='${RUNID}'" '-At' \
    > "$RUNS/phase4-binding.txt"
[ "$(wc -l < "$RUNS/phase4-binding.txt" | tr -d ' ')" = "1" ] \
    || r7_fail "phase4 绑定应恰 1 行: $(cat "$RUNS/phase4-binding.txt")"
grep -qE '^[0-9a-f-]{36}\|primary\|[0-9A-Za-z._-]+\|[0-9a-f]{64}$' "$RUNS/phase4-binding.txt" \
    || r7_fail "phase4 绑定三元组不全（role/版本/digest）: $(cat "$RUNS/phase4-binding.txt")"
r7_psql_ro R7_PG_URL "SELECT steps_used||'|'||decision_seq||'|'||batches_used||'|'||
    (final_claims IS NOT NULL) FROM rca_primary_checkpoint WHERE run_id='${RUNID}'" '-At' \
    > "$RUNS/phase4-checkpoint.txt"
CP="$(cat "$RUNS/phase4-checkpoint.txt")"
echo "$CP" | grep -qE '^[1-8]\|[0-9]+\|[0-9]+\|true$' \
    || r7_fail "phase4 检查点面异常（steps|decision|batches|final_claims 非空）: $CP"
r7_log "phase4 PASS（零子 Agent/绑定三元组/检查点 final_claims 在场）"

# ---------------------------------------------------------------------------
# phase5 受限直查 + 证据锚定（不预灌答案）
# ---------------------------------------------------------------------------
r7_log "phase5 受限直查：allowlist=$ALLOWLIST；evidence_refs 全可解析"
r7_psql_ro R7_PG_URL "SELECT DISTINCT tool_name FROM rca_tool_invocation
    WHERE run_id='${RUNID}'" '-At' > "$RUNS/phase5-tools.txt"
[ -s "$RUNS/phase5-tools.txt" ] || r7_fail "phase5 零工具调用（主 Agent 未直查）"
_tools_bad=0
while IFS= read -r _t; do
    echo "$ALLOWLIST" | grep -q "$_t" || _tools_bad=$((_tools_bad + 1))
done < "$RUNS/phase5-tools.txt"
[ "$_tools_bad" = "0" ] || r7_fail "phase5 存在 allowlist 外工具: $(cat "$RUNS/phase5-tools.txt")"
r7_db_poll_ge "phase5 至少 1 笔工具 SUCCESS" 30 R7_PG_URL \
    "SELECT count(*) FROM rca_tool_invocation WHERE run_id='${RUNID}' AND state='SUCCESS'" 1
_nev="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_evidence WHERE run_id='${RUNID}'" '-At')"
[ "${_nev:-0}" -ge 1 ] || r7_fail "phase5 证据行恒零（结论无证据锚）"
_nunresolved="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_claim c,
    jsonb_array_elements_text(c.evidence_refs) ref
    WHERE c.run_id='${RUNID}' AND c.lifecycle='ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM rca_evidence e WHERE e.id::text=ref)" '-At')"
[ "$_nunresolved" = "0" ] || r7_fail "phase5 evidence_refs 有 $_nunresolved 条解析不到证据行（预灌/复述面）"
_ntrue="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_claim
    WHERE run_id='${RUNID}' AND lifecycle='ACTIVE' AND status='TRUE'
      AND jsonb_array_length(evidence_refs) >= 1" '-At')"
[ "${_ntrue:-0}" -ge 1 ] || r7_fail "phase5 无带引用的 ACTIVE TRUE Claim"
r7_log "phase5 PASS（直查全落 allowlist；证据 ${_nev} 行；TRUE Claim ${_ntrue} 个引用全锚定）"

# ---------------------------------------------------------------------------
# phase6 供应商回执链（A0 核心面；风险登记见文件头）
# ---------------------------------------------------------------------------
r7_log "phase6 回执链：rca_model_call 全 SUCCESS 结清 + 平台账本零交叉（BA-109）"
r7_psql_ro R7_PG_URL "SELECT state||'|'||action_seq||'|'||coalesce(route_id,'')||'|'||
    coalesce(error_code,'') FROM rca_model_call WHERE run_id='${RUNID}' ORDER BY action_seq" \
    '-At' > "$RUNS/phase6-model-calls.txt"
[ -s "$RUNS/phase6-model-calls.txt" ] || r7_fail "phase6 rca_model_call 恒零（主模式未触网？姿态核对）"
_bad="$(grep -vcE '^SUCCESS\|' "$RUNS/phase6-model-calls.txt")" || _bad=0
[ "${_bad:-0}" = "0" ] || r7_fail "phase6 存在非 SUCCESS 模型调用行（UNKNOWN=对账破口）:\
$(grep -vE '^SUCCESS\|' "$RUNS/phase6-model-calls.txt" | tr '\n' ' ')"
# R4 定价显式语义：有 usage 无价 = pricing_version='unpriced' + cost NULL（合法账面，
# 不填 0 不编价）；usage 缺失（usage IS NULL / usage_missing=true）仍算未结清。
_nosettled="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call
    WHERE run_id='${RUNID}' AND (usage IS NULL OR usage_missing=true
      OR pricing_version IS NULL
      OR (cost_micros IS NULL AND pricing_version <> 'unpriced')
      OR invocation_id IS NULL OR settled_at IS NULL)" '-At')"
[ "$_nosettled" = "0" ] || r7_fail "phase6 ${_nosettled} 行 usage/pricing/invocation 未结清"
# R2 输入捕获（V90）：每笔 SUCCESS 调用恰一行 rca_model_input（append-only）且捕获
# prompt_digest 与账行对账一致。默认 digest-only 档=零原文仅 digest；评测环境
# app.alert.r7.input-capture=full 时 sha256(prompt_text)=prompt_digest 即原文可复盘。
_nocap="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call m
    WHERE m.run_id='${RUNID}' AND m.state='SUCCESS'
      AND (SELECT count(*) FROM rca_model_input i
           WHERE i.model_call_id=m.id) <> 1" '-At')"
[ "${_nocap:-0}" = "0" ] || r7_fail "phase6 R2: ${_nocap} 笔 SUCCESS 调用捕获行数≠1（恰一行捕获）"
_digmis="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call m
    JOIN rca_model_input i ON i.model_call_id=m.id
    WHERE m.run_id='${RUNID}' AND m.prompt_digest <> i.prompt_digest" '-At')"
[ "${_digmis:-0}" = "0" ] || r7_fail "phase6 R2: ${_digmis} 行捕获 digest 与账行 prompt_digest 不对账"
# R1 输入快照回填（G3 接线）：主检查点有模型步推进（steps_used>0）则
# input_snapshot_digest 必非空（装配器经 withStepAdvanced 回填；全量信封重建复算
# 由 InputReplayVerifier 在 Java 面承载，脚本面钉"回填已接线"）。
_nosnap="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_primary_checkpoint
    WHERE run_id='${RUNID}' AND steps_used > 0 AND input_snapshot_digest IS NULL" '-At')"
[ "${_nosnap:-0}" = "0" ] || r7_fail "phase6 R1: ${_nosnap} 个已推进主检查点 input_snapshot_digest 为空（快照未回填）"
# BA-109 裁定（设计修正，非口径放行；RUNBOOK §四.1 预登记候选形态①RCA 侧独立账本）：
# 平台 model_call_ledger 深绑 PR 域（V5 三列 NOT NULL+FK），RCA 唯一账本山=rca_model_call，
# 平台写面装配旁路（NoOpModelCallLedgerRepository）。对账断言翻面=平台账本对 RCA 调用
# 必须恒零行（跨域污染防栅），invocation_id 审计锚由上行 _nosettled 已钉。
_platpoll="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM model_call_ledger p
    WHERE p.invocation_id IN (SELECT invocation_id FROM rca_model_call
      WHERE run_id='${RUNID}' AND invocation_id IS NOT NULL)" '-At')"
[ "$_platpoll" = "0" ] || r7_fail "phase6 平台账本出现 RCA 调用行 ${_platpoll} 笔（跨域污染，BA-109 裁定面被破）"
r7_log "phase6 PASS（$(wc -l < "$RUNS/phase6-model-calls.txt" | tr -d ' ') 笔调用全 SUCCESS 结清；捕获恰一行+digest 对账一致（R2）；快照回填非空（R1）；平台账本零交叉）"

# ---------------------------------------------------------------------------
# phase7 报告生产链
# ---------------------------------------------------------------------------
r7_log "phase7 报告链：report → publication → outbox"
r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_report WHERE run_id='${RUNID}'" '-At' \
    > "$RUNS/phase7-report-count.txt"
[ "$(cat "$RUNS/phase7-report-count.txt")" -ge 1 ] || r7_fail "phase7 报告缺行"
r7_psql_ro R7_PG_URL "SELECT substring(package::text from 1 for 400)
    FROM rca_report WHERE run_id='${RUNID}' LIMIT 1" '-At' | r7_redact \
    > "$RUNS/phase7-package-head.txt"
grep -qi 'claim' "$RUNS/phase7-package-head.txt" \
    || r7_log "phase7 提示：报告包头部 400 字未见 claim 词形（全文见 DB，不硬断言）"
r7_db_poll_ge "phase7 report_publication 落行" 30 R7_PG_URL \
    "SELECT count(*) FROM report_publication p JOIN rca_report rr ON p.report_id=rr.id
     WHERE rr.run_id='${RUNID}'" 1
r7_db_poll_ge "phase7 notify_outbox 落行" 30 R7_PG_URL \
    "SELECT count(*) FROM notify_outbox WHERE report_id IN
     (SELECT id FROM rca_report WHERE run_id='${RUNID}')" 1
r7_log "phase7 PASS（报告/发布/外发全落行）"

# ---------------------------------------------------------------------------
# phase8 日志取证 + 密钥扫描
# ---------------------------------------------------------------------------
r7_log "phase8 日志取证（脱敏）+ 供应商密钥词形零回显"
r7_dump_container_logs "$T0" "$RUNS/control-app.log" > "$RUNS/phase8-ledger-fail-count.txt" || true
grep -icE 'sk-[A-Za-z0-9]{8,}' "$RUNS/control-app.log" > "$RUNS/phase8-sk-hits.txt" || true
[ "$(cat "$RUNS/phase8-sk-hits.txt")" = "0" ] \
    || r7_fail "phase8 日志存在供应商密钥词形（脱敏后仍命中 sk-…，人工复核 control-app.log）"
r7_log "phase8 PASS（日志已脱敏落盘；「账本 STARTED 写失败」计数=$(cat "$RUNS/phase8-ledger-fail-count.txt" 2>/dev/null || echo 0)）"

r7_resource_snapshot "$RUNS" "post"
r7_scenario_result "$RUNS" "R7-A0" \
    "A0 真实 checkout 现场主 Agent 受限直查带引用报告（零子 Agent+供应商回执链）" \
    "real-provider@195" "PASS"
r7_log "SUITE PASS：A0 断言全绿（run=$RUNID digest=$DA0 证据=$RUNS）"
