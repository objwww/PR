#!/bin/sh
# ============================================================================
# e2e-m4-09-shadow.sh —— E2E-M4-09：Online Read Shadow 隔离（M4-34/35 随件交付）
#
# 场景（AM4 技术方案 §15.3）：B1 故障注入后，同一 input snapshot 同时送 Holmes
# 与 Native Shadow；必须断言：
#   ① 影子镜像同一 input snapshot（影子 evidence 的 input_snapshot_digest ==
#      holmes run 的 investigation_hash，Am4ShadowTrigger L130 镜像语义）；
#   ② run / budget / ledger 隔离（行集不共用）；
#   ③ Holmes 报告与通知正常落库（主路径不受影子影响）；
#   ④ Candidate 发布增量 0（影子 run 零 report_publication / 零 notify_outbox）；
#   ⑤ 不得从 Shadow 直接切流（影子 run 零报告行，Holmes 报告恰一行）。
#
# v2（195 迭代修正）：runall 无参直调 → 自足链路（quiesce F1 → 注入 → 等 holmes
# run → e4_trigger_shadow），外部供参模式保留（HOLMES_RUN_ID/SHADOW_RUN_ID）；
# ① 的 holmes 侧对拍锚由 rca_evidence（holmes 主链不产，v1 混查恒空真，195 实
# 证）改为 rca_run.investigation_hash。
#
# 用法：sh e2e-m4-09-shadow.sh
#   或：HOLMES_RUN_ID=<uuid> SHADOW_RUN_ID=<uuid> sh e2e-m4-09-shadow.sh
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

if [ -z "${HOLMES_RUN_ID:-}" ] || [ -z "${SHADOW_RUN_ID:-}" ]; then
    e4_begin
    echo "[E2E-M4-09] 自足链路：F1 注入 → holmes run → 影子触发"
    echo "[E2E-M4-09] 注入前静止面（quiesce F1）"
    e4_quiesce F1
    T0=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    echo "[E2E-M4-09] phase1 F1 注入 T0=$T0"
    docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F1

    i=0
    HOLMES_RUN_ID=""
    while [ $i -lt "$POLL_MAX" ]; do
        HOLMES_RUN_ID=$(e4_sql "
            select r.id from rca_run r join rca_report rr on rr.run_id = r.id
             join incident i on i.id = r.incident_id
             where r.created_at >= '$T0' and r.state in ('SUCCEEDED','PARTIAL')
               and i.incident_key like '%ArenaDuplicateOrders%'
             order by r.created_at desc limit 1")
        [ -n "$HOLMES_RUN_ID" ] && break
        i=$((i + 5)); sleep 5
    done
    [ -n "$HOLMES_RUN_ID" ] || { echo "  FAIL: 无收敛 holmes run"; exit 1; }
    echo "  holmes_run=$HOLMES_RUN_ID"

    SHADOW_RUN_ID=$(e4_trigger_shadow "$HOLMES_RUN_ID") || \
        { echo "  FAIL: 影子触发一次性入口失败"; exit 1; }
    [ -n "$SHADOW_RUN_ID" ] || { echo "  FAIL: 触发器未输出影子 run id"; exit 1; }
    echo "  shadow_run=$SHADOW_RUN_ID"
else
    e4_begin
    echo "[E2E-M4-09] 外部供参模式 holmes_run=$HOLMES_RUN_ID shadow_run=$SHADOW_RUN_ID"
fi

# ① 影子镜像同一 input snapshot：影子 evidence 的 input_snapshot_digest ==
#    holmes run 的 investigation_hash（Am4ShadowTrigger L130 镜像语义）
HOLMES_SNAP=$(e4_sql "
    select investigation_hash from rca_run where id = '$HOLMES_RUN_ID'")
SHADOW_SNAP=$(e4_sql "
    select scope::jsonb->>'input_snapshot_digest' from rca_evidence
     where run_id = '$SHADOW_RUN_ID'
     order by created_at, id limit 1")
e4_assert_eq "① 影子侧证据存在" "$([ -n "$SHADOW_SNAP" ] && echo yes || echo no)" "yes"
e4_assert_eq "① 两路同一 input snapshot（影子镜像 holmes 材料 hash）" \
    "$SHADOW_SNAP" "$HOLMES_SNAP"

# ② run 隔离：影子 run 的证据/账本全部归属影子 run id
SHADOW_EVIDENCE=$(e4_sql "select count(*) from rca_evidence where run_id='$SHADOW_RUN_ID'")
e4_assert_eq "② 影子 run 证据归属影子 run（隔离）" \
    "$([ "$SHADOW_EVIDENCE" -gt 0 ] && echo yes || echo no)" "yes"

# ② 预算/账本隔离（195 实证校准）：rca_tool_invocation 只由影子/Native 链写入
#    （holmes 主链工具在 holmesgpt 容器内执行，AM4 侧工具账本零行；holmes 预算
#    面 = rca_attempt usage 与 litellm SpendLogs）——隔离即"账本行全部归属影子 run"
TOOL_ROWS_HOLMES=$(e4_sql "
    select count(*) from rca_tool_invocation where run_id='$HOLMES_RUN_ID'")
TOOL_ROWS_SHADOW=$(e4_sql "
    select count(*) from rca_tool_invocation where run_id='$SHADOW_RUN_ID'")
e4_assert_eq "② 影子工具有独立账本行" \
    "$([ "$TOOL_ROWS_SHADOW" -gt 0 ] && echo yes || echo no)" "yes"
e4_assert_eq "② 账本行零串写（holmes 主链零工具账本行）" \
    "$TOOL_ROWS_HOLMES" "0"

# ③ Holmes 主路径完好：报告落库且 STATE 终态
HOLMES_REPORT=$(e4_sql "
    select count(*) from rca_report where run_id='$HOLMES_RUN_ID'")
e4_assert_eq "③ Holmes 报告落库" "$([ "$HOLMES_REPORT" -gt 0 ] && echo yes || echo no)" "yes"

# ④ Candidate 发布增量 0：影子 run 零 publication、零 outbox（不切主硬断言；
#    发布面挂 report_id → rca_report(run_id)，影子 run 无报告即无发布行）
SHADOW_PUB=$(e4_sql "
    select count(*) from report_publication rp
     join rca_report rr on rr.id = rp.report_id
     where rr.run_id = '$SHADOW_RUN_ID'")
SHADOW_OUTBOX=$(e4_sql "
    select count(*) from notify_outbox no
     join rca_report rr on rr.id = no.report_id
     where rr.run_id = '$SHADOW_RUN_ID'")
e4_assert_eq "④ 影子 run 零发布（Candidate 增量 0）" "$SHADOW_PUB" "0"
e4_assert_eq "④ 影子 run 零通知 outbox" "$SHADOW_OUTBOX" "0"

# ⑤ 主报告未被影子改写：影子 run 零报告行（结构无报告出口的行为取证），
#    Holmes run 报告恰一行（影子不产生新报告行、不推进主报告）
SHADOW_REPORTS=$(e4_sql "select count(*) from rca_report where run_id='$SHADOW_RUN_ID'")
HOLMES_REPORTS=$(e4_sql "select count(*) from rca_report where run_id='$HOLMES_RUN_ID'")
e4_assert_eq "⑤ 影子 run 零报告行（不切主结构面）" "$SHADOW_REPORTS" "0"
e4_assert_eq "⑤ Holmes run 报告恰一行（未被影子推进）" "$HOLMES_REPORTS" "1"

{
    echo "holmes_run=$HOLMES_RUN_ID shadow_run=$SHADOW_RUN_ID"
    echo "holmes_snapshot=$HOLMES_SNAP shadow_snapshot=$SHADOW_SNAP"
    echo "holmes_evidence=(主链不产 rca_evidence，v2 锚=investigation_hash) shadow_evidence=$SHADOW_EVIDENCE"
    echo "holmes_tool_rows=$TOOL_ROWS_HOLMES shadow_tool_rows=$TOOL_ROWS_SHADOW"
} > "$E4_RUN_DIR/m4-09-facts.txt"

e4_summary
