#!/bin/sh
# ============================================================================
# e2e-r12-quota-fallback.sh —— R4 真机正证：模型额度耗尽自动切换（2026-09-12 用户指令
# "当模型额度不够时，可以切换模型"）
#
# 姿态（操作员预置，见 RUNBOOK 补记）：
#   litellm-am3 发一把 max_budget≈0 的虚拟钥匙做主路由 AGENT_MODEL_API_KEY——
#   首笔真实花费即触发 litellm 预算耗尽：HTTP 429 + error.type=budget_exceeded
#   （195 真机实证 wire 形状）。备路由 AGENT_MODEL_FALLBACK=deepseek-v4-flash-0731
#   + AGENT_MODEL_API_KEY_FALLBACK=<异于主路由的钥匙>（A4 资格矩阵：ACCOUNT 级
#   故障仅异 quota_scope 可切——钥匙不同即 quota_scope 不同，五元组非全同过 EX-42）。
#
# 断言面（只读 SQL 全经 r7_psql_ro）：
#   ① run SUCCEEDED（切换后链路真收敛，非降级假收尾）
#   ② 额度败点归因：rca_event ≥1 笔 GATEWAY_MODEL_FALLBACK_SELECTED 且
#      payload reason=QUOTA_EXHAUSTED / fault_scope=ACCOUNT / to_route=fallback
#      （供应商级失败是 ModelGateway 内部语义，不进 rca_model_call 账本——
#      RcaModelEventSink 把决策事件汇聚为 GATEWAY_* rca_event）
#   ③ 备路由接管：rca_model_call ≥1 行 state=SUCCESS route_id=fallback
#      requested_model=<备模型>；且账行全 SUCCESS（切换后零残留 FAILED/UNKNOWN）
#   ④ R2/R1 复用断言：每笔 SUCCESS 恰一行捕获+digest 对账；检查点快照回填非空
#   ⑤ 结论面计数（信息性）：ACTIVE Claim/evidence 如实落日志——结论语义归 A0
#      健康路径断言，模型侧随机性（UNRESOLVED/allowlist 外工具选择）不作切换语义断言
#
# 本脚本对 195 无破坏性动作（不 kill/重启容器；不写 litellm 钥匙）。
# ============================================================================

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/e2e-r7-common.sh"

SUITE="r12fb-$(r7_suite_run_id)"
RUNS="${R7_RUNS_DIR:-./runs}/${SUITE}"
mkdir -p "$RUNS"
# 复用 A0 的 whitelist 路由（active bundle 白名单=alertname|service），BA-111 去重语义
# 下以唯一 summary 区分调查输入
SVC="${R7_A0_SERVICE:-checkout}"
AN="E2EA0CheckoutProbe"
PRIMARY_MODEL="${R7_PRIMARY_MODEL:-qwen3-max-preview}"
FALLBACK_MODEL="${R7_FALLBACK_MODEL:-deepseek-v4-flash-0731}"
r7_log "suite=$SUITE runs=$RUNS scene=service:$SVC alertname:$AN primary=$PRIMARY_MODEL fallback=$FALLBACK_MODEL"

r7_resource_snapshot "$RUNS" "pre"

# ---------------------------------------------------------------------------
# phase1 姿态 + 注入（summary 唯一 → 新 episode 新 run）
# ---------------------------------------------------------------------------
r7_log "phase1 姿态：health 200 + 注入 ${AN}@$SVC"
r7_health "$RUNS"
_code="$(r7_inject_alert "$AN" "$SVC" firing "$RUNS" "E2E-R12 quota-fallback ${SUITE}")"
[ "$_code" = "202" ] || r7_fail "phase1 注入期望 202 实得 $_code: $(cat "$RUNS/alert-${SVC}-firing.resp")"

WL_L="alertname=$(echo "$AN" | tr '[:upper:]' '[:lower:]')|service=${SVC}"
WLKEY="${WL_L}:${WL_L}"
# 决策行按 id 窗口定位本套件行（表内存在历史 WHITELISTED 行，缺窗口会误读旧 run——
# 首跑实证：poll 即刻命中旧行拿 FAILED run 翻车）
_PREMAX="$(r7_psql_ro R7_PG_URL "SELECT coalesce(max(id),0) FROM canary_route_decision" '-At')"
r7_db_poll_ge "phase1 本套件 WHITELISTED 审计行（run_id 非空）" 120 R7_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE id > ${_PREMAX}
     AND decision='WHITELISTED' AND run_id IS NOT NULL" 1
RUNID="$(r7_psql_ro R7_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE id > ${_PREMAX} AND decision='WHITELISTED'
    ORDER BY id DESC LIMIT 1" '-At')"
r7_log "phase1 PASS（run=$RUNID）"

# ---------------------------------------------------------------------------
# phase2 run 终态：SUCCEEDED（额度切换后必须真收敛）
# ---------------------------------------------------------------------------
r7_log "phase2 等 run 终态（真模型 900s 上限）"
STATE="$(r7_wait_run_terminal R7_PG_URL "$RUNID" 900)"
[ "$STATE" = "SUCCEEDED" ] || r7_fail "phase2 run 终态=$STATE（切换失败或链路未收敛；取证 $RUNS）"
r7_log "phase2 PASS（run SUCCEEDED）"

# ---------------------------------------------------------------------------
# phase3 R4 回退断言：rca_event 决策事件 + rca_model_call 接管账本
#   供应商级失败是 ModelGateway 内部语义：rca_model_call 一操作一行，成功切换后
#   全 SUCCESS 且 route_id=fallback；切换决策证据落 rca_event
#   （GATEWAY_MODEL_FALLBACK_SELECTED，RcaModelEventSink 汇聚；payload 附
#   reason/fault_scope/from_route/to_route）
# ---------------------------------------------------------------------------
r7_log "phase3 R4 账本：额度败点决策事件 + 备路由 SUCCESS 接管"
r7_psql_ro R7_PG_URL "SELECT physical_seq||'|'||action_seq||'|'||state||'|'||
    coalesce(route_id,'')||'|'||coalesce(requested_model,'')||'|'||coalesce(error_code,'')
    FROM rca_model_call WHERE run_id='${RUNID}' ORDER BY physical_seq" \
    '-At' > "$RUNS/phase3-model-calls.txt"
[ -s "$RUNS/phase3-model-calls.txt" ] || r7_fail "phase3 rca_model_call 恒零（主模式未触网？姿态核对）"
r7_psql_ro R7_PG_URL "SELECT event_type||'|'||(payload->>'reason')||'|'||
    (payload->>'fault_scope')||'|'||(payload->>'from_route')||'|'||(payload->>'to_route')
    FROM rca_event WHERE run_id='${RUNID}' AND
    event_type LIKE 'GATEWAY_MODEL_FALLBACK%' ORDER BY seq" \
    '-At' > "$RUNS/phase3-fallback-events.txt"

_qfail="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_event
    WHERE run_id='${RUNID}' AND event_type='GATEWAY_MODEL_FALLBACK_SELECTED'
      AND payload->>'reason'='QUOTA_EXHAUSTED' AND payload->>'fault_scope'='ACCOUNT'
      AND payload->>'to_route'='fallback'" '-At')"
[ "${_qfail:-0}" -ge 1 ] || r7_fail "phase3 无额度败点决策事件（QUOTA_EXHAUSTED/ACCOUNT/→fallback）\
——主路由未按额度语义切换，核对 litellm 预算钥匙姿态: $(cat "$RUNS/phase3-fallback-events.txt")"

_fbsucc="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call
    WHERE run_id='${RUNID}' AND state='SUCCESS' AND route_id='fallback'
      AND requested_model='${FALLBACK_MODEL}'" '-At')"
[ "${_fbsucc:-0}" -ge 1 ] || r7_fail "phase3 备路由零接管（SUCCESS@$FALLBACK_MODEL route=fallback）\
——fallback 未生效: $(cat "$RUNS/phase3-model-calls.txt")"

_bad="$(grep -vcE '^[0-9]+\|[0-9]+\|SUCCESS\|' "$RUNS/phase3-model-calls.txt")" || _bad=0
[ "${_bad:-0}" = "0" ] || r7_fail "phase3 存在非 SUCCESS 账行（切换后必须全结清）:\
$(grep -vE '^[0-9]+\|[0-9]+\|SUCCESS\|' "$RUNS/phase3-model-calls.txt" | tr '\n' ' ')"
_rcheck="$(grep -cE '\|fallback\|' "$RUNS/phase3-model-calls.txt")" || _rcheck=0
[ "${_rcheck:-0}" -ge 1 ] || r7_fail "phase3 账本零 fallback 路由行"
_primsucc="$(grep -cE '\|primary\|' "$RUNS/phase3-model-calls.txt")" || _primsucc=0
r7_log "phase3 PASS（QUOTA_EXHAUSTED 决策事件 $_qfail 笔；备路由接管 $_fbsucc/$_rcheck 笔；主路由直达 ${_primsucc} 笔）"

# ---------------------------------------------------------------------------
# phase4 R2 捕获 + R1 快照回填（对 SUCCESS 面复用 A0 phase6 断言）
# ---------------------------------------------------------------------------
r7_log "phase4 R2 捕获恰一行+digest 对账；R1 检查点快照回填"
_nocap="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call m
    WHERE m.run_id='${RUNID}' AND m.state='SUCCESS'
      AND (SELECT count(*) FROM rca_model_input i
           WHERE i.model_call_id=m.id) <> 1" '-At')"
[ "${_nocap:-0}" = "0" ] || r7_fail "phase4 R2: ${_nocap} 笔 SUCCESS 调用捕获行数≠1"
_digmis="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call m
    JOIN rca_model_input i ON i.model_call_id=m.id
    WHERE m.run_id='${RUNID}' AND m.prompt_digest <> i.prompt_digest" '-At')"
[ "${_digmis:-0}" = "0" ] || r7_fail "phase4 R2: ${_digmis} 行捕获 digest 与账行不对账"
_nosnap="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_primary_checkpoint
    WHERE run_id='${RUNID}' AND steps_used > 0 AND input_snapshot_digest IS NULL" '-At')"
[ "${_nosnap:-0}" = "0" ] || r7_fail "phase4 R1: ${_nosnap} 个已推进检查点快照为空"
r7_log "phase4 PASS"

# ---------------------------------------------------------------------------
# phase5 结论面（信息性，不断言）：结论语义（ACTIVE TRUE Claim+证据锚）归 A0
# 健康路径断言；本探针的收敛语义由 phase2 run SUCCEEDED 承担。模型侧随机性
# （如选到 allowlist 外工具、证据不足判 UNRESOLVED）不是切换语义的一部分——
# 只如实记录计数供证据包核对。
# ---------------------------------------------------------------------------
r7_log "phase5 结论面计数（信息性）"
_nclaims="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_claim
    WHERE run_id='${RUNID}' AND lifecycle='ACTIVE'" '-At')"
_nev="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_evidence
    WHERE run_id='${RUNID}'" '-At')"
r7_log "phase5 INFO（ACTIVE Claim=${_nclaims:-0}；evidence=${_nev:-0}）"

r7_resource_snapshot "$RUNS" "post"
r7_log "SUITE PASS（R4 真机正证：额度耗尽→QUOTA_EXHAUSTED→备路由 ${FALLBACK_MODEL} 接管→链路真收敛；run=$RUNID）"
