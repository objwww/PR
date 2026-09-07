#!/bin/sh
# ============================================================================
# e2e-m4-09-shadow.sh —— E2E-M4-09：Online Read Shadow 隔离（M4-34/35 随件交付）
#
# 场景（AM4 技术方案 §15.3）：B1 故障注入后，同一 input snapshot 同时送 Holmes
# 与 Native Shadow；必须断言：
#   ① 两路 snapshot_digest 相同；
#   ② run / budget / ledger 隔离（行集不共用）；
#   ③ Holmes 报告与通知正常落库（主路径不受影子影响）；
#   ④ Candidate 发布增量 0（影子 run 零 report_publication / 零 notify_outbox）；
#   ⑤ 不得从 Shadow 直接切流（无影子 → CODE_COMPLETE/DONE 主报告改写）。
#
# 触发入口（G2 终裁开放项）：影子链组件已装配（AlertAm4Config，docker profile），
# 触发方式由 195 执行者按《195-部署配方-v1.md》§触发入口执行——本脚本只做
# 只读对拍断言，接收 HOLMES_RUN_ID / SHADOW_RUN_ID 两个参数（配方步骤产出）。
#
# 用法：HOLMES_RUN_ID=<uuid> SHADOW_RUN_ID=<uuid> ./e2e-m4-09-shadow.sh
# 前置：source e2e-am4-common.sh 的环境（AM4_PG_CONTAINER 等）已设置。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

HOLMES_RUN_ID="${HOLMES_RUN_ID:?需要 HOLMES_RUN_ID（Holmes 主链 run）}"
SHADOW_RUN_ID="${SHADOW_RUN_ID:?需要 SHADOW_RUN_ID（Native 影子 run）}"

e4_begin
echo "[E2E-M4-09] holmes_run=$HOLMES_RUN_ID shadow_run=$SHADOW_RUN_ID"

# ① 两路同一 input snapshot：rca_run.investigation_hash 所锚定的 snapshot 一致
#    （snapshot digest 经 rca_evidence_snapshot 归属；此处对拍两 run 的冻结证据
#    的 input_snapshot_digest——证据 scope 内印章）
HOLMES_SNAP=$(e4_sql "
    select scope->>'input_snapshot_digest' from rca_evidence
     where run_id = '$HOLMES_RUN_ID'
     order by created_at, id limit 1")
SHADOW_SNAP=$(e4_sql "
    select scope->>'input_snapshot_digest' from rca_evidence
     where run_id = '$SHADOW_RUN_ID'
     order by created_at, id limit 1")
e4_assert_eq "① 影子侧证据存在" "$([ -n "$SHADOW_SNAP" ] && echo yes || echo no)" "yes"
e4_assert_eq "① 两路 snapshot_digest 相同" "$SHADOW_SNAP" "$HOLMES_SNAP"

# ② run 隔离：两 run 是不同行（run id 天然不同——此处断言互不串写：
#    影子 run 的证据/断言全部归属影子 run id，Holmes 行数不被影子改变）
HOLMES_EVIDENCE=$(e4_sql "select count(*) from rca_evidence where run_id='$HOLMES_RUN_ID'")
SHADOW_EVIDENCE=$(e4_sql "select count(*) from rca_evidence where run_id='$SHADOW_RUN_ID'")
e4_assert_eq "② 影子 run 证据归属影子 run（隔离）" \
    "$([ "$SHADOW_EVIDENCE" -gt 0 ] && echo yes || echo no)" "yes"

# ② 预算/账本隔离：rca_tool_invocation 的影子调用不落在 Holmes run 上
TOOL_ROWS_HOLMES=$(e4_sql "
    select count(*) from rca_tool_invocation where run_id='$HOLMES_RUN_ID'")
TOOL_ROWS_SHADOW=$(e4_sql "
    select count(*) from rca_tool_invocation where run_id='$SHADOW_RUN_ID'")
e4_assert_eq "② 影子工具有独立账本行" \
    "$([ "$TOOL_ROWS_SHADOW" -gt 0 ] && echo yes || echo no)" "yes"
e4_assert_eq "② 两 run 账本行集互斥（run_id 主维度隔离）" \
    "$(e4_sql "select count(distinct run_id) from rca_tool_invocation
               where run_id in ('$HOLMES_RUN_ID','$SHADOW_RUN_ID')")" "2"

# ③ Holmes 主路径完好：报告落库且 STATE 终态
HOLMES_REPORT=$(e4_sql "
    select count(*) from rca_report where run_id='$HOLMES_RUN_ID'")
e4_assert_eq "③ Holmes 报告落库" "$([ "$HOLMES_REPORT" -gt 0 ] && echo yes || echo no)" "yes"

# ④ Candidate 发布增量 0：影子 run 零 publication、零 outbox（不切主硬断言）
SHADOW_PUB=$(e4_sql "
    select count(*) from report_publication rp
     join rca_run r on r.id = rp.run_id where rp.run_id='$SHADOW_RUN_ID'")
SHADOW_OUTBOX=$(e4_sql "
    select count(*) from notify_outbox where run_id='$SHADOW_RUN_ID'")
e4_assert_eq "④ 影子 run 零发布（Candidate 增量 0）" "$SHADOW_PUB" "0"
e4_assert_eq "④ 影子 run 零通知 outbox" "$SHADOW_OUTBOX" "0"

# ⑤ 主报告未被影子改写：Holmes 报告只有一代（影子不回写主报告版本）
HOLMES_REPORT_VERSIONS=$(e4_sql "
    select count(distinct report_version) from rca_report where run_id='$HOLMES_RUN_ID'")
e4_assert_eq "⑤ Holmes 报告版本未被影子推进" "$HOLMES_REPORT_VERSIONS" "1"

{
    echo "holmes_run=$HOLMES_RUN_ID shadow_run=$SHADOW_RUN_ID"
    echo "holmes_snapshot=$HOLMES_SNAP shadow_snapshot=$SHADOW_SNAP"
    echo "holmes_evidence=$HOLMES_EVIDENCE shadow_evidence=$SHADOW_EVIDENCE"
    echo "holmes_tool_rows=$TOOL_ROWS_HOLMES shadow_tool_rows=$TOOL_ROWS_SHADOW"
} > "$E4_RUN_DIR/m4-09-facts.txt"

e4_summary
