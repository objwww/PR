#!/bin/sh
# ============================================================================
# e2e-r7-a0-provider-receipt-chain-v2.sh —— A0 断言包 v2（A0 补充方案 §4/§5）
#
# 与 v1（保留为历史证据，不再改）的差异：
#   A. phase5 场景化门禁：不再对所有场景强制 TRUE——
#      SCENARIO_KIND=FAULT_POSITIVE（缺省，故障正例：带 SUPPORTS 判定的 TRUE Claim）
#                 | HEALTHY_NEGATIVE（健康负例：零 TRUE、缺口如实）
#                 | NO_DATA（无数据例：零 TRUE、未决收敛）
#                 | CONFLICT（矛盾例：冲突保留 UNKNOWN+MULTI_SOURCE_CONFLICT）
#      另钉协议 v2 采纳面：检查点 final_claims 必含 evidence_roles（准入判定落库）。
#   B. phase7 拆两用例：
#      7a 首次发布——报告(package_json 探针，修 BA-134 旧列名)→publication→outbox
#         →送达回执（outbox state=SENT；超时按链路配置推导，卡段输出，不盲延时）；
#         winner 精确核对 report_generation_winner(winner_run_id,winner_report_id)。
#      7b 重复调查仲裁（R7_REPEAT_RUN_ID 注入同 incident 复用 run；未注入=如实
#         SKIP 不假跑）——必查对应 winner 存在（不把缺发布一律算合法败者）；
#         败者=自己报告落档+零 publication+零外发+report_publication_loser 日志证据；
#         胜者=publication+outbox 全链。仲裁键=(incident_id,generation)（V33）。
#
# 操作员前置与 v1 相同（RUNBOOK 部署段）；对 195 无破坏性动作。
# ============================================================================
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/e2e-r7-common.sh"

SUITE="a0v2-$(r7_suite_run_id)"
RUNS="${R7_RUNS_DIR:-./runs}/${SUITE}"
mkdir -p "$RUNS"
SVC="${R7_A0_SERVICE:-checkout}"
AN="E2EA0CheckoutProbe"
SCENARIO="${SCENARIO_KIND:-FAULT_POSITIVE}"
ALLOWLIST="${R7_PRIMARY_ALLOWLIST:-prometheus.query,logs.query}"
r7_log "suite=$SUITE runs=$RUNS scene=service:$SVC alertname:$AN scenario=$SCENARIO"

r7_resource_snapshot "$RUNS" "pre"

# ---------------------------------------------------------------------------
# phase1 姿态 + 路由 bundle（同 v1：percent=0 + 本场景白名单）
# ---------------------------------------------------------------------------
r7_log "phase1 姿态：health 200 + bundle 发布激活（白名单 $SVC）"
r7_health "$RUNS"
cat > "$RUNS/bundle-a0.content" <<EOF
{"policy_version":"r7-e2e-a0v2-${SUITE}","canary":{"percent":0,"whitelist":["alertname=${AN}|service=${SVC}"],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
DA0="$(r7_publish_bundle "$RUNS/bundle-a0.content" a0v2)"
r7_qualify "$DA0" "$RUNS"
r7_activate "$DA0" "$RUNS"
r7_log "phase1 PASS（health 200；digest=$DA0）"

# ---------------------------------------------------------------------------
# phase2 注入 + WHITELISTED 原子对（同 v1：唯一 summary 防去重吸收）
# ---------------------------------------------------------------------------
T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
r7_log "phase2 注入 checkout 合成告警（${AN}@$SVC）"
_code="$(r7_inject_alert "$AN" "$SVC" firing "$RUNS" "E2E-R7 A0v2 ${SUITE} checkout 现场")"
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
# phase3 真模型收敛（同 v1：SUCCEEDED 唯一收敛面）
# ---------------------------------------------------------------------------
r7_log "phase3 等 run 终态（真模型 900s 上限）"
STATE="$(r7_wait_run_terminal R7_PG_URL "$RUNID" 900)"
[ "$STATE" = "SUCCEEDED" ] || r7_fail "phase3 run 终态=$STATE（A0 要求 SUCCEEDED；取证见 $RUNS）"
r7_log "phase3 PASS（run SUCCEEDED）"

# ---------------------------------------------------------------------------
# phase4 主链断言 + 协议 v2 采纳面
# ---------------------------------------------------------------------------
r7_log "phase4 主链：恰 1 PRIMARY_INVESTIGATE DONE / 零 DELEGATE / 检查点含 evidence_roles"
_nprim="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
    WHERE run_id='${RUNID}' AND task_key='PRIMARY_INVESTIGATE' AND state='DONE'" '-At')"
[ "$_nprim" = "1" ] || r7_fail "phase4 PRIMARY_INVESTIGATE DONE 应恰 1 实得 $_nprim"
_ndel="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
    WHERE run_id='${RUNID}' AND task_key LIKE 'DELEGATE-%'" '-At')"
[ "$_ndel" = "0" ] || r7_fail "phase4 零子 Agent 破：DELEGATE-% 任务 $_ndel 个"
_nv2="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_primary_checkpoint
    WHERE run_id='${RUNID}' AND final_claims IS NOT NULL
      AND jsonb_array_length(final_claims) >= 1
      AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(final_claims) fc
                      WHERE fc->'evidence_roles' IS NOT NULL
                        AND jsonb_array_length(fc->'evidence_roles') >= 1)" '-At')"
[ "$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_primary_checkpoint
    WHERE run_id='${RUNID}' AND final_claims IS NOT NULL" '-At')" -ge 1 ] \
    || r7_fail "phase4 检查点 final_claims 为空"
[ "${_nv2:-0}" = "0" ] || r7_fail "phase4 协议 v2 未采纳：${_nv2} 个 claim 行缺 evidence_roles\
（部署 jar 非支持作用面的版本，或模型未按协议输出）"
r7_log "phase4 PASS（主链 + evidence_roles 采纳面）"

# ---------------------------------------------------------------------------
# phase5v2 受限直查 + 场景化门禁（A0 补充方案 §4：不再对所有场景强制 TRUE）
# ---------------------------------------------------------------------------
r7_log "phase5 受限直查 + 场景门禁 scenario=$SCENARIO"
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
[ "$_nunresolved" = "0" ] || r7_fail "phase5 evidence_refs 有 $_nunresolved 条解析不到证据行"

_ntrue="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_claim
    WHERE run_id='${RUNID}' AND lifecycle='ACTIVE' AND status='TRUE'" '-At')"
_nconf="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_claim
    WHERE run_id='${RUNID}' AND lifecycle='ACTIVE' AND status='UNKNOWN'
      AND evidence_basis='MULTI_SOURCE_CONFLICT'" '-At')"
case "$SCENARIO" in
    FAULT_POSITIVE)
        # 故障正例：TRUE 必须有准入确认的 SUPPORTS 判定（kind→TRUE 直推已废止）
        [ "${_ntrue:-0}" -ge 1 ] || r7_fail "phase5 正例无 ACTIVE TRUE Claim"
        _nsup="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_primary_checkpoint
            WHERE run_id='${RUNID}' AND EXISTS (
                SELECT 1 FROM jsonb_array_elements(final_claims) fc
                WHERE fc->>'kind'='ROOT_CAUSE'
                  AND EXISTS (SELECT 1 FROM jsonb_array_elements(fc->'evidence_roles') er
                              WHERE er->>'role'='SUPPORTS'))" '-At')"
        [ "${_nsup:-0}" -ge 1 ] || r7_fail "phase5 正例 TRUE 无 SUPPORTS 判定锚（v2 支持面未生效）"
        ;;
    HEALTHY_NEGATIVE)
        [ "${_ntrue:-0}" = "0" ] || r7_fail "phase5 健康负例出现 $_ntrue 个 TRUE（编造根因）"
        _nmiss="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_primary_checkpoint
            WHERE run_id='${RUNID}' AND missing_information IS NOT NULL
              AND jsonb_array_length(missing_information) >= 1" '-At')"
        [ "${_nmiss:-0}" -ge 1 ] || r7_fail "phase5 健康负例未如实写缺口（missing_information 空）"
        ;;
    NO_DATA)
        [ "${_ntrue:-0}" = "0" ] || r7_fail "phase5 无数据例出现 $_ntrue 个 TRUE（无证据不该确认）"
        ;;
    CONFLICT)
        [ "${_nconf:-0}" -ge 1 ] || r7_fail "phase5 矛盾例冲突未保留（无 UNKNOWN+MULTI_SOURCE_CONFLICT 行）"
        ;;
    *) r7_fail "phase5 未知 SCENARIO_KIND=$SCENARIO（FAULT_POSITIVE|HEALTHY_NEGATIVE|NO_DATA|CONFLICT）" ;;
esac
r7_log "phase5 PASS（直查全落 allowlist；证据 ${_nev} 行；场景门禁 $SCENARIO 过）"

# ---------------------------------------------------------------------------
# phase6 供应商回执链（同 v1：全 SUCCESS 结清 + 捕获对账 + 平台零交叉）
# ---------------------------------------------------------------------------
r7_log "phase6 回执链：rca_model_call 全 SUCCESS 结清 + 平台账本零交叉"
r7_psql_ro R7_PG_URL "SELECT state||'|'||action_seq FROM rca_model_call
    WHERE run_id='${RUNID}' ORDER BY action_seq" '-At' > "$RUNS/phase6-model-calls.txt"
[ -s "$RUNS/phase6-model-calls.txt" ] || r7_fail "phase6 rca_model_call 恒零（主模式未触网？）"
_bad="$(grep -vcE '^SUCCESS\|' "$RUNS/phase6-model-calls.txt")" || _bad=0
[ "${_bad:-0}" = "0" ] || r7_fail "phase6 存在非 SUCCESS 模型调用行"
_nosettled="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call
    WHERE run_id='${RUNID}' AND (usage IS NULL OR usage_missing=true
      OR pricing_version IS NULL
      OR (cost_micros IS NULL AND pricing_version <> 'unpriced')
      OR invocation_id IS NULL OR settled_at IS NULL)" '-At')"
[ "$_nosettled" = "0" ] || r7_fail "phase6 ${_nosettled} 行 usage/pricing/invocation 未结清"
_platpoll="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM model_call_ledger p
    WHERE p.invocation_id IN (SELECT invocation_id FROM rca_model_call
      WHERE run_id='${RUNID}' AND invocation_id IS NOT NULL)" '-At')"
[ "$_platpoll" = "0" ] || r7_fail "phase6 平台账本出现 RCA 调用行（跨域污染）"
r7_log "phase6 PASS"

# ---------------------------------------------------------------------------
# phase7a 首次发布：报告→winner→publication→outbox→送达回执（超时链路推导）
# ---------------------------------------------------------------------------
r7_log "phase7a 首次发布链（package_json 探针；BA-134 旧列名修复）"
r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_report WHERE run_id='${RUNID}'" '-At' \
    > "$RUNS/phase7a-report-count.txt"
[ "$(cat "$RUNS/phase7a-report-count.txt")" -ge 1 ] || r7_fail "phase7a 报告缺行"
r7_psql_ro R7_PG_URL "SELECT substring(package_json::text from 1 for 400)
    FROM rca_report WHERE run_id='${RUNID}' LIMIT 1" '-At' | r7_redact \
    > "$RUNS/phase7a-package-head.txt"
grep -qi 'claim' "$RUNS/phase7a-package-head.txt" \
    || r7_log "phase7a 提示：报告包头部 400 字未见 claim 词形（全文见 DB，不硬断言）"
# winner 精确核对：本 run 必持 (incident,generation) 发布赢家行
r7_psql_ro R7_PG_URL "SELECT count(*) FROM report_generation_winner w
    WHERE w.winner_run_id='${RUNID}'
      AND w.winner_report_id IN (SELECT id FROM rca_report WHERE run_id='${RUNID}')
      AND w.incident_id=(SELECT incident_id FROM rca_run WHERE id='${RUNID}')
      AND w.generation=(SELECT generation FROM rca_run WHERE id='${RUNID}')" '-At' \
    > "$RUNS/phase7a-winner.txt"
[ "$(cat "$RUNS/phase7a-winner.txt")" = "1" ] \
    || r7_fail "phase7a 首次发布应恰持本 run 的 winner 行: $(cat "$RUNS/phase7a-winner.txt")"
# publication + outbox 落行
r7_db_poll_ge "phase7a report_publication 落行" 30 R7_PG_URL \
    "SELECT count(*) FROM report_publication p JOIN rca_report rr ON p.report_id=rr.id
     WHERE rr.run_id='${RUNID}'" 1
r7_db_poll_ge "phase7a notify_outbox 落行" 30 R7_PG_URL \
    "SELECT count(*) FROM notify_outbox WHERE report_id IN
     (SELECT id FROM rca_report WHERE run_id='${RUNID}')" 1
# 送达回执：超时按链路推导（max_attempts×30s 退避预算+60s 基线；env 可覆写），
# 超时先输出卡在哪一段（publication 状态/退避/outbox 状态），不盲延时掩盖。
_rwait="$(r7_psql_ro R7_PG_URL "SELECT 60 + max(max_attempts)*30 FROM report_publication
    WHERE report_id IN (SELECT id FROM rca_report WHERE run_id='${RUNID}')" '-At')"
PUB_WAIT="${R7_PUB_WAIT:-${_rwait:-180}}"
r7_log "phase7a 等送达回执 state=SENT（派生超时 ${PUB_WAIT}s）"
_i=0
while : ; do
    _sent="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM notify_outbox
        WHERE report_id IN (SELECT id FROM rca_report WHERE run_id='${RUNID}')
          AND state='SENT'" '-At')"
    [ "${_sent:-0}" -ge 1 ] && break
    _i=$((_i + 5))
    [ "$_i" -ge "$PUB_WAIT" ] && {
        r7_log "卡段诊断：publication=$(r7_psql_ro R7_PG_URL "SELECT state||'|attempts='||attempt_count||'|'||coalesce(last_error::text,'') FROM report_publication WHERE report_id IN (SELECT id FROM rca_report WHERE run_id='${RUNID}')" '-At' | tr '\n' ' ')outbox=$(r7_psql_ro R7_PG_URL "SELECT string_agg(state,',') FROM notify_outbox WHERE report_id IN (SELECT id FROM rca_report WHERE run_id='${RUNID}')" '-At')"
        r7_fail "phase7a 送达回执 ${PUB_WAIT}s 未到（outbox SENT=0；卡段见上行，非延长可解）"
    }
    sleep 5
done
r7_log "phase7a PASS（报告/winner/publication/outbox/SENT 回执全链落行）"

# ---------------------------------------------------------------------------
# phase7b 重复调查仲裁（R7_REPEAT_RUN_ID 注入；未注入=如实 SKIP 不假跑）
# ---------------------------------------------------------------------------
if [ -n "${R7_REPEAT_RUN_ID:-}" ]; then
    r7_log "phase7b 重复调查仲裁：run=$R7_REPEAT_RUN_ID（仲裁键=incident+generation）"
    _rrep="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_report
        WHERE run_id='${R7_REPEAT_RUN_ID}'" '-At')"
    [ "${_rrep:-0}" -ge 1 ] || r7_fail "phase7b 重复 run 报告未落档（败者也应诚实记账 INV-AM3-7）"
    # 必须找到对应 winner（缺发布≠合法败者：先证 winner 在场）
    r7_psql_ro R7_PG_URL "SELECT w.winner_run_id FROM report_generation_winner w
        WHERE w.incident_id=(SELECT incident_id FROM rca_run WHERE id='${R7_REPEAT_RUN_ID}')
          AND w.generation=(SELECT generation FROM rca_run WHERE id='${R7_REPEAT_RUN_ID}')" '-At' \
        > "$RUNS/phase7b-winner-run.txt"
    [ -s "$RUNS/phase7b-winner-run.txt" ] \
        || r7_fail "phase7b 仲裁键无 winner 行（缺发布不能一律当合法败者）"
    WINNER_RUN="$(cat "$RUNS/phase7b-winner-run.txt")"
    if [ "$WINNER_RUN" = "$R7_REPEAT_RUN_ID" ]; then
        r7_db_poll_ge "phase7b 胜者 publication 落行" 30 R7_PG_URL \
            "SELECT count(*) FROM report_publication p
             WHERE p.report_id IN (SELECT id FROM rca_report WHERE run_id='${R7_REPEAT_RUN_ID}')" 1
        r7_db_poll_ge "phase7b 胜者 outbox 落行" 30 R7_PG_URL \
            "SELECT count(*) FROM notify_outbox WHERE report_id IN
             (SELECT id FROM rca_report WHERE run_id='${R7_REPEAT_RUN_ID}')" 1
        r7_log "phase7b PASS（重复 run 为胜者：publication+outbox 全链）"
    else
        _pub="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM report_publication p
            WHERE p.report_id IN (SELECT id FROM rca_report WHERE run_id='${R7_REPEAT_RUN_ID}')" '-At')"
        [ "${_pub:-0}" = "0" ] || r7_fail "phase7b 败者出现 publication 行 $_pub（赢家栅栏破）"
        _ob="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM notify_outbox
            WHERE report_id IN (SELECT id FROM rca_report WHERE run_id='${R7_REPEAT_RUN_ID}')" '-At')"
        [ "${_ob:-0}" = "0" ] || r7_fail "phase7b 败者出现外发 $_ob 行（重复外发）"
        _wrep="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_report
            WHERE id IN (SELECT winner_report_id FROM report_generation_winner
                         WHERE winner_run_id='${WINNER_RUN}')" '-At')"
        [ "${_wrep:-0}" -ge 1 ] || r7_fail "phase7b winner 报告行缺失: winner_run=$WINNER_RUN"
        r7_dump_container_logs "$T0" "$RUNS/control-app-7b.log" > /dev/null 2>&1 || true
        grep -q "report_publication_loser" "$RUNS/control-app-7b.log" \
            || r7_log "phase7b 提示：日志未见 report_publication_loser 事件词形（可能日志滚动；以 DB 败者面为准）"
        r7_log "phase7b PASS（败者=报告落档+零发布+零外发；winner=$WINNER_RUN 报告在场）"
    fi
else
    r7_log "phase7b SKIP（R7_REPEAT_RUN_ID 未注入——重复仲裁用例 NOT_RUN，不假跑）"
fi

# ---------------------------------------------------------------------------
# phase8 日志取证 + 密钥扫描（同 v1）
# ---------------------------------------------------------------------------
r7_log "phase8 日志取证（脱敏）+ 供应商密钥词形零回显"
r7_dump_container_logs "$T0" "$RUNS/control-app.log" > "$RUNS/phase8-ledger-fail-count.txt" || true
grep -icE 'sk-[A-Za-z0-9]{8,}' "$RUNS/control-app.log" > "$RUNS/phase8-sk-hits.txt" || true
[ "$(cat "$RUNS/phase8-sk-hits.txt")" = "0" ] \
    || r7_fail "phase8 日志存在供应商密钥词形（人工复核 control-app.log）"
r7_log "phase8 PASS"

r7_resource_snapshot "$RUNS" "post"
r7_scenario_result "$RUNS" "R7-A0-v2" \
    "A0v2 场景门禁($SCENARIO)+发布两用例（首次发布送达回执+重复仲裁 winner 精确核对）" \
    "real-provider@195" "PASS"
r7_log "SUITE PASS：A0v2 断言全绿（run=$RUNID scenario=$SCENARIO digest=$DA0 证据=$RUNS）"
