#!/bin/sh
# ============================================================================
# e2e-m4-01-f1.sh —— E2E-M4-01：B1/F1 真实订单请求触发告警跑完 Native DAG
#                     （LIVE_BUSINESS + 混合证据）
#
# 必断言（AM4 技术方案 §15.3）：同 generation；真实 Prometheus 证据 + 明示的
# Logs/Change replay 证据可回查；事件序列与 DAG 终态闭合；报告根因命中 GT
# 或诚实进入不确定分支；无证据的确定根因必须失败；Candidate 发布增量为 0。
#
# 触发：arena-e2e-cli 内 driver.py phase1 F1（受控故障注入口，CHAOS_ADMIN_TOKEN）；
#       Native 影子 run 由执行者按《195-部署配方-v1.md》§5 触发后传 SHADOW_RUN_ID。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

SHADOW_RUN_ID="${SHADOW_RUN_ID:-}"
POLL_MAX="${POLL_MAX:-240}"

e4_begin
echo "[E2E-M4-01] phase1 F1 注入（arena-chaos-admin 受控注入口）"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F1

# 等待 Holmes 主链跑完（run → REPORTING → report STRUCTURE_VALIDATED）
i=0
RUN_ID=""
while [ $i -lt "$POLL_MAX" ]; do
    RUN_ID=$(e4_sql "select id from rca_run order by created_at desc limit 1")
    DONE=$(e4_sql "
        select count(*) from rca_run r join rca_report rr on rr.run_id = r.id
         where r.state in ('REPORTING','SUCCEEDED','PARTIAL')")
    [ "$DONE" -gt 0 ] && break
    i=$((i + 5)); sleep 5
done
[ -n "$RUN_ID" ] || { echo "  FAIL: 无 run 产生"; exit 1; }
echo "  run=$RUN_ID"

# 同 generation：incident 当前代与 run 代一致
e4_assert_eq "run 与 incident 同 generation" "$(e4_sql "
    select count(*) from rca_run r join incident i on i.id = r.incident_id
     where r.generation = i.generation and r.id = '$RUN_ID'")" "1"

# 真实 Prometheus 证据 + 明示 replay 证据可回查（混合证据保真度）
PROM_EVIDENCE=$(e4_sql "
    select count(*) from rca_evidence
     where run_id='$RUN_ID' and source='prometheus'")
REPLAY_EVIDENCE=$(e4_sql "
    select count(*) from rca_evidence
     where run_id='$RUN_ID' and source in ('logs','change')")
e4_assert_eq "Prometheus 证据存在（真实源）" "$([ "$PROM_EVIDENCE" -gt 0 ] && echo yes || echo no)" "yes"
e4_assert_eq "Logs/Change replay 证据存在（fixture 明示）" "$([ "$REPLAY_EVIDENCE" -gt 0 ] && echo yes || echo no)" "yes"

# 证据完整性：payload_digest 全部非空（五步 digest 纪律的落库面）
e4_assert_eq "证据行 payload_digest 无空" "$(e4_sql "
    select count(*) from rca_evidence
     where run_id='$RUN_ID' and (payload_digest is null or payload_digest='')")" "0"

# DAG 终态闭合：run 内无永久 BLOCKED/READY 残留任务
e4_assert_eq "DAG 任务终态闭合" "$(e4_sql "
    select count(*) from rca_task where run_id='$RUN_ID'
      and state in ('READY','BLOCKED','RUNNING')")" "0"

# 报告落库且验证态合法（STRUCTURE_VALIDATED 或 REJECTED_*，不伪造成功）
REPORT_STATUS=$(e4_sql "
    select validation_status from rca_report where run_id='$RUN_ID' order by created_at desc limit 1")
e4_assert_eq "报告验证态非空" "$([ -n "$REPORT_STATUS" ] && echo yes || echo no)" "yes"
echo "  report_status=$REPORT_STATUS（根因命中 GT 的判定归 eval 正式门禁面，非 E2E SQL 断言）"

# 无证据的确定根因必须失败：TRUE/FALSE claim 必须有 evidence_refs 引用
e4_assert_eq "确定 claim 均引用证据（无证据不产确认根因）" "$(e4_sql "
    select count(*) from rca_claim
     where run_id='$RUN_ID' and status in ('TRUE','FALSE')
       and (evidence_refs is null or evidence_refs = '[]')")" "0"

# Candidate 发布增量 0（影子 run 已触发时）
if [ -n "$SHADOW_RUN_ID" ]; then
    e4_assert_eq "影子 run 零发布行" "$(e4_sql "
        select count(*) from report_publication rp
         join rca_report rr on rr.id = rp.report_id
         where rr.run_id = '$SHADOW_RUN_ID'")" "0"
    e4_assert_eq "影子 run 零报告行" "$(e4_sql "
        select count(*) from rca_report where run_id='$SHADOW_RUN_ID'")" "0"
    SNAP_H=$(e4_sql "
        select scope::jsonb->>'input_snapshot_digest' from rca_evidence
         where run_id='$RUN_ID' order by created_at, id limit 1")
    SNAP_S=$(e4_sql "
        select scope::jsonb->>'input_snapshot_digest' from rca_evidence
         where run_id='$SHADOW_RUN_ID' order by created_at, id limit 1")
    e4_assert_eq "两路同一 input snapshot" "$SNAP_S" "$SNAP_H"
fi

e4_summary
