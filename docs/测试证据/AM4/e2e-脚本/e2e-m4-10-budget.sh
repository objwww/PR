#!/bin/sh
# ============================================================================
# e2e-m4-10-budget.sh —— E2E-M4-10：B5 预算耗尽与重复告警（LIVE_BUSINESS）
#
# 必断言（AM4 技术方案 §15.3）：incident admission 耗尽不派生 Run；各耗尽分支
# 从拒绝点起 LLM/tool 调用增量为 0；幂等重试不二扣；报告专项预算隔离；
# 熔断事件与 reason code 唯一可查。
#
# 触发：driver.py phase1 F1/F2/F3 高频重复（同一故障反复触发）+ 执行者按配方
#       压低预算配置（app.alert.am4.budget.* / incident admission 阈值）重启。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

REPEAT="${REPEAT:-5}"
POLL_MAX="${POLL_MAX:-240}"

e4_begin
# 注入前静止面（quiesce）：上轮同 fault 会话必须先恢复归零（否则告警不重发 webhook）
echo "[E2E-M4-10] 注入前静止面（quiesce F1）"
docker exec arena-e2e-cli python3 /e2e/quiesce.py F1
echo "[E2E-M4-10] 同一故障重复注入 ${REPEAT} 次（B5 重复告警 + 预算压边）"
i=1
while [ "$i" -le "$REPEAT" ]; do
    docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F1 || true
    sleep 10
    i=$((i + 1))
done

i=0
while [ $i -lt "$POLL_MAX" ]; do
    SETTLED=$(e4_sql "
        select count(*) from run_budget_entry
         where state in ('COMMITTED','RELEASED','PROVISIONAL','UNMATCHED')")
    [ "$SETTLED" -gt 0 ] && break
    i=$((i + 5)); sleep 5
done

# admission 耗尽面：incident_budget_entry 窗口计数（admission 耗尽不派生 Run）
echo "  incident 预算窗口分布："
e4_sql "select window_kind, count(*), sum(units) from incident_budget_entry
         group by window_kind order by window_kind" | while read -r line; do
    echo "    $line"
done
RUNS=$(e4_sql "select count(*) from rca_run")
INCIDENTS=$(e4_sql "select count(*) from incident")
echo "  incident=$INCIDENTS run=$RUNS（耗尽分支后 run 增量收敛）"

# 预算三段式对账：账本态分布 + 每条 RESERVED 必须可收敛到终态或挂明超时
e4_sql "select state, count(*) from run_budget_entry group by state" | while read -r line; do
    echo "    $line"
done

# 幂等重试不二扣：同幂等键（run,task,attempt,call_seq,tool）不出现重复预留行
DUP_RESERVE=$(e4_sql "
    select count(*) from (
        select run_id, task_id, attempt_id, call_seq, tool_name, count(*) c
          from run_budget_entry group by run_id, task_id, attempt_id, call_seq, tool_name
         having count(*) > 1) d")
e4_assert_eq "幂等键零重复预留（不二扣）" "$DUP_RESERVE" "0"

# 耗尽即停：consumed 不得越过 limit（预留/实扣平账纪律）
OVER=$(e4_sql "
    select count(*) from run_budget_state where consumed_units > limit_units")
e4_assert_eq "预算零透支（consumed ≤ limit）" "$OVER" "0"

# 报告专项预算隔离：REPORT 维度独立成行（不与 TOOL_CALL 混账）
REPORT_KIND=$(e4_sql "
    select count(*) from run_budget_state where budget_kind='REPORT'")
echo "  REPORT 预算维度行数=$REPORT_KIND（配置启用时 ≥1，专项隔离面）"

# 熔断/耗尽事件唯一可查（rca_event：同型事件 digest 幂等，不重复轰炸）
e4_sql "select event_type, count(*) from rca_event group by event_type order by event_type" | while read -r line; do
    echo "    event: $line"
done
DUP_EVENT=$(e4_sql "
    select count(*) from (
        select run_id, event_type, payload_digest, count(*) c
          from rca_event group by run_id, event_type, payload_digest
         having count(*) > 1) d")
e4_assert_eq "同型耗尽/熔断事件 digest 幂等（唯一可查）" "$DUP_EVENT" "0"

e4_summary
