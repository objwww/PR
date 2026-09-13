#!/bin/sh
# ============================================================================
# e2e-b2-cl06-v2.sh —— B2 CL-06 跨轮记忆真窗断言 v2（S0/U02/U03 修复版）
#
# v1→v2 变更（docs/告警-BUGLOG与B2测试审查-统一收口技术方案-v1.md §4.2/§4.3）：
#   phase7 绑案仲裁（U02/N09~N12）：winner=本案 pub SENT+outbox；loser=本案报告
#     零 pub 行+零 outbox 行【且】同 incident 存在更早 SENT winner——纯 DB 推导，
#     零 docker logs 依赖（容器重建即蒸发，日志非数据库事实；v1 无作用域 grep
#     可借别案 loser 行放行本案）。pub 行在但 state=DEAD=发送耗尽，非仲裁 loser，
#     FAIL。产品尚无持久 loser 仲裁行（winnerReportId 审计字段）——U02 产品面
#     任务，本 driver 先用可推导事实，不新增产品字段。
#   phase9 父链/锚点（U03/N13~N17）：
#     父链=同 run 作用域按 (checkpoint_revision,id) 序 LAG 对拍——首行无父、
#     其余父=前一已提交行；revision 严格递增但【允许间隙】（DELEGATE/FINAL/
#     配置切换耗版次不写记忆，v1 强制 rev-1 连号会误杀合法多批流）；
#     父行跨 run 错连=FAIL。
#     锚点=LEFT JOIN+NULL-safe（cp.memory_id IS NULL / m.id IS NULL 悬空 /
#     digest NULL 均显式 FAIL——v1 INNER JOIN 时违规行从 JOIN 消失→count=0 假绿）。
#   phase10（§4.1.6）：SUITE 判定依赖 verify2 退出码（0=PASS/3=INCONCLUSIVE
#     不算整体过/1,2=FAIL）。
#   退出面：EXIT trap 恒输出 RUN_ID_RESULT=<run>（wrapper 据此取案，零 ls -t）。
# 归档原件 docs/测试证据/R7/runs/b2-cl06-20260913/scripts/e2e-b2-cl06.sh 不动。
# ============================================================================
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/e2e-r7-common.sh"

RUNID="unknown"
trap 'echo "RUN_ID_RESULT=${RUNID}"' EXIT

SUITE="b2cl06v2-$(r7_suite_run_id)$$"
RUNS="${R7_RUNS_DIR:-./runs}/${SUITE}"
mkdir -p "$RUNS"
SVC="${R7_A0_SERVICE:-checkout}"
AN="E2EB2Cl06CrossRound"
ALLOWLIST="${R7_PRIMARY_ALLOWLIST:-prometheus.query,logs.query,prometheus.instant,prometheus.metric_value,prometheus.catalog,prometheus.label_values,prometheus.rules,logs.aggregate}"
r7_log "suite=$SUITE runs=$RUNS scene=service:$SVC alertname:$AN (委派面 v2)"

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
r7_log "phase2 注入合成告警（${AN}@$SVC）"
_code="$(r7_inject_alert "$AN" "$SVC" firing "$RUNS" "multi-signal: latency + errors + suspected configuration change on checkout [${SUITE}]")"
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
r7_log "phase3 等 run 终态（真模型 900s 上限；两批委派收敛窗更宽）"
STATE="$(r7_wait_run_terminal R7_PG_URL "$RUNID" 900)"
[ "$STATE" = "SUCCEEDED" ] || r7_fail "phase3 run 终态=$STATE（CL-06 要求 SUCCEEDED；取证见 $RUNS）"
r7_log "phase3 PASS（run SUCCEEDED）"

# phase4 主链断言（委派变体）
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
echo "$CP" | grep -qE '^[1-9][0-9]*\|[1-9]\|[0-9]+\|[1-9][0-9]*\|true$' \
    || r7_fail "phase4 检查点面异常（需 round≥1/steps∈[1,9]/batches≥1/final_claims 非空）: $CP"
r7_log "phase4 PASS（主 DONE+委派 $_ndel 子任务；checkpoint round/steps/batches：$CP）"

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

# phase6 供应商回执链
r7_log "phase6 回执链：全 SUCCESS 结清 + 捕获对账（R2）+ 快照回填（R1）+ 平台零交叉"
r7_psql_ro R7_PG_URL "SELECT state||'|'||action_seq||'|'||coalesce(route_id,'')||'|'||coalesce(error_code,'')
    FROM rca_model_call WHERE run_id='${RUNID}' ORDER BY action_seq" '-At' \
    > "$RUNS/phase6-model-calls.txt"
[ -s "$RUNS/phase6-model-calls.txt" ] || r7_fail "phase6 rca_model_call 恒零"
_bad="$(grep -vcE '^SUCCESS\|' "$RUNS/phase6-model-calls.txt")" || _bad=0
[ "${_bad:-0}" = "0" ] || r7_fail "phase6 存在非 SUCCESS 模型调用行: $(grep -vE '^SUCCESS\|' "$RUNS/phase6-model-calls.txt" | tr '\n' ' ')"
_nosettled="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call
    WHERE run_id='${RUNID}' AND (state <> 'SUCCESS'
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

# phase7 报告链 v2 —— 绑案仲裁（U02/N09~N12）：纯 DB 事实，零 docker logs
r7_log "phase7 报告链 v2：winner=本案 pub SENT+outbox / loser=本案零 pub 零 outbox+同 incident 更早 SENT winner"
r7_db_poll_ge "phase7 report 落行" 60 R7_PG_URL \
    "SELECT count(*) FROM rca_report WHERE run_id='${RUNID}'" 1
_pubstate="$(r7_psql_ro R7_PG_URL "SELECT p.state FROM report_publication p
    JOIN rca_report rr ON p.report_id=rr.id
    WHERE rr.run_id='${RUNID}' ORDER BY p.updated_at DESC LIMIT 1" '-At')"
if [ -n "${_pubstate:-}" ]; then
    [ "$_pubstate" = "SENT" ] \
        || r7_fail "phase7 本案 publication state=${_pubstate}（DEAD=发送尝试耗尽，非仲裁 loser——发送链断）"
    r7_db_poll_ge "phase7 notify_outbox 落行（winner 送达面·落库子门）" 60 R7_PG_URL \
        "SELECT count(*) FROM notify_outbox WHERE report_id IN
         (SELECT id FROM rca_report WHERE run_id='${RUNID}')" 1
    r7_log "phase7 PASS（winner 面·绑案：本案 pub SENT + outbox 落行；接收器持久回执=送达门，另由 RR27 面验收）"
else
    _nrep="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_report WHERE run_id='${RUNID}'" '-At')"
    _loser_ok="$(r7_psql_ro R7_PG_URL "SELECT count(*)
        FROM rca_report mine
        JOIN rca_run cr ON cr.id = mine.run_id
        WHERE mine.run_id='${RUNID}'
          AND NOT EXISTS (SELECT 1 FROM report_publication p WHERE p.report_id=mine.id)
          AND NOT EXISTS (SELECT 1 FROM notify_outbox o WHERE o.report_id=mine.id)
          AND EXISTS (SELECT 1 FROM rca_report w
                      JOIN rca_run wr ON wr.id=w.run_id
                      JOIN report_publication wp ON wp.report_id=w.id
                      WHERE wr.incident_id=cr.incident_id
                        AND wp.state='SENT'
                        AND w.created_at < mine.created_at)" '-At')"
    [ "${_loser_ok:-0}" = "${_nrep:-0}" ] && [ "${_nrep:-0}" -ge 1 ] \
        || r7_fail "phase7 loser 前提不成立（本案报告 ${_nrep} 行，满足绑案前提 ${_loser_ok} 行：需零 pub+零 outbox+同 incident 更早 SENT winner 在档——N09/N11）"
    r7_log "phase7 PASS（loser 面·绑案成立：本案零发布零外发 + 同 incident 更早 SENT winner 在档=仲裁语义正确）"
fi

# phase8 日志取证（脱敏）+ 密钥词形零回显
r7_log "phase8 日志取证（脱敏）+ 密钥词形零回显"
docker logs deploy-control-app-1 --since 30m 2>&1 \
    | grep -iE 'apikey|authorization|bearer|sk-' | grep -vE 'bearer[_-]?(token)?(键|key)?[=:]?\s*\(?(masked|redacted|\*\*)' \
    > "$RUNS/phase8-secret-scan.txt" || true
[ -s "$RUNS/phase8-secret-scan.txt" ] && r7_fail "phase8 日志疑似密钥回显: $(head -3 "$RUNS/phase8-secret-scan.txt")"
r7_log "phase8 PASS"

# phase9 CL-06 跨轮记忆 v2 —— LAG 同作用域父链（允许间隙）+ LEFT JOIN NULL-safe 锚点
r7_log "phase9 CL-06 记忆链 v2：schema2/LAG 父链（允许间隙）/锚点 NULL-safe/槽纯度"
_memcnt="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_working_memory
    WHERE run_id='${RUNID}'" '-At')"
[ "${_memcnt:-0}" -ge 2 ] || r7_fail "phase9 记忆行 ${_memcnt}（<2，无累计链可言）"
_schema2="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_working_memory
    WHERE run_id='${RUNID}' AND schema_version <> 2" '-At')"
[ "$_schema2" = "0" ] || r7_fail "phase9 存在 schema_version<>2 行 $_schema2 个"
# 父链（N13~N17）：首行无父；其余父=按 (rev,id) 序前一已提交行（允许 revision 间隙——
# 委派/final/配置切换耗版次不写记忆）；父行必须同 run（跨作用域错连拒）；无环由
# 「父=严格前一」结构保证。
_chainbrk="$(r7_psql_ro R7_PG_URL "WITH ordered AS (
        SELECT id, parent_memory_id,
               LAG(id) OVER (ORDER BY checkpoint_revision, id) prev_id,
               row_number() OVER (ORDER BY checkpoint_revision, id) rn
        FROM rca_working_memory WHERE run_id='${RUNID}')
    SELECT count(*) FROM ordered o
    LEFT JOIN rca_working_memory p ON o.parent_memory_id=p.id
    WHERE (o.rn=1 AND o.parent_memory_id IS NOT NULL)
       OR (o.rn>1 AND (o.parent_memory_id IS NULL
                      OR o.parent_memory_id <> o.prev_id
                      OR p.run_id <> '${RUNID}'))" '-At')"
[ "$_chainbrk" = "0" ] || r7_fail "phase9 父链断裂/跳连/跨 run 错连 $_chainbrk 处（LAG 同作用域契约）"
_revdup="$(r7_psql_ro R7_PG_URL "SELECT count(*) - count(DISTINCT checkpoint_revision)
    FROM rca_working_memory WHERE run_id='${RUNID}'" '-At')"
[ "${_revdup:-0}" = "0" ] || r7_fail "phase9 checkpoint_revision 重复 ${_revdup} 行（须严格递增）"
# 锚点（N14）：LEFT JOIN+NULL-safe——memory_id NULL/悬空行/锚非最新行/digest NULL 或不等均显式 FAIL
_anchor="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_primary_checkpoint cp
    LEFT JOIN rca_working_memory m ON cp.memory_id=m.id
    WHERE cp.run_id='${RUNID}'
      AND (cp.memory_id IS NULL
           OR m.id IS NULL
           OR m.run_id <> cp.run_id
           OR m.checkpoint_revision <> (SELECT max(checkpoint_revision)
                                        FROM rca_working_memory WHERE run_id='${RUNID}')
           OR m.memory_digest IS DISTINCT FROM cp.memory_digest)" '-At')"
[ "$_anchor" = "0" ] || r7_fail "phase9 检查点记忆锚点悬空/漂移 $_anchor 处（LEFT JOIN NULL-safe）"
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
r7_log "phase9 PASS（记忆行 $_memcnt；LAG 父链连号允许间隙；锚点 NULL-safe 回环；槽纯度绿；FULL 捕获在）"

# phase10 跨轮 prompt 可达性 v2 —— SUITE 判定门（§4.1.6：verify2 退出码参与整体结论）
r7_log "phase10 verify2 验证器门（0=PASS / 3=INCONCLUSIVE 不算整体过 / 1,2=FAIL）"
V2RC=0
python3 "${SCRIPT_DIR}/b2-cl06-verify2.py" --run-id "$RUNID" --out "$RUNS" \
    > "$RUNS/phase10-verify2.log" 2>&1 || V2RC=$?
echo "$V2RC" > "$RUNS/phase10-verify2.exit"
case "$V2RC" in
    0) r7_log "phase10 PASS（verify2=0：结构+非空 witness 语义双过）" ;;
    3) r7_fail "phase10 INCONCLUSIVE（verify2=3：结构过但跨轮槽恒空——非空 witness 未取得，不得整体 PASS，见 $RUNS/verify2-result.json）" ;;
    *) r7_fail "phase10 FAIL/ERROR（verify2=$V2RC，见 $RUNS/phase10-verify2.log 与 verify2-result.json）" ;;
esac

r7_resource_snapshot "$RUNS" "post"
r7_scenario_result "$RUNS" "B2-CL06-V2" \
    "B2 CL-06 跨轮记忆真窗 v2（绑案仲裁+LAG 父链+NULL-safe 锚点+verify2 门；run=$RUNID）" \
    "real-provider@195" "PASS"
r7_log "SUITE PASS：CL-06 v2 驱动断言全绿 + verify2=0（run=$RUNID digest=$DA0 证据=$RUNS）"
