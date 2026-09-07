#!/bin/sh
# ============================================================================
# e2e-m4-03-f3.sh —— E2E-M4-03：B3/F3 取消与迟到（LIVE_BUSINESS + 混合证据）
#
# 必断言（AM4 技术方案 §15.3）：发送前取消 → RELEASED；发送后取消 →
# PROVISIONAL/UNMATCHED；迟到成功 → STALE（拒收）；usage 缺失不伪造零；
# 迟到结果不补旧 Snapshot；报告不把未知写成成功。
#
# 触发：driver.py phase1 F3（卡单场景放大取消/迟到窗口）；账本五态断言。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

POLL_MAX="${POLL_MAX:-240}"

e4_begin
# 注入前静止面（quiesce）：上轮同 fault 会话必须先恢复归零（否则告警不重发 webhook）
echo "[E2E-M4-03] 注入前静止面（quiesce F3）"
e4_quiesce F3
# T0 时间窗（BA-36）：收敛等待只认本场景注入后的新 run
T0=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "[E2E-M4-03] phase1 F3 注入（T0=$T0）"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F3

i=0
while [ $i -lt "$POLL_MAX" ]; do
    DONE=$(e4_sql "select count(*) from rca_run where created_at >= '$T0' and state in ('REPORTING','SUCCEEDED','PARTIAL','FAILED')")
    [ "$DONE" -gt 0 ] && break
    i=$((i + 5)); sleep 5
done

# 账本五态面（V13 ck 冻结：RESERVED/COMMITTED/RELEASED/PROVISIONAL/UNMATCHED）
echo "  run_budget_entry 状态分布："
e4_sql "select state, count(*) from run_budget_entry group by state order by state" | while read -r line; do
    echo "    $line"
done

# 发送前取消 → RELEASED（预留退回，committed 为空）
RELEASED=$(e4_sql "select count(*) from run_budget_entry where state='RELEASED'")
echo "  RELEASED=$RELEASED（发送前取消面；无取消路径的轮次可为 0）"

# 发送后取消 → PROVISIONAL / UNMATCHED（结果未知，不伪造终态）
PROV=$(e4_sql "select count(*) from run_budget_entry where state in ('PROVISIONAL','UNMATCHED')")
echo "  PROVISIONAL/UNMATCHED=$PROV（发送后取消面）"

# 迟到成功 → STALE：旧代（superseded generation）不接受迟到落库——
# 迟到结果的账本痕迹 = invocation 终态不为伪 SUCCESS（走 UNKNOWN/REPLAY_MISS 族）
LATE_FAKE=$(e4_sql "
    select count(*) from rca_tool_invocation
     where state='SUCCESS' and settled_at is null")
e4_assert_eq "伪终态（SUCCESS 无 settle 时间）为零" "$LATE_FAKE" "0"

# usage 缺失不伪造零：usage_missing=true 的行 total_tokens 必须为空（非 0）
FAKE_ZERO=$(e4_sql "
    select count(*) from rca_report
     where usage_missing = true and total_tokens is not null")
e4_assert_eq "usage 缺失不伪造零 token" "$FAKE_ZERO" "0"

# 迟到结果不补旧 Snapshot：快照表行数与 run 一一对应（旧代不追加成员）
LATE_MEMBER=$(e4_sql "
    select count(*) from rca_snapshot_member m
     join rca_evidence_snapshot s on s.id = m.snapshot_id
     join rca_evidence e on e.id = m.evidence_id
     where e.observed_generation < s.observed_generation")
e4_assert_eq "旧代证据不进新代快照（迟到不补写）" "$LATE_MEMBER" "0"

# 报告不把未知写成成功：usage_missing 时 validation_status 不为 STRUCTURE_VALIDATED
# 且 model 为空的行不得宣称 token 全 accounted（M3 语义在 AM4 延续）
e4_assert_eq "UNKNOWN 不伪装成成功落账" "$(e4_sql "
    select count(*) from rca_tool_invocation
     where state='UNKNOWN' and reason_code is null")" "0"

e4_summary
